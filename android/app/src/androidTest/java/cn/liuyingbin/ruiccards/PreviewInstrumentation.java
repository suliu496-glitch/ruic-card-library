package cn.liuyingbin.ruiccards;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exercises the actual disposable importer, Android image decoder, commit ledger and cleanup. */
final class PreviewInstrumentation {
    private int assertions;
    static int run(Context context) throws Exception { return new PreviewInstrumentation().verify(context); }

    private int verify(Context context) throws Exception {
        File fixtures = new File(context.getCacheDir(), "qa-preview-" + System.nanoTime());
        check(fixtures.mkdir(), "Preview fixture directory created");
        CardStore library = new CardStore(context);
        byte[] initial = hashTree(library.cardRoot);
        List<String> added = new ArrayList<>();
        List<PreviewSession> sessions = new ArrayList<>();
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            File first = card(fixtures, "first.ruic", "临时闪卡一"), second = card(fixtures, "second.ruic", "临时闪卡二");
            DownloadsScanner scanner = new DownloadsScanner(fixtures, context.getCacheDir(),
                    new File(fixtures, "ledger.json"), library, () -> false);
            PreviewSession preview = PreviewSession.openFile(context, first, first.getName()); sessions.add(preview);
            JSONArray cards = preview.cards();
            check(cards.length() == 1, "Single ruic previews without publishing");
            JSONObject metadata = cards.getJSONObject(0);
            check("preview".equals(metadata.getString("source")), "Preview metadata identifies temporary source");
            String previewPrefix = "https://appassets.androidplatform.net/preview/" + preview.id + "/";
            check(metadata.getString("cover").startsWith(previewPrefix), "Cover uses isolated same-origin preview route");
            check(metadata.getString("configUrl").startsWith(previewPrefix), "Config uses isolated same-origin preview route");
            String cardId = metadata.getString("id");
            try (java.io.InputStream input = preview.openAsset(cardId + "/card-config.json")) {
                check(input.read() == '{', "Sanitized config can be read from temporary root");
            }
            rejected(() -> preview.openAsset("../source.ruic"), "Traversal cannot read preview snapshot");
            rejected(() -> preview.openAsset(cardId + "/assets/app.js"), "Preview cannot serve executable resources");
            rejected(() -> preview.openAsset("other/cover.png"), "Other card roots cannot be read");
            check(Arrays.equals(initial, hashTree(library.cardRoot)), "Opening and reading preview leave real card library byte-for-byte unchanged");
            JSONObject committed = preview.commit(scanner);
            collectIds(committed, added);
            check(committed.getInt("imported") == 1, "Only explicit save adds a real card without broad storage permission");
            check(!Arrays.equals(initial, hashTree(library.cardRoot)), "Save changes the real card library");
            check(preview.commit(scanner).getInt("imported") == 0, "Repeated preview save uses shared content dedupe");
            check(library.containsCard(added.get(0)) && !library.containsCard(cardId), "Save assigns a formal ID distinct from temporary preview ID");
            preview.close();
            check(!preview.root.exists(), "Close removes source snapshot and temporary card data");
            rejected(preview::cards, "Closed session cannot be reused");

            File bundle = new File(fixtures, "collection.zip");
            writeZip(bundle, new String[]{"闪卡合集/one.ruic", "闪卡合集/two.ruic", "README.txt"},
                    new byte[][]{Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()), "说明".getBytes(StandardCharsets.UTF_8)});
            byte[] beforeBundle = hashTree(library.cardRoot);
            PreviewSession collection = PreviewSession.openFile(context, bundle, bundle.getName()); sessions.add(collection);
            JSONArray collectionCards = collection.cards();
            check(collectionCards.length() == 2, "Outer collection previews both inner ruic cards");
            check("临时闪卡一".equals(collectionCards.getJSONObject(0).getString("title"))
                    && "临时闪卡二".equals(collectionCards.getJSONObject(1).getString("title")), "Collection card order remains stable for previous / next navigation");
            check(Arrays.equals(beforeBundle, hashTree(library.cardRoot)), "Collection preview does not modify formal cards");
            Future<JSONObject> saved = io.submit(() -> collection.commit(scanner));
            Future<?> closed = io.submit(collection::close);
            JSONObject savedResult = saved.get(); collectIds(savedResult, added); closed.get();
            check(savedResult.getInt("imported") == 1 && savedResult.getInt("skipped") == 1, "Collection save shares dedupe with individual package save");
            check(!collection.root.exists(), "Immediate queued close runs after save completes");
            check(library.containsCard(added.get(1)), "Saved card survives preview cleanup");

            DownloadsScanner tracked = new DownloadsScanner(fixtures, context.getCacheDir(),
                    new File(fixtures, "ledger.json"), library, () -> true);
            PreviewSession trackedSingle = trackedPreview(context, tracked, first); sessions.add(trackedSingle);
            check(trackedSingle.commit(tracked).getInt("skipped") == 1, "Saving an existing preview returns its active dedupe ID");
            trackedSingle.close();
            check(scannedFile(tracked, first.getName()).getBoolean("imported"), "Preview save marks the unchanged original download as imported");
            PreviewSession trackedBundle = trackedPreview(context, tracked, bundle); sessions.add(trackedBundle);
            check(trackedBundle.commit(tracked).getInt("skipped") == 2, "Collection source tracking includes all already imported children");
            trackedBundle.close();
            check(scannedFile(tracked, bundle.getName()).getBoolean("imported"), "Saved preview collection is marked imported after returning to scan");
            library.deleteCard(added.get(0));
            JSONObject missingCardBundle = scannedFile(tracked, bundle.getName());
            check(!missingCardBundle.getBoolean("imported"), "Deleting one child clears the collection imported marker");
            Set<String> beforeReimport = cardIds(library);
            JSONObject reimport = tracked.importSelected(new JSONArray().put(missingCardBundle.getString("id")).toString(), progress -> { });
            collectNewIds(library, beforeReimport, added);
            check(reimport.getInt("imported") == 1, "Deleted preview child can be reimported while the other child stays deduplicated");
            check(scannedFile(tracked, bundle.getName()).getBoolean("imported"), "Collection marker returns after deleted child is reimported");

            File changed = card(fixtures, "changed.ruic", "源文件修改前");
            PreviewSession changedPreview = trackedPreview(context, tracked, changed); sessions.add(changedPreview);
            long previousModified = changed.lastModified();
            card(fixtures, changed.getName(), "源文件修改后"); changed.setLastModified(previousModified + 2000);
            JSONObject savedOldSnapshot = changedPreview.commit(tracked); collectIds(savedOldSnapshot, added);
            check(savedOldSnapshot.getInt("imported") == 1, "The validated preview snapshot can still be saved after the source changes");
            check(!scannedFile(tracked, changed.getName()).getBoolean("imported"), "Changed source is never marked imported from an older preview snapshot");
            changedPreview.close();

            File fresh = card(fixtures, "transaction-child.ruic", "保存事务中的新卡");
            File transactionBundle = new File(fixtures, "transaction-collection.zip");
            writeZip(transactionBundle, new String[]{"existing.ruic", "fresh.ruic"},
                    new byte[][]{Files.readAllBytes(second.toPath()), Files.readAllBytes(fresh.toPath())});
            PreviewSession transaction = trackedPreview(context, tracked, transactionBundle); sessions.add(transaction);
            byte[] beforeTransaction = hashTree(library.cardRoot);
            Set<String> beforeTransactionIds = cardIds(library);
            // A broken outer source snapshot forces the final source-record transaction to fail,
            // after the real per-child import and ledger have succeeded.
            try (FileOutputStream corrupt = new FileOutputStream(new File(transaction.root, "source.ruic"), true)) { corrupt.write(0); }
            JSONObject recordFailure = transaction.commit(tracked);
            collectNewIds(library, beforeTransactionIds, added);
            check(!recordFailure.getBoolean("ok") && recordFailure.getInt("imported") == 0
                    && recordFailure.getJSONArray("ids").length() == 0, "Failed source recording reports no newly saved cards");
            check(recordFailure.getString("message").contains("撤回"), "Record failure clearly explains that newly added cards were rolled back");
            check(Arrays.equals(beforeTransaction, hashTree(library.cardRoot)), "Source-record failure rolls back only new cards and preserves earlier deduplicated cards");
            transaction.close();
            PreviewSession retry = trackedPreview(context, tracked, transactionBundle); sessions.add(retry);
            JSONObject retryResult = retry.commit(tracked); collectIds(retryResult, added); retry.close();
            check(retryResult.getInt("imported") == 1 && retryResult.getInt("skipped") == 1, "Retry after rolled-back source recording succeeds without deleting or duplicating old cards");
            check(scannedFile(tracked, transactionBundle.getName()).getBoolean("imported"), "Retry publishes a complete source marker");

            for (String invalid : new String[]{"javascript:alert(1)", "https://example.com/card.ruic", "file:///data/local/tmp/private.ruic", "content:///missing.ruic"})
                rejected(() -> PreviewSession.validateUri(context, Uri.parse(invalid)), "Dangerous or unrelated URI rejected: " + invalid);
            Intent send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.provider/document/card.ruic"));
            check(PreviewSession.intentUri(send).getScheme().equals("content"), "Sharing accepts a document URI without converting it into a filesystem path");
            check(resolves(context, new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://example.provider/document/42"), "application/vnd.ruic.card")), "Manifest associates granted content card documents");
            check(resolves(context, new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/Download/card.ruic"), "application/octet-stream")), "Manifest associates typed file ruic paths");
            check(resolves(context, new Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///storage/emulated/0/Download/card.ruic"))), "Manifest associates file ruic paths without MIME metadata");
            check(resolves(context, new Intent(Intent.ACTION_SEND).setType("application/zip")), "Manifest supports sharing ZIP card collections");
            check(!resolves(context, new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/card.ruic"), "application/vnd.ruic.card")), "Manifest does not associate web links");
            rejected(() -> PreviewSession.intentUri(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "file:///card.ruic")), "Text-only share cannot become a file open");
            rejected(() -> PreviewSession.intentUri(new Intent(Intent.ACTION_SEND_MULTIPLE)), "Unexpected intent action rejected");
            int beforeFailures = previewRootCount(context);
            File backup = new File(fixtures, "backup.zip");
            writeZip(backup, new String[]{"library-state.json"}, new byte[][]{"{}".getBytes(StandardCharsets.UTF_8)});
            rejected(() -> PreviewSession.openFile(context, backup, backup.getName()), "Application backup cannot enter temporary preview or restore app state");
            File ordinary = new File(fixtures, "ordinary.zip");
            writeZip(ordinary, new String[]{"readme.txt"}, new byte[][]{{1}});
            rejected(() -> PreviewSession.openFile(context, ordinary, ordinary.getName()), "Ordinary ZIP is rejected");
            File malicious = new File(fixtures, "malicious.ruic");
            writeZip(malicious, new String[]{"../escape.png"}, new byte[][]{{1}});
            rejected(() -> PreviewSession.openFile(context, malicious, malicious.getName()), "Archive traversal is rejected before data reaches viewer");
            rejected(() -> PreviewSession.openFile(context, first, "unrelated.html"), "Unrelated filename rejected even for explicit file opens");
            String[] manyNames = new String[51]; byte[][] manyData = new byte[51][];
            for (int i = 0; i < 51; i++) { manyNames[i] = i + ".ruic"; manyData[i] = Files.readAllBytes(first.toPath()); }
            File tooMany = new File(fixtures, "too-many.zip"); writeZip(tooMany, manyNames, manyData);
            rejected(() -> PreviewSession.openFile(context, tooMany, tooMany.getName()), "Preview collection has a 50-card bound");
            check(previewRootCount(context) == beforeFailures, "Every failed preview removes its disposable session directory");
            File abandoned = new File(context.getCacheDir(), "preview-session-abandoned-test"); abandoned.mkdir();
            PreviewSession.cleanupAbandoned(context);
            check(!abandoned.exists(), "A later launch removes abandoned preview cache");
        } finally {
            io.shutdown();
            for (PreviewSession session : sessions) session.close();
            for (String id : added) library.deleteCard(id);
            deleteTree(fixtures);
        }
        check(Arrays.equals(initial, hashTree(library.cardRoot)), "Test cleanup restores the real library exactly");
        return assertions;
    }

    private interface Checked { void run() throws Exception; }
    private void rejected(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, message);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private static void collectIds(JSONObject result, List<String> added) throws Exception {
        JSONArray ids = result.getJSONArray("ids"); for (int i = 0; i < ids.length(); i++) added.add(ids.getString(i));
    }
    private static Set<String> cardIds(CardStore library) throws Exception {
        Set<String> ids = new HashSet<>(); JSONArray cards = library.listCards();
        for (int i = 0; i < cards.length(); i++) ids.add(cards.getJSONObject(i).getString("id")); return ids;
    }
    private static void collectNewIds(CardStore library, Set<String> before, List<String> added) throws Exception {
        Set<String> current = cardIds(library); current.removeAll(before); added.addAll(current);
    }
    private static JSONObject scannedFile(DownloadsScanner scanner, String name) throws Exception {
        JSONArray files = scanner.scan().getJSONArray("files");
        for (int i = 0; i < files.length(); i++) if (name.equals(files.getJSONObject(i).getString("name"))) return files.getJSONObject(i);
        throw new AssertionError("Download missing from scan: " + name);
    }
    private static PreviewSession trackedPreview(Context context, DownloadsScanner scanner, File source) throws Exception {
        // Production previews resolve a scanned opaque ID first. This also avoids Android's
        // /data/user/0 -> /data/data fixture alias without weakening the no-symlink source guard.
        JSONObject candidate = scannedFile(scanner, source.getName());
        return PreviewSession.openFile(context, scanner.resolveCurrentFile(candidate.getString("id")), source.getName());
    }
    private static File card(File fixtures, String filename, String title) throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        Bitmap bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFF435A76);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, image); bitmap.recycle();
        JSONObject config = new JSONObject().put("title", title).put("assets", new JSONObject().put("image", "./assets/image.png"));
        File file = new File(fixtures, filename);
        writeZip(file, new String[]{"card-config.json", "cover.png", "assets/image.png"},
                new byte[][]{config.toString().getBytes(StandardCharsets.UTF_8), image.toByteArray(), image.toByteArray()});
        return file;
    }
    private static void writeZip(File file, String[] paths, byte[][] content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < paths.length; i++) { zip.putNextEntry(new ZipEntry(paths[i])); zip.write(content[i]); zip.closeEntry(); }
        }
    }
    private static byte[] hashTree(File root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); hash(root, root, digest); return digest.digest();
    }
    private static void hash(File root, File file, MessageDigest digest) throws Exception {
        File[] children = file.listFiles();
        if (children != null) {
            Arrays.sort(children, java.util.Comparator.comparing(File::getName));
            for (File child : children) hash(root, child, digest);
        } else {
            digest.update(root.toPath().relativize(file.toPath()).toString().getBytes(StandardCharsets.UTF_8));
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536]; for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
        }
    }
    private static int previewRootCount(Context context) {
        File[] roots = context.getCacheDir().listFiles(file -> file.getName().startsWith("preview-session-")); return roots == null ? 0 : roots.length;
    }
    private static boolean resolves(Context context, Intent intent) {
        intent.setPackage(context.getPackageName());
        return !context.getPackageManager().queryIntentActivities(intent, 0).isEmpty();
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); file.delete();
    }
}
