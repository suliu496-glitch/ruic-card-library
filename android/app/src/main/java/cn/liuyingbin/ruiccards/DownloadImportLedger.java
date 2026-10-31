package cn.liuyingbin.ruiccards;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Successful source digests point to the IDs actually committed by CardStore, never guessed IDs. */
final class DownloadImportLedger {
    private static final int MAX_RECORDS = 2000, MAX_BYTES = 4 * 1024 * 1024;
    private final AtomicFile file;
    private final CardStore store;
    private JSONObject sources = new JSONObject(), fingerprints = new JSONObject();
    private boolean loaded;

    DownloadImportLedger(File location, CardStore store) {
        file = new AtomicFile(location);
        this.store = store;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                if (output.size() + read > MAX_BYTES) throw new IOException("导入记录过大");
                output.write(buffer, 0, read);
            }
            JSONObject saved = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            JSONObject sourceRecords = saved.optJSONObject("sources"), cached = saved.optJSONObject("fingerprints");
            if (sourceRecords != null) sources = sourceRecords;
            if (cached != null) fingerprints = cached;
            trim(sources); trim(fingerprints);
        } catch (Exception ignored) { /* A missing or damaged optimization never blocks a valid card import. */ }
    }

    List<String> activeIds(String digest) {
        load();
        JSONArray ids = sources.optJSONArray(digest);
        if (ids == null || ids.length() == 0 || ids.length() > 1024) return null;
        List<String> active = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            if (!store.containsCard(id)) return null;
            active.add(id);
        }
        return active;
    }

    boolean importedFingerprint(String fingerprint) {
        load();
        return activeIds(fingerprints.optString(fingerprint, "")) != null;
    }

    void remember(String digest, List<String> addedIds) throws Exception {
        load();
        if (addedIds.isEmpty()) return;
        JSONObject proposed = new JSONObject(sources.toString());
        proposed.put(digest, new JSONArray(addedIds));
        save(proposed, new JSONObject(fingerprints.toString()));
    }

    void rememberFingerprint(String fingerprint, String digest) throws Exception {
        load();
        JSONObject proposed = new JSONObject(fingerprints.toString());
        proposed.put(fingerprint, digest);
        save(new JSONObject(sources.toString()), proposed);
    }

    void rememberSourceAndFingerprint(String digest, List<String> ids, String fingerprint) throws Exception {
        load();
        if (ids.isEmpty()) return;
        JSONObject proposedSources = new JSONObject(sources.toString()), proposedFingerprints = new JSONObject(fingerprints.toString());
        proposedSources.put(digest, new JSONArray(ids));
        proposedFingerprints.put(fingerprint, digest);
        save(proposedSources, proposedFingerprints);
    }

    private void save(JSONObject proposedSources, JSONObject proposedFingerprints) throws Exception {
        trim(proposedSources); trim(proposedFingerprints);
        byte[] bytes = new JSONObject().put("sources", proposedSources).put("fingerprints", proposedFingerprints)
                .toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("导入记录过大");
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes); output.getFD().sync(); file.finishWrite(output);
            // AtomicFile can log a failed rename instead of throwing; verify publication before updating memory.
            try (InputStream input = file.openRead(); ByteArrayOutputStream verified = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (verified.size() + read > MAX_BYTES) throw new IOException("无法保存导入记录");
                    verified.write(buffer, 0, read);
                }
                if (!Arrays.equals(bytes, verified.toByteArray())) throw new IOException("无法保存导入记录");
            }
        }
        catch (IOException error) { file.failWrite(output); throw error; }
        sources = proposedSources;
        fingerprints = proposedFingerprints;
    }

    private static void trim(JSONObject records) {
        while (records.length() > MAX_RECORDS) {
            Iterator<String> keys = records.keys();
            if (keys.hasNext()) records.remove(keys.next());
        }
    }
}
