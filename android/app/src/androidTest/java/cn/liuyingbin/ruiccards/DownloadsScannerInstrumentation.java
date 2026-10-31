package cn.liuyingbin.ruiccards;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exercises production discovery and CardStore against real files and Android image decoders. */
final class DownloadsScannerInstrumentation {
    private int assertions;
    private final Context context;
    private final File fixtures, downloads, cache, history;
    private final CardStore store;
    private final boolean[] allowed = {true};
    private DownloadsScanner scanner;

    private DownloadsScannerInstrumentation(Context context) {
        this.context = context;
        fixtures = new File(context.getCacheDir(), "download-qa-" + UUID.randomUUID());
        downloads = new File(fixtures, "Download"); downloads.mkdirs();
        cache = new File(fixtures, "cache"); cache.mkdirs();
        history = new File(fixtures, "history.json");
        store = new CardStore(context, new File(fixtures, "cards"));
        scanner = scanner();
    }

    static int run(Context context) throws Exception {
        DownloadsScannerInstrumentation suite = new DownloadsScannerInstrumentation(context);
        try { suite.exercise(); return suite.assertions; }
        finally { delete(suite.fixtures); }
    }

    private DownloadsScanner scanner() { return new DownloadsScanner(downloads, cache, history, store, () -> allowed[0]); }

    private void exercise() throws Exception {
        byte[] first = card("First"), second = card("Second"), third = card("Third");
        File single = write(new File(downloads, "first.ruic"), first);
        File nestedDirectory = new File(downloads, "browser/subfolder"); nestedDirectory.mkdirs();
        File copy = write(new File(nestedDirectory, "copy.ruic"), first);
        write(new File(downloads, "direct.zip"), first);
        File collection = new File(downloads, "collection.zip");
        zip(collection, new String[]{"cards/first.ruic", "cards/second.ruic", "README.txt", "SHA256SUMS", "ignored.zip"},
                new byte[][]{first, second, bytes("说明"), bytes("hash"), third});
        zip(new File(downloads, "ordinary.zip"), new String[]{"notes.txt"}, new byte[][]{bytes("ordinary")});
        zip(new File(downloads, "backup.zip"), new String[]{"library-state.json", "first.ruic"}, new byte[][]{bytes("{}"), first});
        zip(new File(downloads, "backup.ruic"), new String[]{"library-state.json"}, new byte[][]{bytes("{}")});
        zip(new File(downloads, "traversal.zip"), new String[]{"../first.ruic"}, new byte[][]{first});
        zip(new File(downloads, "bad-path.ruic"), new String[]{"../escape.json"}, new byte[][]{bytes("{}")});
        write(new File(downloads, "broken.ruic"), bytes("incomplete download"));
        File outside = write(new File(fixtures, "outside.ruic"), third);
        Files.createSymbolicLink(new File(downloads, "linked.ruic").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(downloads, "linked-folder").toPath(), fixtures.toPath());

        JSONObject report = scanner.scan();
        check("ready".equals(report.getString("status")), "Real filesystem scan succeeds");
        check(report.getJSONArray("files").length() == 6, "Subdirectories scanned; ordinary ZIP, backup, traversal ZIP and symlinks excluded");
        check(file(report, "collection.zip").getInt("cardCount") == 2, "Collection counts only ruic packages; does not recurse nested ZIP");
        check(!file(report, "first.ruic").getString("id").contains("/"), "Frontend receives opaque tokens");
        check(scanner.resolveCurrentFile(file(report, "first.ruic").getString("id")).equals(single.getCanonicalFile()), "Native preview resolves checked opaque token");
        List<JSONObject> progress = new ArrayList<>();
        JSONObject result = scanner.importSelected(select(report, "collection.zip"), progress::add);
        check(result.getInt("imported") == 2 && result.getInt("failed") == 0, "Outer ZIP imports its two real card packages directly");
        check(progress.get(progress.size() - 1).getInt("completed") == 1 && progress.get(progress.size() - 1).getInt("total") == 1, "Progress counts selected files separately from imported cards");
        check(store.listCards().length() == 2 && titleId("Third") == null, "Nested ZIP is never recursively imported");
        check(collection.isFile() && single.isFile() && copy.isFile(), "Downloaded originals remain present");
        check(java.util.Arrays.equals(Files.readAllBytes(single.toPath()), first), "Downloaded original remains byte-for-byte unchanged");
        check(history.isFile(), "Successful source history is persisted");

        scanner = scanner(); report = scanner.scan();
        check(file(report, "collection.zip").getBoolean("imported"), "Collection imported status survives scanner reconstruction");
        result = scanner.importSelected(select(report, "first.ruic", "copy.ruic", "direct.zip"), ignored -> {});
        check(result.getInt("imported") == 0 && result.getInt("skipped") == 3 && store.listCards().length() == 2,
                "SHA deduplicates collection children, loose files, renamed copies and single-card ZIPs");
        report = scanner.scan();
        check(file(report, "copy.ruic").getBoolean("imported"), "Fingerprint supplies scan status without rehashing files");
        String oldId = titleId("First"); check(store.deleteCard(oldId), "Delete imported test card");
        report = scanner.scan();
        check(!file(report, "collection.zip").getBoolean("imported") && !file(report, "first.ruic").getBoolean("imported"),
                "Deleted IDs invalidate imported status");
        result = scanner.importSelected(select(report, "collection.zip"), ignored -> {});
        check(result.getInt("imported") == 1 && result.getInt("skipped") == 1 && store.listCards().length() == 2,
                "Deleting one card permits reimport while retaining the other collection child");
        check(!oldId.equals(titleId("First")), "Ledger follows the actual newly committed ID");

        File partial = new File(downloads, "partial.zip");
        zip(partial, new String[]{"third.ruic", "broken.ruic"}, new byte[][]{third, bytes("broken")});
        report = scanner.scan();
        result = scanner.importSelected(select(report, "partial.zip"), ignored -> {});
        check(result.getInt("imported") == 1 && result.getInt("failed") == 1, "Valid child survives a different invalid child");
        check(!file(scanner.scan(), "partial.zip").getBoolean("imported"), "Partial collection does not pretend to be fully imported");
        report = scanner.scan();
        result = scanner.importSelected(select(report, "partial.zip"), ignored -> {});
        check(result.getInt("imported") == 0 && result.getInt("skipped") == 1 && result.getInt("failed") == 1,
                "Partial retry skips already successful package by persistent hash");

        File removed = write(new File(downloads, "removed.ruic"), card("Removed"));
        write(new File(downloads, "fourth.ruic"), card("Fourth"));
        report = scanner.scan(); String removedSelection = select(report, "removed.ruic", "fourth.ruic"); removed.delete();
        result = scanner.importSelected(removedSelection, ignored -> {});
        check(result.getInt("failed") == 1 && result.getInt("imported") == 1, "Missing source reports failure and does not interrupt another card");
        report = scanner.scan(); String changedSelection = select(report, "first.ruic");
        single.setLastModified(single.lastModified() + 10000);
        check(scanner.importSelected(changedSelection, ignored -> {}).getInt("failed") == 1, "Token rejects source modified after scan");
        report = scanner.scan();
        result = scanner.importSelected(select(report, "bad-path.ruic", "broken.ruic"), ignored -> {});
        check(result.getInt("failed") == 2 && !new File(fixtures, "escape.json").exists(), "Unsafe and corrupt explicit ruic files fail without escape");
        check(scanner.importSelected(new JSONArray().put(outside.getPath()).toString(), ignored -> {}).getInt("failed") == 1,
                "Raw frontend paths are not accepted as scan IDs");
        JSONArray excessive = new JSONArray(); for (int i = 0; i < 51; i++) excessive.put("token" + i);
        check(scanner.importSelected(excessive.toString(), ignored -> {}).getInt("failed") == 1, "Selection is bounded at 50");
        String staleId = file(report, "first.ruic").getString("id");
        check(staleId.equals(file(scanner.scan(), "first.ruic").getString("id")), "Unchanged source keeps its opaque ID across refresh");
        single.setLastModified(single.lastModified() + 10000);
        check(!staleId.equals(file(scanner.scan(), "first.ruic").getString("id")), "Changed source receives a new opaque ID");
        check(scanner.importSelected(new JSONArray().put(staleId).toString(), ignored -> {}).getInt("failed") == 1, "Changed source invalidates its previous token");

        report = scanner.scan(); String deniedSelection = select(report, "first.ruic"); allowed[0] = false;
        check(scanner.importSelected(deniedSelection, ignored -> {}).getInt("failed") == 1, "Import rechecks revoked storage permission");
        boolean deniedPreview = false;
        try { scanner.resolveCurrentFile(file(report, "first.ruic").getString("id")); }
        catch (java.io.IOException expected) { deniedPreview = true; }
        check(deniedPreview, "Preview resolution rechecks permission too");
        report = scanner.scan();
        check("permission_required".equals(report.getString("status")) && report.getJSONArray("files").length() == 0, "Denied scan exposes no stale results");
        File previewSnapshot = write(new File(cache, "explicit-preview.ruic"), first);
        check(scanner.importPreviewFile(previewSnapshot, "first.ruic").count == 0,
                "Explicit URI-granted preview import shares SHA deduplication without broad storage permission");
        previewSnapshot.delete();
        allowed[0] = true;
        check("ready".equals(scanner.scan().getString("status")), "Permission return allows rescan");

        File duplicate = new File(fixtures, "case.zip");
        zip(duplicate, new String[]{"cover.png", "COVER.PNG"}, new byte[][]{{1}, {2}});
        check(rejected(duplicate), "Case-fold ZIP duplicate names rejected");
        File tooMany = new File(fixtures, "too-many.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tooMany))) {
            for (int i = 0; i < 1025; i++) { zip.putNextEntry(new ZipEntry(i + ".json")); zip.closeEntry(); }
        }
        check(rejected(tooMany), "Central directory entry count is bounded");
        File backup = new File(downloads, "backup.ruic");
        boolean restoreBlocked = false; try { store.importFile(backup, backup.getName()); } catch (Exception expected) { restoreBlocked = true; }
        check(restoreBlocked, "Data-only importer independently rejects application backups");
        CardStore budgetStore = new CardStore(context, new File(fixtures, "budget-cards"));
        CardStore.SharedImportBudget budget = new CardStore.SharedImportBudget();
        long firstExpanded = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(single)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) firstExpanded += entries.nextElement().getSize();
        }
        budget.reserve(CardStore.MAX_EXPANDED - firstExpanded);
        check(budgetStore.importFile(single, "first.ruic", budget).count == 1, "First inner card uses remaining shared actual-byte budget");
        boolean sharedRejected = false;
        try { budgetStore.importFile(single, "first.ruic", budget); } catch (Exception expected) { sharedRejected = true; }
        check(sharedRejected && budgetStore.listCards().length() == 1, "Next inner card cannot reset the collection expansion budget");
        faultLedger();
        previewSourceStatus();
        File[] residue = cache.listFiles();
        check(residue != null && residue.length == 0, "Successful, skipped, failed and partial imports clean private snapshots");
        File[] unpackResidue = context.getCacheDir().listFiles(file -> file.getName().startsWith("unpack-") || file.getName().startsWith("clean-"));
        check(unpackResidue != null && unpackResidue.length == 0, "CardStore cleans its validation and staging directories");
        File deep = downloads;
        for (int i = 0; i < 10; i++) { deep = new File(deep, "depth"); deep.mkdir(); }
        write(new File(deep, "deep.ruic"), first);
        check(scanner.scan().getBoolean("truncated"), "Directory recursion is bounded and reports truncation");
        File many = new File(downloads, "many"); many.mkdir();
        for (int i = 0; i < 110; i++) write(new File(many, i + ".ruic"), first);
        report = scanner.scan();
        check(report.getBoolean("truncated") && report.getJSONArray("files").length() <= 100, "Result count is bounded and reports truncation");
    }

    private void faultLedger() throws Exception {
        byte[] faultCard = card("Fault rollback");
        write(new File(downloads, "fault.ruic"), faultCard);
        JSONObject report = scanner.scan();
        String oldId = titleId("First"), oldDigest = digest(Files.readAllBytes(new File(downloads, "first.ruic").toPath()));
        String faultDigest = digest(faultCard);
        DownloadImportLedger before = new DownloadImportLedger(history, store);
        List<String> oldIds = before.activeIds(oldDigest);
        check(oldIds != null && oldIds.contains(oldId), "Fault test starts with a persisted live old record");
        byte[] ledgerBytes = Files.readAllBytes(history.toPath());
        int cardsBefore = store.listCards().length();
        // A nonempty directory at AtomicFile's staging path produces a real write failure without changing the base file.
        File blockedWrite = new File(history.getPath() + ".new");
        check(blockedWrite.mkdir(), "Create real filesystem ledger write obstruction");
        write(new File(blockedWrite, "blocker"), bytes("do not overwrite"));
        File snapshot = write(new File(cache, "fault-preview.ruic"), faultCard);
        try {
            boolean failed = false;
            try { before.rememberSourceAndFingerprint(faultDigest, oldIds, "fault-fingerprint"); }
            catch (Exception expected) { failed = true; }
            check(failed && before.activeIds(faultDigest) == null && !before.importedFingerprint("fault-fingerprint"),
                    "Failed atomic write does not publish proposed source or fingerprint in memory");
            DownloadImportLedger recreated = new DownloadImportLedger(history, store);
            check(recreated.activeIds(faultDigest) == null && recreated.activeIds(oldDigest).equals(oldIds),
                    "Recreated ledger agrees with memory and retains previous IDs after write failure");
            JSONObject outcome = scanner.importSelected(select(report, "fault.ruic"), ignored -> {});
            check(outcome.getInt("imported") == 0 && outcome.getInt("failed") == 1 && store.listCards().length() == cardsBefore,
                    "Scan import rolls back its committed card and does not count it as imported when history cannot persist");
            boolean previewFailed = false;
            try { scanner.importPreviewFile(snapshot, "fault.ruic"); } catch (Exception expected) { previewFailed = true; }
            check(previewFailed && titleId("Fault rollback") == null && store.listCards().length() == cardsBefore,
                    "Explicit preview import also rolls back only its new card on ledger failure");
            check(store.containsCard(oldId) && java.util.Arrays.equals(ledgerBytes, Files.readAllBytes(history.toPath())),
                    "Existing cards and prior persisted history remain untouched by both failed paths");
        } finally { delete(blockedWrite); snapshot.delete(); }
        JSONObject retried = scanner.importSelected(select(scanner.scan(), "fault.ruic"), ignored -> {});
        check(retried.getInt("imported") == 1 && retried.getInt("failed") == 0 && store.containsCard(oldId),
                "Import retry succeeds once the actual filesystem obstruction is removed");
        snapshot = write(new File(cache, "fault-preview.ruic"), faultCard);
        CardStore.ImportResult duplicate = scanner.importPreviewFile(snapshot, "fault.ruic"); snapshot.delete();
        check(duplicate.count == 0 && duplicate.addedIds.isEmpty() && duplicate.activeIds.size() == 1,
                "Duplicate preview reports active IDs separately from newly committed IDs");
    }

    private void previewSourceStatus() throws Exception {
        byte[] sourceBytes = card("Preview source status");
        File fixtureSource = write(new File(downloads, "preview-source.ruic"), sourceBytes);
        File source = scanner.resolveCurrentFile(file(scanner.scan(), fixtureSource.getName()).getString("id"));
        File snapshot = write(new File(cache, "preview-source-snapshot.ruic"), sourceBytes);
        long size = source.length(), modified = source.lastModified();
        try {
            CardStore.ImportResult outcome = scanner.importPreviewFile(snapshot, source.getName());
            check(!file(scanner.scan(), source.getName()).getBoolean("imported"), "Source has no guessed fingerprint before explicit preview association");
            scanner.rememberPreviewSource(source, size, modified, snapshot, outcome.activeIds);
            check(file(scanner.scan(), source.getName()).getBoolean("imported"), "Preview source association marks unchanged download imported on rescan");
            source.setLastModified(modified + 10000);
            scanner.rememberPreviewSource(source, size, modified, snapshot, outcome.activeIds);
            check(!file(scanner.scan(), source.getName()).getBoolean("imported"), "Preview association cannot mark a source changed after preview");
            File outside = write(new File(fixtures, "outside-preview.ruic"), sourceBytes).getCanonicalFile();
            byte[] previousLedger = Files.readAllBytes(history.toPath());
            scanner.rememberPreviewSource(outside, outside.length(), outside.lastModified(), snapshot, outcome.activeIds);
            check(java.util.Arrays.equals(previousLedger, Files.readAllBytes(history.toPath())), "Preview association cannot add source paths outside Download");
        } finally { snapshot.delete(); }
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte part : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            value.append(Character.forDigit((part >>> 4) & 15, 16)).append(Character.forDigit(part & 15, 16));
        return value.toString();
    }

    private boolean rejected(File file) { try { DownloadsScanner.inspect(file); return false; } catch (Exception expected) { return true; } }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private String titleId(String title) throws Exception {
        JSONArray cards = store.listCards();
        for (int i = 0; i < cards.length(); i++) if (title.equals(cards.getJSONObject(i).getString("title"))) return cards.getJSONObject(i).getString("id");
        return null;
    }
    private static JSONObject file(JSONObject report, String name) throws Exception {
        JSONArray files = report.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) if (name.equals(files.getJSONObject(i).getString("name"))) return files.getJSONObject(i);
        throw new AssertionError("Scanned file missing: " + name);
    }
    private static String select(JSONObject report, String... names) throws Exception {
        JSONArray ids = new JSONArray(); for (String name : names) ids.put(file(report, name).getString("id")); return ids.toString();
    }
    private static byte[] card(String title) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFF435A76);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, png); bitmap.recycle();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("card-config.json"));
            zip.write(bytes(new JSONObject().put("title", title).put("assets", new JSONObject().put("image", "assets/image.png")).toString())); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/image.png")); zip.write(png.toByteArray()); zip.closeEntry();
        }
        return output.toByteArray();
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static File write(File file, byte[] bytes) throws Exception { try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); } return file; }
    private static void zip(File file, String[] names, byte[][] bytes) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < names.length; i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(bytes[i]); zip.closeEntry(); }
        }
    }
    private static void delete(File file) {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}
