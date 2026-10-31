package cn.liuyingbin.ruiccards;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Disposable, validated card data. Neither URI input nor preview settings enter the real library. */
final class PreviewSession implements AutoCloseable {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String STORAGE = ORIGIN + "/storage/";
    private static final Set<String> LIVE_ROOTS = ConcurrentHashMap.newKeySet();
    final String id = UUID.randomUUID().toString().replace("-", "");
    final File root;
    private final CardStore temporaryStore;
    private final List<PackageFile> packages = new ArrayList<>();
    private final List<String> cardOrder = new ArrayList<>();
    private File originalSource, sourceSnapshot;
    private long sourceSize, sourceModified;
    private volatile boolean closed;

    private PreviewSession(Context context) throws IOException {
        root = new File(context.getCacheDir(), "preview-session-" + id);
        if (!root.mkdir()) throw new IOException("无法创建临时预览");
        LIVE_ROOTS.add(root.getAbsolutePath());
        temporaryStore = new CardStore(context, new File(root, "cards"));
    }

    static PreviewSession openFile(Context context, File source, String name) throws Exception {
        long size = source.length(), modified = source.lastModified();
        try (InputStream input = Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            PreviewSession session = open(context, input, checkedName(name));
            if (source.length() != size || source.lastModified() != modified || session.sourceSnapshot.length() != size) {
                session.close();
                throw new IOException("下载文件已改变，请重新打开预览");
            }
            session.originalSource = source.getAbsoluteFile();
            session.sourceSize = size;
            session.sourceModified = modified;
            return session;
        }
    }

    static PreviewSession openUri(Context context, Uri uri, String mime) throws Exception {
        validateUri(context, uri);
        String name = uri.getLastPathSegment();
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            } catch (SecurityException denied) {
                throw new IOException("文件没有读取授权，请从文件管理器重新分享或打开");
            } catch (IllegalArgumentException | UnsupportedOperationException unavailableMetadata) { name = null; }
        }
        if (name == null || name.isEmpty()) {
            if ("application/vnd.ruic.card".equals(mime)) name = "闪卡.ruic";
            else if ("application/zip".equals(mime)) name = "闪卡.zip";
        }
        name = checkedName(name);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取文件，请通过文件管理器分享或打开");
            return open(context, input, name);
        } catch (SecurityException denied) {
            throw new IOException("文件没有读取授权，请从文件管理器重新分享或打开");
        }
    }

    static Uri intentUri(Intent intent) throws IOException {
        if (intent == null) throw new IOException("没有收到闪卡文件");
        Uri uri;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            uri = stream instanceof Uri ? (Uri) stream : null;
        } else throw new IOException("不支持此文件打开方式");
        if (uri == null) throw new IOException("没有收到闪卡文件");
        return uri;
    }

    static void validateUri(Context context, Uri uri) throws IOException {
        if (uri == null || uri.toString().length() > 8192 || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IOException("文件地址无效");
        if ("content".equals(uri.getScheme())) {
            if (uri.getAuthority() == null || uri.getAuthority().isEmpty()) throw new IOException("文件地址无效");
            return; // ContentResolver enforces the document's URI grant, independently of all-files access.
        }
        if (!"file".equals(uri.getScheme()) || uri.getAuthority() != null && !uri.getAuthority().isEmpty()
                || uri.getQuery() != null || uri.getPath() == null) throw new IOException("只支持已授权的本地 ruic 或 ZIP 文件");
        File source = new File(uri.getPath()), canonical = source.getCanonicalFile();
        if (!canonical.toPath().equals(source.toPath().toAbsolutePath().normalize())) throw new IOException("不支持链接文件");
        List<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        if (Build.VERSION.SDK_INT >= 30) {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager != null) for (StorageVolume volume : manager.getStorageVolumes())
                if (volume.getDirectory() != null) volumes.add(volume.getDirectory());
        }
        boolean shared = false;
        for (File volume : volumes) if (canonical.toPath().startsWith(volume.getCanonicalFile().toPath())) shared = true;
        if (!shared || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("文件位置无效，请从文件管理器分享为文档");
        for (File part = source; part != null; part = part.getParentFile())
            if (Files.isSymbolicLink(part.toPath())) throw new IOException("不支持链接文件");
    }

    private static String checkedName(String name) throws IOException {
        if (name == null || name.length() > 240) throw new IOException("文件名无效");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ruic") && !lower.endsWith(".zip")) throw new IOException("仅支持 ruic 闪卡包或闪卡合集 ZIP");
        return name.replaceAll("[\\p{Cntrl}]", "");
    }

    private static PreviewSession open(Context context, InputStream input, String name) throws Exception {
        PreviewSession session = new PreviewSession(context);
        try {
            File snapshot = new File(session.root, "source.ruic");
            session.sourceSnapshot = snapshot;
            copy(input, snapshot, CardStore.MAX_ARCHIVE, null);
            DownloadsScanner.ArchiveInfo info = DownloadsScanner.inspect(snapshot);
            if (info == null) throw new IOException("这不是闪卡包；应用备份请使用导入功能");
            if (info.packages.size() > 50) throw new IOException("一次最多预览 50 张闪卡");
            CardStore.SharedImportBudget budget = new CardStore.SharedImportBudget();
            if (info.packages.isEmpty()) session.add(snapshot, name, budget);
            else try (ZipFile zip = new ZipFile(snapshot, StandardCharsets.UTF_8)) {
                for (String entryName : info.packages) {
                    ZipEntry entry = zip.getEntry(entryName);
                    File nested = new File(session.root, "card-" + session.packages.size() + ".ruic");
                    try (InputStream nestedInput = zip.getInputStream(entry)) {
                        copy(nestedInput, nested, CardStore.MAX_ENTRY, budget);
                    }
                    if (nested.length() != entry.getSize()) throw new IOException("合集内闪卡包长度无效");
                    DownloadsScanner.ArchiveInfo child = DownloadsScanner.inspect(nested);
                    if (child == null || !child.packages.isEmpty()) throw new IOException("合集内只支持单张 ruic 闪卡包");
                    session.add(nested, entryName, budget);
                }
            }
            if (session.cardOrder.isEmpty()) throw new IOException("文件中没有可预览的闪卡");
            return session;
        } catch (Exception error) { session.close(); throw error; }
    }

    private void add(File source, String name, CardStore.SharedImportBudget budget) throws Exception {
        CardStore.ImportResult imported = temporaryStore.importFile(source, name, budget);
        if (imported.state != null || imported.count != 1) throw new IOException("预览只接受单张闪卡包");
        packages.add(new PackageFile(source, name));
        cardOrder.addAll(imported.addedIds);
    }

    JSONArray cards() throws Exception {
        ensureOpen();
        JSONArray available = temporaryStore.listCards(), output = new JSONArray();
        for (String cardId : cardOrder) for (int i = 0; i < available.length(); i++) {
            JSONObject card = available.getJSONObject(i);
            if (!cardId.equals(card.getString("id"))) continue;
            card = new JSONObject(card.toString());
            for (String key : new String[]{"cover", "configUrl"}) {
                String original = card.getString(key);
                if (!original.startsWith(STORAGE + cardId + "/")) throw new IOException("预览资源地址无效");
                card.put(key, ORIGIN + "/preview/" + id + "/" + original.substring(STORAGE.length()));
            }
            output.put(card.put("source", "preview"));
        }
        return output;
    }

    synchronized InputStream openAsset(String relative) throws IOException {
        ensureOpen();
        if (!CardStore.safeRelativePath(relative) || !CardStore.allowedDataFile(relative)) throw new IOException("预览资源不可用");
        int slash = relative.indexOf('/');
        if (slash < 1 || !cardOrder.contains(relative.substring(0, slash))) throw new IOException("预览资源不可用");
        File file = new File(temporaryStore.cardRoot, relative).getCanonicalFile();
        if (!file.toPath().startsWith(temporaryStore.cardRoot.getCanonicalFile().toPath()) || !file.isFile())
            throw new IOException("预览资源不可用");
        return new FileInputStream(file);
    }

    JSONObject commit(DownloadsScanner scanner) throws Exception {
        ensureOpen();
        int imported = 0, skipped = 0, failed = 0;
        List<String> addedIds = new ArrayList<>();
        Set<String> activeIds = new LinkedHashSet<>();
        String failure = "";
        for (PackageFile file : packages) {
            try {
                CardStore.ImportResult outcome = scanner.importPreviewFile(file.file, file.name);
                imported += outcome.count;
                if (outcome.count == 0) skipped++;
                addedIds.addAll(outcome.addedIds);
                activeIds.addAll(outcome.activeIds);
            } catch (Exception error) { failed++; if (failure.isEmpty()) failure = CardStore.safeError(error); }
        }
        if (failed == 0 && originalSource != null) {
            try {
                scanner.rememberPreviewSource(originalSource, sourceSize, sourceModified, sourceSnapshot,
                        new ArrayList<>(activeIds));
            } catch (Exception recordFailure) {
                // Previously deduplicated cards belong to earlier saves and must never be rolled back.
                scanner.rollbackPreviewImports(addedIds);
                String message = "保存下载记录失败，请重试";
                if (!addedIds.isEmpty()) message += "；本次新增卡片已撤回";
                return new JSONObject().put("ok", false).put("message", message).put("sessionId", id)
                        .put("imported", 0).put("skipped", skipped).put("failed", 1).put("ids", new JSONArray());
            }
        }
        String message = "已保存 " + imported + " 张闪卡";
        if (skipped > 0) message += "，" + skipped + " 张已在卡库中";
        if (failed > 0) message += "；" + failed + " 张未保存：" + failure;
        return new JSONObject().put("ok", failed == 0 || imported > 0).put("message", message).put("sessionId", id)
                .put("imported", imported).put("skipped", skipped).put("failed", failed).put("ids", new JSONArray(addedIds));
    }

    private void ensureOpen() throws IOException { if (closed) throw new IOException("预览已关闭，请重新打开闪卡"); }

    @Override public synchronized void close() {
        closed = true;
        deleteTree(root);
        LIVE_ROOTS.remove(root.getAbsolutePath());
    }

    static void cleanupAbandoned(Context context) {
        File[] files = context.getCacheDir().listFiles(file -> file.getName().startsWith("preview-session-"));
        if (files != null) for (File file : files) if (!LIVE_ROOTS.contains(file.getAbsolutePath())) deleteTree(file);
    }

    private static void copy(InputStream input, File target, long limit, CardStore.SharedImportBudget budget) throws IOException {
        long total = 0;
        byte[] buffer = new byte[65536];
        try (FileOutputStream output = new FileOutputStream(target)) {
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > limit) throw new IOException("文件超过大小限制");
                if (budget != null) budget.reserve(read);
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteTree(File file) {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static final class PackageFile {
        final File file; final String name;
        PackageFile(File file, String name) { this.file = file; this.name = name; }
    }
}
