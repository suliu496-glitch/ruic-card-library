package cn.liuyingbin.ruiccards;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Tests production CardStore using Android decoders and the actual app-private filesystem. */
public final class CardStoreInstrumentation extends Instrumentation {
    private int assertions;
    private boolean webviewDebugOnly;
    private String previewUri, previewMime;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        webviewDebugOnly = arguments != null && "true".equals(arguments.getString("webviewDebugOnly"));
        if (webviewDebugOnly) {
            previewUri = arguments.getString("previewUri");
            previewMime = arguments.getString("previewMime", "application/vnd.ruic.card");
        }
        start();
    }
    @Override public void onStart() {
        if (webviewDebugOnly) {
            // Debugging exists only in the separately signed test harness, never in release MainActivity.
            runOnMainSync(() -> android.webkit.WebView.setWebContentsDebuggingEnabled(true));
            android.content.Intent launch = getTargetContext().getPackageManager()
                    .getLaunchIntentForPackage(getTargetContext().getPackageName());
            if (launch == null) throw new IllegalStateException("App launcher missing");
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (previewUri != null) {
                // Only the test sender permits legacy file URIs to exercise production onCreate handling.
                if (previewUri.startsWith("file:")) android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
                launch.setAction(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(previewUri), previewMime);
                launch.removeCategory(android.content.Intent.CATEGORY_LAUNCHER);
                launch.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivitySync(launch);
            Bundle ready = new Bundle(); ready.putString("stream", "Release WebView debugging session ready; force-stop target app to close.\n");
            sendStatus(0, ready);
            return; // Leave the Activity and instrumentation alive for the external CDP verifier.
        }
        Bundle result = new Bundle();
        Set<String> createdIds = new HashSet<>();
        File fixtures = new File(getTargetContext().getCacheDir(), "qa-fixtures"); fixtures.mkdirs();
        CardStore store = new CardStore(getTargetContext());
        try {
            Set<String> initial = ids(store.listCards());
            File png = new File(fixtures, "fixture.png");
            writePng(png, 16, 24);
            CardStore.ImportResult imported = store.importUri(Uri.fromFile(png));
            check(imported.count == 1, "Image imported");
            Set<String> added = ids(store.listCards()); added.removeAll(initial);
            check(added.size() == 1, "Image listed once");
            createdIds.addAll(added);
            String id = added.iterator().next();
            JSONObject card = card(store.listCards(), id);
            check("image".equals(card.getString("kind")), "Single image is honestly identified");
            check(card.getString("cover").startsWith("https://appassets.androidplatform.net/storage/" + id + "/"), "Private same-origin cover");
            JSONObject state = new JSONObject().put("schema", 1).put("cards", new JSONObject().put(id,
                    new JSONObject().put("favorite", true).put("parameters", new JSONObject().put("foil", 0.77))));
            store.savePendingExportState(state.toString());
            CardStore recreated = new CardStore(getTargetContext());
            check(state.toString().equals(recreated.readPendingExportState()), "Picker state survives store / Activity reconstruction");
            recreated.clearPendingExportState();
            boolean missingStateRejected = false;
            try { recreated.readPendingExportState(); } catch (java.io.IOException expected) { missingStateRejected = true; }
            check(missingStateRejected, "Missing picker state fails instead of exporting empty parameters");
            ByteArrayOutputStream backup = new ByteArrayOutputStream();
            store.exportBackup(backup, state.toString());
            check(backup.size() > 100, "Backup contains data");
            check(store.deleteCard(id), "Image deleted");
            check(!store.deleteCard("../cards"), "Traversal delete rejected");
            check(ids(store.listCards()).equals(initial), "Library returned to initial state");
            File restoreFile = new File(fixtures, "restore.zip");
            try (FileOutputStream output = new FileOutputStream(restoreFile)) { output.write(backup.toByteArray()); }
            CardStore.ImportResult restored = store.importUri(Uri.fromFile(restoreFile));
            check(restored.count == 1, "Backup restored imported card");
            check(ids(store.listCards()).contains(id), "Restored ID remains stable");
            check(restored.state.getJSONObject("cards").getJSONObject(id).getBoolean("favorite"), "Favorite restored");
            check(Math.abs(restored.state.getJSONObject("cards").getJSONObject(id).getJSONObject("parameters").getDouble("foil") - .77) < .001, "Fine tuning restored");
            check(store.importUri(Uri.fromFile(restoreFile)).count == 0, "Repeated restore skips existing IDs");
            store.deleteCard(id);
            File stateOnly = new File(fixtures, "state-only.zip");
            writeZip(stateOnly, new String[]{"library-state.json"}, new byte[][]{state.toString().getBytes(StandardCharsets.UTF_8)});
            CardStore.ImportResult stateRestore = store.importUri(Uri.fromFile(stateOnly));
            check(stateRestore.count == 0 && stateRestore.state != null, "Built-in-only parameter backup restored");
            for (String forbidden : new String[]{"../escape.json", "assets/app.js", "assets/x.svg", "assets\\cover.png"}) {
                File archive = new File(fixtures, "bad.zip");
                writeZip(archive, new String[]{forbidden}, new byte[][]{"{}".getBytes(StandardCharsets.UTF_8)});
                boolean rejected = false;
                try { store.importUri(Uri.fromFile(archive)); } catch (Exception expected) { rejected = true; }
                check(rejected, "Forbidden ZIP rejected: " + forbidden);
            }
            File duplicate = new File(fixtures, "duplicate.zip");
            writeZip(duplicate, new String[]{"cover.png", "COVER.PNG"}, new byte[][]{{1}, {2}});
            boolean duplicatesRejected = false;
            try { store.importUri(Uri.fromFile(duplicate)); } catch (Exception expected) { duplicatesRejected = true; }
            check(duplicatesRejected, "Case-fold duplicate ZIP entries rejected");
            File oversize = new File(fixtures, "wide.png"); writePng(oversize, 8193, 1);
            boolean pixelsRejected = false;
            try { store.importUri(Uri.fromFile(oversize)); } catch (Exception expected) { pixelsRejected = true; }
            check(pixelsRejected, "Oversized image dimension rejected by Android decoder");
            check(!new File(getTargetContext().getCacheDir(), "escape.json").exists(), "No ZIP traversal file created");
            check(ids(store.listCards()).equals(initial), "No failed-import residue");
            String budgetId = "import_qa_budget";
            File budgetDirectory = new File(store.cardRoot, budgetId); budgetDirectory.mkdirs(); createdIds.add(budgetId);
            for (int i = 0; i < 1024; i++) new File(budgetDirectory, i + ".json").createNewFile();
            ByteArrayOutputStream rejectedBackup = new ByteArrayOutputStream();
            boolean budgetRejected = false;
            try { store.exportBackup(rejectedBackup, "{}"); } catch (java.io.IOException expected) { budgetRejected = true; }
            check(budgetRejected, "Unrestorable backup entry count rejected before export");
            check(rejectedBackup.size() == 0, "Rejected backup does not write destination data");
            store.deleteCard(budgetId);
            File[] backupResidue = getTargetContext().getCacheDir().listFiles(file -> file.getName().startsWith("backup-"));
            check(backupResidue != null && backupResidue.length == 0, "Private staged backup is cleaned after success and failure");
            assertions += DownloadsScannerInstrumentation.run(getTargetContext());
            assertions += PreviewInstrumentation.run(getTargetContext());
            result.putString("stream", "\nCardStore, Download scanner and Preview Android instrumentation: " + assertions + " assertions passed.\n");
            result.putInt("passed", assertions);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nCardStore Android instrumentation failed: " + error + "\n");
            result.putString("failure", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            for (String id : createdIds) store.deleteCard(id);
            deleteFixtures(fixtures);
        }
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private static Set<String> ids(JSONArray cards) throws Exception {
        Set<String> ids = new HashSet<>(); for (int i = 0; i < cards.length(); i++) ids.add(cards.getJSONObject(i).getString("id")); return ids;
    }
    private static JSONObject card(JSONArray cards, String id) throws Exception {
        for (int i = 0; i < cards.length(); i++) if (id.equals(cards.getJSONObject(i).getString("id"))) return cards.getJSONObject(i);
        throw new AssertionError("Card missing");
    }
    private static void writePng(File file, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFF435A76);
        try (FileOutputStream output = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
    private static void writeZip(File file, String[] paths, byte[][] content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < paths.length; i++) { zip.putNextEntry(new ZipEntry(paths[i])); zip.write(content[i]); zip.closeEntry(); }
        }
    }
    private static void deleteFixtures(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteFixtures(child); file.delete();
    }
}
