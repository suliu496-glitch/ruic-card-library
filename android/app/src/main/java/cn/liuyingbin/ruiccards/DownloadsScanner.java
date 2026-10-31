package cn.liuyingbin.ruiccards;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded Download discovery and data-only import. All methods run on the shell's IO executor. */
final class DownloadsScanner {
    static final int MAX_RESULTS = 100, MAX_SELECTION = 50;
    private static final int MAX_DEPTH = 8, MAX_DIRECTORIES = 512, MAX_VISITED = 10000;
    interface Access { boolean granted(); }
    interface Progress { void update(JSONObject progress); }
    private final File downloadRoot, cacheRoot;
    private final CardStore store;
    private final DownloadImportLedger ledger;
    private final Access access;
    private final Map<String, Candidate> current = new ConcurrentHashMap<>();
    private boolean initialized;

    DownloadsScanner(File downloadRoot, File cacheRoot, File ledgerFile, CardStore store, Access access) {
        this.downloadRoot = downloadRoot;
        this.cacheRoot = cacheRoot;
        this.store = store;
        this.access = access;
        ledger = new DownloadImportLedger(ledgerFile, store);
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        File[] residue = cacheRoot.listFiles(file -> file.getName().startsWith("download-import-"));
        if (residue != null) for (File file : residue) file.delete();
    }

    JSONObject scan() {
        initialize();
        Map<String, String> previousIds = new HashMap<>();
        for (Candidate previous : current.values()) previousIds.put(previous.fingerprint, previous.id);
        current.clear();
        if (!access.granted()) return report("permission_required", "请授权读取下载目录，授权后会自动重新扫描", new JSONArray(), false);
        List<Candidate> candidates = new ArrayList<>();
        boolean truncated = false;
        boolean stop = false;
        try {
            if (!downloadRoot.exists()) return report("ready", "下载目录中暂无闪卡包", new JSONArray(), false);
            Path root = downloadRoot.getCanonicalFile().toPath();
            if (Files.isSymbolicLink(downloadRoot.toPath()) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("无法读取下载目录");
            ArrayDeque<Directory> pending = new ArrayDeque<>();
            pending.add(new Directory(root, 0));
            int directories = 1, visited = 0;
            while (!pending.isEmpty() && !stop) {
                ensureAccess();
                Directory directory = pending.removeFirst();
                try (DirectoryStream<Path> children = Files.newDirectoryStream(directory.path)) {
                    for (Path child : children) {
                        if (++visited > MAX_VISITED) { truncated = true; stop = true; break; }
                        if (Files.isSymbolicLink(child)) continue;
                        Path canonical = child.toFile().getCanonicalFile().toPath();
                        if (!canonical.startsWith(root) || !canonical.equals(child.toAbsolutePath().normalize())) continue;
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            if (directory.depth >= MAX_DEPTH || directories >= MAX_DIRECTORIES) truncated = true;
                            else { pending.add(new Directory(child, directory.depth + 1)); directories++; }
                            continue;
                        }
                        if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue;
                        String name = child.getFileName().toString(), lower = name.toLowerCase(Locale.ROOT);
                        if (!lower.endsWith(".ruic") && !lower.endsWith(".zip")) continue;
                        File source = child.toFile();
                        long size = source.length(), modified = source.lastModified();
                        if (size <= 0 || size > CardStore.MAX_ARCHIVE) continue;
                        ArchiveInfo info;
                        try { info = inspect(source); }
                        catch (Exception invalid) {
                            // Explicit .ruic downloads remain visible so a broken download has an actionable error.
                            if (!lower.endsWith(".ruic")) continue;
                            info = ArchiveInfo.single();
                        }
                        if (info == null) continue;
                        if (candidates.size() >= MAX_RESULTS) { truncated = true; stop = true; break; }
                        candidates.add(new Candidate(canonical.toFile(), root.toFile(), size, modified, info.cardCount(), previousIds));
                    }
                } catch (java.nio.file.AccessDeniedException ignored) { truncated = true; }
            }
            ensureAccess();
            candidates.sort(Comparator.comparingLong((Candidate item) -> item.modified).reversed().thenComparing(item -> item.file.getName()));
            JSONArray files = new JSONArray();
            for (Candidate item : candidates) {
                current.put(item.id, item);
                files.put(new JSONObject().put("id", item.id).put("name", item.file.getName()).put("size", item.size)
                        .put("modified", item.modified).put("cardCount", item.cardCount)
                        .put("imported", ledger.importedFingerprint(item.fingerprint)));
            }
            return report("ready", files.length() == 0 ? "下载目录中暂无闪卡包" : "找到 " + files.length() + " 个闪卡包", files, truncated);
        } catch (Exception error) {
            current.clear();
            return report(access.granted() ? "error" : "permission_required", CardStore.safeError(error), new JSONArray(), false);
        }
    }

    JSONObject importSelected(String idsJson, Progress progress) {
        initialize();
        Counts counts = new Counts();
        List<String> errors = new ArrayList<>();
        try {
            if (idsJson == null || idsJson.length() > 8192) throw new IOException("导入选择无效，请重新扫描");
            JSONArray requested = new JSONArray(idsJson);
            if (requested.length() == 0 || requested.length() > MAX_SELECTION) throw new IOException("每次请选择 1 至 50 个闪卡包");
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < requested.length(); i++) {
                Object id = requested.get(i);
                if (!(id instanceof String)) throw new IOException("导入选择无效，请重新扫描");
                selected.add((String) id);
            }
            counts.total = selected.size();
            progress.update(counts.json());
            for (String id : selected) {
                Candidate item = current.get(id);
                try {
                    if (item == null) throw new IOException("扫描结果已过期，请重新扫描");
                    importCandidate(item, counts, errors);
                } catch (Exception error) {
                    counts.failed++;
                    errors.add((item == null ? "" : item.file.getName() + "：") + CardStore.safeError(error));
                }
                counts.completed++;
                progress.update(counts.json());
            }
        } catch (Exception error) { counts.failed++; errors.add(CardStore.safeError(error)); }
        String message = "已导入 " + counts.imported + " 张，跳过 " + counts.skipped + " 张，失败 " + counts.failed + " 个";
        if (!errors.isEmpty()) message += "；" + errors.get(0);
        try { return counts.json().put("ok", counts.failed == 0 || counts.imported > 0 || counts.skipped > 0).put("message", message); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** Native preview code can resolve only a current opaque token, with the same access checks as import. */
    File resolveCurrentFile(String id) throws IOException {
        Candidate item = current.get(id);
        if (item == null) throw new IOException("扫描结果已过期，请重新扫描");
        verify(item);
        return item.file;
    }

    /** Explicitly opened documents have URI grants; only their private validated snapshots reach this method. */
    CardStore.ImportResult importPreviewFile(File snapshot, String displayName) throws Exception {
        initialize();
        File canonical = privateSnapshot(snapshot);
        ArchiveInfo info = inspect(canonical);
        if (info == null || !info.packages.isEmpty()) throw new IOException("预览导入只支持单张闪卡包");
        return importPackage(canonical, displayName, digestFile(canonical), null);
    }

    /** Associate a saved preview only with the unchanged Download source that produced its private snapshot. */
    void rememberPreviewSource(File original, long expectedSize, long expectedModified, File snapshot, List<String> activeIds) throws Exception {
        if (original == null || activeIds.isEmpty() || !access.granted()) return;
        File root = downloadRoot.getCanonicalFile(), source = original.getAbsoluteFile();
        if (!source.equals(original.getCanonicalFile()) || !source.toPath().startsWith(root.toPath())) return;
        Candidate item = new Candidate(source, root, expectedSize, expectedModified, activeIds.size(), java.util.Collections.emptyMap());
        try { verify(item); } catch (IOException changed) { return; }
        File canonicalSnapshot = privateSnapshot(snapshot);
        if (canonicalSnapshot.length() != expectedSize) throw new IOException("预览源文件长度无效");
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(activeIds));
        for (String id : uniqueIds) if (!store.containsCard(id)) return;
        String digest = digestFile(canonicalSnapshot);
        try { verify(item); } catch (IOException changed) { return; }
        ledger.rememberSourceAndFingerprint(digest, uniqueIds, item.fingerprint);
    }

    void rollbackPreviewImports(List<String> addedIds) { for (String id : addedIds) store.deleteCard(id); }

    private File privateSnapshot(File snapshot) throws IOException {
        File canonical = snapshot.getCanonicalFile();
        if (!canonical.toPath().startsWith(cacheRoot.getCanonicalFile().toPath())
                || Files.isSymbolicLink(snapshot.toPath()) || !canonical.isFile()) throw new IOException("预览文件已失效，请重新打开");
        return canonical;
    }

    private static String digestFile(File canonical) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long readBytes = 0;
        byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = input.read(buffer)) != -1;) {
                readBytes += read;
                if (readBytes > CardStore.MAX_ARCHIVE) throw new IOException("闪卡包超过 512 MB");
                hash.update(buffer, 0, read);
            }
        }
        return hex(hash.digest());
    }

    private void importCandidate(Candidate item, Counts counts, List<String> errors) throws Exception {
        verify(item);
        File snapshot = temporary();
        try {
            String digest;
            try (InputStream input = Files.newInputStream(item.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                digest = copyHashed(input, snapshot, CardStore.MAX_ARCHIVE);
            }
            verify(item);
            if (snapshot.length() != item.size) throw new IOException("下载文件已改变，请重新扫描");
            ArchiveInfo info = inspect(snapshot);
            if (info == null) throw new IOException("该文件不是闪卡包，应用备份请使用选择文件导入");
            ensureAccess();
            List<String> known = ledger.activeIds(digest);
            if (known != null) {
                ledger.rememberFingerprint(item.fingerprint, digest);
                counts.skipped += known.size();
                return;
            }
            List<String> importedIds = new ArrayList<>(), addedIds = new ArrayList<>();
            if (info.packages.isEmpty()) {
                CardStore.ImportResult outcome = importPackage(snapshot, item.file.getName(), digest, null);
                count(outcome, counts); importedIds.addAll(outcome.activeIds); addedIds.addAll(outcome.addedIds);
            }
            else {
                int failuresBefore = counts.failed;
                CardStore.SharedImportBudget budget = new CardStore.SharedImportBudget();
                try (ZipFile zip = new ZipFile(snapshot, StandardCharsets.UTF_8)) {
                    for (String packageName : info.packages) {
                        File nested = temporary();
                        try {
                            ensureAccess();
                            ZipEntry entry = zip.getEntry(packageName);
                            String packageDigest;
                            try (InputStream input = zip.getInputStream(entry)) { packageDigest = copyHashed(input, nested, CardStore.MAX_ENTRY, budget); }
                            if (nested.length() != entry.getSize()) throw new IOException("合集内闪卡包长度无效");
                            ArchiveInfo nestedInfo = inspect(nested);
                            if (nestedInfo == null || !nestedInfo.packages.isEmpty()) throw new IOException("合集内只支持单张 ruic 闪卡包");
                            ensureAccess();
                            CardStore.ImportResult outcome = importPackage(nested, packageName, packageDigest, budget);
                            count(outcome, counts); importedIds.addAll(outcome.activeIds); addedIds.addAll(outcome.addedIds);
                        } catch (Exception error) {
                            counts.failed++;
                            errors.add(packageName.substring(packageName.lastIndexOf('/') + 1) + "：" + CardStore.safeError(error));
                        }
                        finally { nested.delete(); }
                    }
                }
                if (counts.failed != failuresBefore) return; // Individual successes are already recorded for a partial retry.
            }
            try { ledger.rememberSourceAndFingerprint(digest, importedIds, item.fingerprint); }
            catch (Exception failedRecord) {
                rollbackPreviewImports(addedIds);
                counts.imported -= addedIds.size();
                throw failedRecord;
            }
        } finally { snapshot.delete(); }
    }

    private CardStore.ImportResult importPackage(File source, String name, String digest, CardStore.SharedImportBudget budget) throws Exception {
        List<String> known = ledger.activeIds(digest);
        if (known != null) return new CardStore.ImportResult(0, null, java.util.Collections.emptyList(), known);
        CardStore.ImportResult imported = store.importFile(source, name, budget);
        try { ledger.remember(digest, imported.addedIds); }
        catch (Exception failedRecord) { rollbackPreviewImports(imported.addedIds); throw failedRecord; }
        return imported;
    }

    private static void count(CardStore.ImportResult outcome, Counts counts) {
        counts.imported += outcome.count;
        if (outcome.count == 0) counts.skipped += outcome.activeIds.size();
    }

    private void verify(Candidate item) throws IOException {
        ensureAccess();
        File root = downloadRoot.getCanonicalFile(), canonical = item.file.getCanonicalFile();
        if (!root.equals(item.root) || !canonical.equals(item.file) || !canonical.toPath().startsWith(root.toPath()))
            throw new IOException("下载文件位置已改变，请重新扫描");
        Path path = item.file.toPath();
        for (Path part = path; part != null && !part.equals(root.toPath()); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("不支持链接文件，请重新扫描");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("下载文件已删除或无法读取，请重新扫描");
        if (item.file.length() != item.size || item.file.lastModified() != item.modified)
            throw new IOException("下载文件已改变，请重新扫描");
    }

    /** Only central-directory metadata is read during discovery, never a full archive hash or extraction. */
    static ArchiveInfo inspect(File archive) throws IOException {
        if (archive.length() > CardStore.MAX_ARCHIVE) throw new IOException("闪卡包超过 512 MB");
        checkCentralDirectory(archive);
        List<String> packages = new ArrayList<>();
        Set<String> seen = new HashSet<>(), cardRoots = new HashSet<>();
        boolean allData = true, backup = false;
        int entries = 0;
        long expanded = 0;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (++entries > 1024) throw new IOException("闪卡包文件数量超过 1024");
                String original = entry.getName(), name = original;
                if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (!CardStore.safeRelativePath(name) || !seen.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException("闪卡包包含无效、重复或越界路径");
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > CardStore.MAX_ENTRY) throw new IOException("闪卡包单文件超过 96 MB");
                expanded += entry.getSize();
                if (expanded > CardStore.MAX_EXPANDED) throw new IOException("闪卡包展开大小超过 768 MB");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("library-state.json") || lower.endsWith("/library-state.json")) backup = true;
                if (lower.endsWith(".ruic")) packages.add(original);
                if (!CardStore.allowedDataFile(name)) allData = false;
                if (lower.equals("card.json") || lower.equals("card-config.json")) cardRoots.add("");
                else if (lower.endsWith("/card.json") || lower.endsWith("/card-config.json"))
                    cardRoots.add(name.substring(0, name.lastIndexOf('/')));
            }
        }
        if (backup) return null;
        if (!packages.isEmpty()) return new ArchiveInfo(packages);
        if (!allData || cardRoots.size() != 1) return null;
        String root = cardRoots.iterator().next();
        return root.contains("/") ? null : ArchiveInfo.single();
    }

    // Check the small end record before ZipFile allocates central-directory metadata for an untrusted archive.
    private static void checkCentralDirectory(File archive) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            int length = (int) Math.min(input.length(), 65557);
            byte[] tail = new byte[length];
            input.seek(input.length() - length); input.readFully(tail);
            for (int i = length - 22; i >= 0; i--) {
                if (tail[i] != 'P' || tail[i + 1] != 'K' || tail[i + 2] != 5 || tail[i + 3] != 6
                        || i + 22 + unsignedShort(tail, i + 20) != length) continue;
                int entries = unsignedShort(tail, i + 10);
                if (unsignedShort(tail, i + 4) != 0 || unsignedShort(tail, i + 6) != 0
                        || unsignedShort(tail, i + 8) != entries) throw new IOException("不支持分卷闪卡包");
                if (entries > 1024) throw new IOException("闪卡包文件数量超过 1024");
                long directoryBytes = unsignedInt(tail, i + 12), directoryOffset = unsignedInt(tail, i + 16);
                if (directoryBytes > 8 * 1024 * 1024L || directoryOffset + directoryBytes > input.length() - length + i)
                    throw new IOException("闪卡包目录大小无效或超限");
                return;
            }
        }
        throw new IOException("闪卡包不是完整 ZIP 文件");
    }

    private static int unsignedShort(byte[] bytes, int offset) { return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8; }
    private static long unsignedInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | (long) unsignedShort(bytes, offset + 2) << 16;
    }

    private void ensureAccess() throws IOException { if (!access.granted()) throw new IOException("下载目录读取权限已关闭，请重新授权"); }
    private File temporary() throws IOException { return File.createTempFile("download-import-", ".ruic", cacheRoot); }
    private static String copyHashed(InputStream input, File target, long max) throws Exception {
        return copyHashed(input, target, max, null);
    }
    private static String copyHashed(InputStream input, File target, long max, CardStore.SharedImportBudget budget) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        byte[] buffer = new byte[65536];
        try (FileOutputStream output = new FileOutputStream(target)) {
            for (int read; (read = input.read(buffer)) != -1;) {
                copied += read;
                if (copied > max) throw new IOException("文件超过大小限制");
                if (budget != null) budget.reserve(read);
                digest.update(buffer, 0, read); output.write(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }
    private static String fingerprint(File file, long size, long modified) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest((file.getPath() + "\n" + size + "\n" + modified).getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }
    private static JSONObject report(String status, String message, JSONArray files, boolean truncated) {
        try { return new JSONObject().put("status", status).put("message", message).put("files", files).put("truncated", truncated); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static final class ArchiveInfo {
        final List<String> packages;
        ArchiveInfo(List<String> packages) { this.packages = packages; }
        static ArchiveInfo single() { return new ArchiveInfo(new ArrayList<>()); }
        int cardCount() { return packages.isEmpty() ? 1 : packages.size(); }
    }
    private static final class Candidate {
        final String id, fingerprint;
        final File file, root;
        final long size, modified;
        final int cardCount;
        Candidate(File file, File root, long size, long modified, int cardCount, Map<String, String> previousIds) throws Exception {
            this.file = file; this.root = root; this.size = size; this.modified = modified; this.cardCount = cardCount;
            fingerprint = fingerprint(file, size, modified);
            String previous = previousIds.get(fingerprint);
            id = previous == null ? UUID.randomUUID().toString() : previous;
        }
    }
    private static final class Directory {
        final Path path; final int depth;
        Directory(Path path, int depth) { this.path = path; this.depth = depth; }
    }
    private static final class Counts {
        int completed, total, imported, skipped, failed;
        JSONObject json() {
            try { return new JSONObject().put("completed", completed).put("total", total).put("imported", imported).put("skipped", skipped).put("failed", failed); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
