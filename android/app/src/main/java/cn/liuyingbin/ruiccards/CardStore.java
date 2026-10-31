package cn.liuyingbin.ruiccards;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Constrained data importer. ZIP contents are never copied directly into a WebView asset tree. */
final class CardStore {
    static final int MAX_STATE_BYTES = 1024 * 1024;
    private static final long MIB = 1024L * 1024L;
    static final long MAX_ARCHIVE = 512 * MIB, MAX_EXPANDED = 768 * MIB, MAX_ENTRY = 96 * MIB;
    private static final String STORAGE = "https://appassets.androidplatform.net/storage/";
    private final Context context;
    final File cardRoot;

    CardStore(Context context) {
        this(context, new File(context.getFilesDir(), "cards"));
        File[] temporary = context.getCacheDir().listFiles(file -> file.getName().startsWith("incoming-")
                || file.getName().startsWith("unpack-") || file.getName().startsWith("clean-")
                || file.getName().startsWith("backup-") || file.getName().startsWith("import_"));
        if (temporary != null) for (File file : temporary) deleteTree(file);
    }

    /** A separate private root permits disposable previews without publishing into the user's library. */
    CardStore(Context context, File privateCardRoot) {
        this.context = context.getApplicationContext();
        cardRoot = privateCardRoot;
        cardRoot.mkdirs();
        File[] unfinished = cardRoot.listFiles(file -> file.getName().startsWith(".pending-"));
        if (unfinished != null) for (File file : unfinished) deleteTree(file);
    }

    synchronized JSONArray listCards() {
        JSONArray cards = new JSONArray();
        File[] dirs = cardRoot.listFiles(File::isDirectory);
        if (dirs == null) return cards;
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        for (File dir : dirs) {
            try {
                if (!validId(dir.getName())) continue;
                cards.put(new JSONObject(readText(new File(dir, "card.json"), 64 * 1024)));
            } catch (Exception ignored) { }
        }
        return cards;
    }

    synchronized boolean deleteCard(String id) {
        if (!validId(id)) return false;
        File dir = new File(cardRoot, id);
        if (!dir.isDirectory()) return false;
        return deleteTree(dir);
    }

    ImportResult importUri(Uri uri) throws Exception {
        String displayName = "闪卡";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        File incoming = new File(context.getCacheDir(), "incoming-" + UUID.randomUUID());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(incoming)) {
                if (input == null) throw new IOException("无法读取所选文件");
                copyLimited(input, output, lower.endsWith(".zip") || lower.endsWith(".ruic") ? MAX_ARCHIVE : MAX_ENTRY);
            }
            if (isZip(incoming)) return importZip(incoming, true);
            validateImage(incoming);
            return importImage(incoming, displayName);
        } finally { incoming.delete(); }
    }

    /** Download imports are data-only packages; restoring app state remains an explicit SAF action. */
    ImportResult importFile(File source, String displayName) throws Exception {
        return importFile(source, displayName, null);
    }

    ImportResult importFile(File source, String displayName, SharedImportBudget budget) throws Exception {
        if (source.length() > MAX_ARCHIVE) throw new IOException("闪卡包超过 512 MB");
        if (!isZip(source)) throw new IOException("闪卡包不是有效 ZIP 文件");
        return importZip(source, false, budget);
    }

    synchronized boolean containsCard(String id) {
        return validId(id) && new File(new File(cardRoot, id), "card.json").isFile();
    }

    private ImportResult importImage(File image, String displayName) throws Exception {
        String id = newId();
        File staging = new File(context.getCacheDir(), id);
        File assets = new File(staging, "assets");
        requireDirectory(assets);
        try {
            String fileName = "image." + imageExtension(image);
            copyFile(image, new File(assets, fileName));
            copyFile(image, new File(staging, "cover." + imageExtension(image)));
            String title = cleanText(displayName.replaceFirst("\\.[^.]+$", ""), 80, "我的闪卡");
            JSONObject config = new JSONObject().put("title", title).put("sourceMode", "image")
                    .put("assets", new JSONObject().put("image", "./assets/" + fileName));
            JSONObject card = metadata(id, config, "image", "cover." + imageExtension(image));
            writeText(new File(staging, "card-config.json"), config.toString(2));
            writeText(new File(staging, "card.json"), card.toString(2));
            commit(staging, id);
            return new ImportResult(1, null, java.util.Collections.singletonList(id));
        } finally { deleteTree(staging); }
    }

    private ImportResult importZip(File archive, boolean allowBackup) throws Exception {
        return importZip(archive, allowBackup, null);
    }

    private ImportResult importZip(File archive, boolean allowBackup, SharedImportBudget budget) throws Exception {
        File unpack = new File(context.getCacheDir(), "unpack-" + UUID.randomUUID());
        requireDirectory(unpack);
        try {
            unpackDataZip(archive, unpack, budget);
            File stateFile = new File(unpack, "library-state.json");
            boolean backup = stateFile.isFile();
            if (backup && !allowBackup) throw new IOException("应用备份请使用选择文件导入");
            JSONObject state = stateFile.isFile() ? new JSONObject(readText(stateFile, MAX_STATE_BYTES)) : null;
            List<File> roots = new ArrayList<>();
            findCardRoots(unpack, roots, 0);
            if (roots.isEmpty()) {
                if (backup) return new ImportResult(0, state);
                throw new IOException("闪卡包缺少 card.json 或 card-config.json");
            }
            if (!backup && (roots.size() != 1 || relative(unpack, roots.get(0)).split("/").length > 1
                    && !roots.get(0).equals(unpack))) throw new IOException("闪卡包只允许一层外目录及一张卡；多卡请使用应用备份包");
            List<String> addedIds = new ArrayList<>();
            try {
                for (File root : roots) {
                    String importId = newId();
                    if (backup) {
                        String rel = relative(unpack, root);
                        if (!rel.matches("cards/[a-z0-9_-]{1,80}")) throw new IOException("备份闪卡目录无效");
                        importId = root.getName();
                    }
                    if (importPackage(root, importId)) addedIds.add(importId);
                }
            } catch (Exception error) {
                // A malformed multi-card backup must not leave a silently half-imported library.
                for (String id : addedIds) deleteCard(id);
                throw error;
            }
            return new ImportResult(addedIds.size(), state, addedIds);
        } finally { deleteTree(unpack); }
    }

    private boolean importPackage(File source, String preservedId) throws Exception {
        String id = preservedId == null ? newId() : preservedId;
        synchronized (this) { if (new File(cardRoot, id).exists()) return false; }
        File staging = new File(context.getCacheDir(), "clean-" + UUID.randomUUID());
        requireDirectory(new File(staging, "assets"));
        try {
            File configFile = new File(source, "card-config.json");
            File cardFile = new File(source, "card.json");
            JSONObject config = configFile.isFile() ? new JSONObject(readText(configFile, 256 * 1024))
                    : new JSONObject(readText(cardFile, 256 * 1024));
            JSONObject info = cardFile.isFile() ? new JSONObject(readText(cardFile, 256 * 1024)) : config;
            JSONObject inputAssets = config.optJSONObject("assets");
            if (inputAssets == null) inputAssets = new JSONObject();
            JSONObject clean = cleanConfig(config, info);
            JSONObject assets = new JSONObject();
            int layers = 0;
            for (String key : new String[]{"subject", "background", "text", "lineart", "effects", "image", "model"}) {
                String defaultName = key.equals("model") ? "assets/card.glb" : "assets/" + key + ".png";
                String candidate = inputAssets.optString(key, defaultName);
                File inputFile = safeAsset(source, candidate, key.equals("model"));
                if (inputFile == null || !inputFile.isFile()) continue;
                if (key.equals("model")) validateGlb(inputFile);
                else validateImage(inputFile);
                String extension = key.equals("model") ? "glb" : imageExtension(inputFile);
                String outputName = key.equals("model") ? "card.glb" : key + "." + extension;
                copyFile(inputFile, new File(staging, "assets/" + outputName));
                assets.put(key, "./assets/" + outputName);
                if (key.equals("subject") || key.equals("background") || key.equals("text") || key.equals("lineart")) layers++;
            }
            boolean layered = layers == 4 && assets.has("model");
            File cover = null;
            String coverCandidate = info.optString("cover", "cover.png");
            if (!coverCandidate.startsWith("http")) cover = safeAsset(source, coverCandidate, false);
            if (cover == null || !cover.isFile()) {
                for (String name : new String[]{"cover.png", "cover.jpg", "cover.webp", "assets/image.png", "assets/image.jpg", "assets/image.webp"}) {
                    File possible = new File(source, name);
                    if (possible.isFile()) { cover = possible; break; }
                }
            }
            if (cover == null || !cover.isFile()) {
                String preferred = layered ? assets.optString("subject") : assets.optString("image");
                if (!preferred.isEmpty()) cover = new File(staging, preferred.substring(2));
            }
            if (cover == null || !cover.isFile()) throw new IOException("闪卡包缺少封面或图片");
            validateImage(cover);
            String coverName = "cover." + imageExtension(cover);
            copyFile(cover, new File(staging, coverName));
            if (!layered) {
                // Incomplete layer sets are honestly presented as a single-image card.
                String imageName = "image." + imageExtension(cover);
                copyFile(cover, new File(staging, "assets/" + imageName));
                assets = new JSONObject().put("image", "./assets/" + imageName);
                clean.put("sourceMode", "image");
            }
            clean.put("assets", assets);
            writeText(new File(staging, "card-config.json"), clean.toString(2));
            writeText(new File(staging, "card.json"), metadata(id, clean, layered ? "layered" : "image", coverName).toString(2));
            commit(staging, id);
            return true;
        } finally { deleteTree(staging); }
    }

    private static JSONObject cleanConfig(JSONObject config, JSONObject info) throws Exception {
        JSONObject clean = new JSONObject();
        for (String key : new String[]{"title", "subtitle", "description", "edition", "collection", "technique", "tagline"}) {
            int length = key.equals("description") ? 1200 : key.equals("title") ? 80 : 120;
            clean.put(key, cleanText(config.optString(key, info.optString(key)), length, key.equals("title") ? "导入闪卡" : ""));
        }
        String sourceMode = config.optString("sourceMode", "");
        if (sourceMode.equals("relief") || sourceMode.equals("reference")) clean.put("sourceMode", sourceMode);
        JSONArray fit = config.optJSONArray("artworkFit");
        if (fit != null && fit.length() == 2) clean.put("artworkFit", new JSONArray().put(number(fit.optDouble(0), 0.1, 3, 1)).put(number(fit.optDouble(1), 0.1, 3, 1)));
        JSONObject safe = config.optJSONObject("safeArea");
        if (safe != null) {
            JSONObject output = new JSONObject().put("scale", number(safe.optDouble("scale"), 0.1, 2, 1));
            JSONArray offset = safe.optJSONArray("offset");
            if (offset != null && offset.length() == 2) output.put("offset", new JSONArray().put(number(offset.optDouble(0), -1, 1, 0)).put(number(offset.optDouble(1), -1, 1, 0)));
            clean.put("safeArea", output);
        }
        JSONObject appearance = config.optJSONObject("appearance");
        if (appearance != null) {
            JSONObject output = new JSONObject();
            String background = appearance.optString("background");
            if (background.matches("#[0-9a-fA-F]{6}")) output.put("background", background);
            String finish = appearance.optString("finish", "pearl");
            output.put("finish", Arrays.asList("pearl", "silver", "gold", "original").contains(finish) ? finish : "pearl");
            clean.put("appearance", output);
        }
        JSONObject parameters = config.optJSONObject("parameters");
        if (parameters != null) {
            JSONObject output = new JSONObject();
            output.put("foil", number(parameters.optDouble("foil"), 0, 2, 0.65));
            output.put("subjectScale", number(parameters.optDouble("subjectScale"), 0.4, 1.8, 1));
            output.put("subjectDepth", number(parameters.optDouble("subjectDepth"), -2, 2, 0.07));
            output.put("backgroundDepth", number(parameters.optDouble("backgroundDepth"), -2, 2, -0.05));
            output.put("effectsDepth", number(parameters.optDouble("effectsDepth"), -2, 2, 0.1));
            clean.put("parameters", output);
        }
        return clean;
    }

    private static JSONObject metadata(String id, JSONObject config, String kind, String coverName) throws Exception {
        JSONObject card = new JSONObject().put("id", id).put("title", config.optString("title", "导入闪卡"))
                .put("subtitle", config.optString("subtitle")).put("description", config.optString("description"))
                .put("edition", config.optString("edition")).put("kind", kind).put("source", "imported")
                .put("cover", STORAGE + id + "/" + coverName)
                .put("configUrl", STORAGE + id + "/card-config.json");
        return card;
    }

    private synchronized void commit(File staging, String id) throws IOException {
        File target = new File(cardRoot, id);
        if (target.exists()) throw new IOException("卡片编号已存在");
        // Cache and private files normally share a filesystem; fallback copies data before publishing.
        File unpublished = new File(cardRoot, ".pending-" + id);
        try {
            copyTree(staging, unpublished);
            if (!unpublished.renameTo(target)) throw new IOException("无法保存闪卡");
        } finally { deleteTree(unpublished); }
    }

    synchronized void savePendingExportState(String stateJson) throws IOException {
        if (!validStateJsonSize(stateJson)) throw new IOException("备份参数无效或过大");
        AtomicFile file = pendingExportFile();
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(stateJson.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException error) { file.failWrite(stream); throw error; }
    }

    synchronized String readPendingExportState() throws IOException {
        AtomicFile file = pendingExportFile();
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, MAX_STATE_BYTES);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.FileNotFoundException missing) { throw new IOException("备份参数已失效，请返回重新备份"); }
    }

    synchronized void clearPendingExportState() { pendingExportFile().delete(); }
    private AtomicFile pendingExportFile() { return new AtomicFile(new File(context.getFilesDir(), "pending-export-state.json")); }

    synchronized void exportBackup(OutputStream output, String stateJson) throws Exception {
        if (!validStateJsonSize(stateJson)) throw new IOException("备份参数无效或过大");
        byte[] stateBytes = stateJson.getBytes(StandardCharsets.UTF_8);
        BackupBudget budget = new BackupBudget(stateBytes.length);
        File[] cards = cardRoot.listFiles(File::isDirectory);
        if (cards == null) throw new IOException("无法读取卡片目录");
        for (File dir : cards) if (validId(dir.getName())) countBackupFiles(dir, budget);
        // Generate and verify the complete archive before touching the destination stream.
        // Its real compressed size, not an estimate, must fit the importer's 512 MiB limit.
        File temporary = File.createTempFile("backup-", ".zip", context.getCacheDir());
        try {
            try (FileOutputStream fileOutput = new FileOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(new LimitedOutputStream(fileOutput, MAX_ARCHIVE), StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("library-state.json"));
                zip.write(stateBytes);
                zip.closeEntry();
                for (File dir : cards) if (validId(dir.getName())) zipDirectory(zip, dir, "cards/" + dir.getName() + "/");
            }
            try (InputStream input = new FileInputStream(temporary)) { copyLimited(input, output, MAX_ARCHIVE); }
        } finally { temporary.delete(); }
    }

    private static void countBackupFiles(File directory, BackupBudget budget) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("无法读取卡片目录");
        for (File file : files) {
            if (file.isDirectory()) countBackupFiles(file, budget);
            else if (allowedDataFile(file.getName())) budget.addEntry(file.length());
        }
    }

    static final class BackupBudget {
        private int entries;
        private long expanded;
        BackupBudget(long stateBytes) throws IOException { addEntry(stateBytes); }
        void addEntry(long bytes) throws IOException {
            if (bytes < 0 || bytes > MAX_ENTRY) throw new IOException("备份单文件超过 96 MB，请减少导入卡片");
            if (++entries > 1024) throw new IOException("备份文件数量超过 1024，请减少导入卡片");
            expanded += bytes;
            if (expanded > MAX_EXPANDED) throw new IOException("备份展开大小超过 768 MB，请减少导入卡片");
        }
    }

    static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        private void reserve(int bytes) throws IOException {
            if (bytes > limit - written) throw new IOException("备份超过 512 MB，请减少导入卡片");
            written += bytes;
        }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { reserve(length); out.write(bytes, offset, length); }
    }

    private static void unpackDataZip(File archive, File root) throws Exception {
        unpackDataZip(archive, root, null);
    }

    /** A collection shares one actual-byte limit across outer copies and every inner package. */
    static final class SharedImportBudget {
        private long expanded;
        void reserve(long bytes) throws IOException {
            if (bytes < 0 || bytes > MAX_EXPANDED - expanded) throw new IOException("合集实际展开大小超过 768 MB");
            expanded += bytes;
        }
    }

    private static void unpackDataZip(File archive, File root, SharedImportBudget budget) throws Exception {
        long expanded = 0;
        int entries = 0;
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > 1024) throw new IOException("闪卡包文件数量超过 1024");
                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (!safeRelativePath(name) || !seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("闪卡包包含无效、重复或越界路径");
                File target = new File(root, name).getCanonicalFile();
                if (!target.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("闪卡包路径越界");
                if (entry.isDirectory()) { requireDirectory(target); continue; }
                if (!allowedDataFile(name)) throw new IOException("闪卡包只接受 JSON、PNG、JPEG、WebP 和 GLB 数据");
                if (entry.getSize() > MAX_ENTRY) throw new IOException("闪卡包单文件超过 96 MB");
                requireDirectory(target.getParentFile());
                long size = 0;
                try (OutputStream file = new FileOutputStream(target)) {
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        size += read; expanded += read;
                        if (size > MAX_ENTRY || expanded > MAX_EXPANDED) throw new IOException("闪卡包解压大小超限");
                        if (budget != null) budget.reserve(read);
                        file.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    static boolean safeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 240 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0
                || path.indexOf('%') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    static boolean allowedDataFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".glb");
    }

    static boolean validStateJsonSize(String stateJson) {
        return stateJson != null && stateJson.getBytes(StandardCharsets.UTF_8).length <= MAX_STATE_BYTES;
    }

    private static File safeAsset(File root, String path, boolean model) throws IOException {
        if (path.startsWith("./")) path = path.substring(2);
        if (!safeRelativePath(path)) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (model ? !lower.endsWith(".glb") : !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"))) return null;
        File file = new File(root, path).getCanonicalFile();
        return file.getPath().startsWith(root.getCanonicalPath() + File.separator) ? file : null;
    }

    private static void findCardRoots(File folder, List<File> roots, int depth) throws IOException {
        if (depth > 4) return;
        if (new File(folder, "card.json").isFile() || new File(folder, "card-config.json").isFile()) { roots.add(folder); return; }
        File[] children = folder.listFiles(File::isDirectory);
        if (children != null) for (File child : children) findCardRoots(child, roots, depth + 1);
    }

    private static void validateImage(File image) throws IOException {
        if (image.length() > MAX_ENTRY) throw new IOException("图片超过 96 MB");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getPath(), options);
        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 8192 || options.outHeight > 8192
                || (long) options.outWidth * options.outHeight > 32_000_000) throw new IOException("图片无效，或超过 8192 像素 / 3200 万像素限制");
        if (!"image/png".equals(options.outMimeType) && !"image/jpeg".equals(options.outMimeType)
                && !"image/webp".equals(options.outMimeType)) throw new IOException("仅支持 PNG、JPEG 和 WebP 图片");
    }
    private static String imageExtension(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if ("image/jpeg".equals(options.outMimeType)) return "jpg";
        if ("image/webp".equals(options.outMimeType)) return "webp";
        return "png";
    }
    private static void validateGlb(File file) throws IOException {
        if (file.length() < 12 || file.length() > MAX_ENTRY) throw new IOException("GLB 模型大小无效");
        byte[] header = new byte[12];
        try (InputStream input = new FileInputStream(file)) {
            if (input.read(header) != 12 || header[0] != 'g' || header[1] != 'l' || header[2] != 'T' || header[3] != 'F'
                    || header[4] != 2 || header[5] != 0 || header[6] != 0 || header[7] != 0) throw new IOException("仅支持 GLB 2.0 模型");
        }
        long declared = (header[8] & 255L) | (header[9] & 255L) << 8 | (header[10] & 255L) << 16 | (header[11] & 255L) << 24;
        if (declared != file.length()) throw new IOException("GLB 长度无效");
    }
    private static boolean isZip(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return input.read() == 'P' && input.read() == 'K'; }
    }
    private static String newId() { return "import_" + UUID.randomUUID().toString().replace("-", ""); }
    private static boolean validId(String id) { return id != null && id.matches("[a-z0-9_-]{1,80}"); }
    private static double number(double value, double min, double max, double fallback) { return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback; }
    private static String cleanText(String value, int max, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return cleaned.isEmpty() ? fallback : cleaned.substring(0, Math.min(cleaned.length(), max));
    }
    static String safeError(Exception e) {
        if (e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("/")) return e.getMessage();
        return "文件内容无效或无法读取";
    }
    private static String relative(File root, File file) { return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); }
    private static void requireDirectory(File folder) throws IOException { if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建卡片目录"); }
    private static void copyFile(File source, File target) throws IOException {
        requireDirectory(target.getParentFile());
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) { copyLimited(input, output, MAX_ENTRY); }
    }
    private static long copyLimited(InputStream input, OutputStream output, long max) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0;
        for (int read; (read = input.read(buffer)) != -1;) { total += read; if (total > max) throw new IOException("文件超过大小限制"); output.write(buffer, 0, read); }
        return total;
    }
    private static String readText(File file, int maxBytes) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, maxBytes); return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void writeText(File file, String value) throws IOException { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    private static void copyTree(File source, File target) throws IOException {
        requireDirectory(target);
        File[] files = source.listFiles(); if (files == null) throw new IOException("无法读取卡片目录");
        for (File file : files) { File destination = new File(target, file.getName()); if (file.isDirectory()) copyTree(file, destination); else copyFile(file, destination); }
    }
    private static boolean deleteTree(File file) {
        if (!file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return file.delete();
    }
    private static void zipDirectory(ZipOutputStream zip, File directory, String prefix) throws IOException {
        File[] files = directory.listFiles(); if (files == null) throw new IOException("无法读取卡片目录");
        for (File file : files) {
            if (file.isDirectory()) zipDirectory(zip, file, prefix + file.getName() + "/");
            else if (allowedDataFile(file.getName())) {
                zip.putNextEntry(new ZipEntry(prefix + file.getName()));
                try (InputStream input = new FileInputStream(file)) { copyLimited(input, zip, MAX_ENTRY); }
                zip.closeEntry();
            }
        }
    }
    static final class ImportResult {
        final int count; final JSONObject state; final List<String> addedIds, activeIds;
        ImportResult(int count, JSONObject state) { this(count, state, java.util.Collections.emptyList()); }
        ImportResult(int count, JSONObject state, List<String> addedIds) {
            this(count, state, addedIds, addedIds);
        }
        ImportResult(int count, JSONObject state, List<String> addedIds, List<String> activeIds) {
            this.count = count; this.state = state; this.addedIds = new ArrayList<>(addedIds);
            this.activeIds = new ArrayList<>(activeIds);
        }
    }
}
