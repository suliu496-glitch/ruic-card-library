package cn.liuyingbin.ruiccards;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline, single-origin app shell. Imported data can never become executable web content. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/assets/library/index.html";
    private static final int IMPORT_REQUEST = 101, EXPORT_REQUEST = 102, DOWNLOAD_PERMISSION_REQUEST = 103;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private CardStore store;
    private DownloadsScanner downloads;
    private final Map<String, PreviewSession> previews = new ConcurrentHashMap<>();
    private String pendingPreviewReport;
    private volatile boolean destroyed;
    private boolean fullscreen;
    private boolean waitingForDownloadAccess, scanWhenPageReady, webReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        store = new CardStore(this);
        downloads = new DownloadsScanner(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                getCacheDir(), new File(getFilesDir(), "download-import-history.json"), store, this::hasDownloadAccess);
        io.execute(() -> PreviewSession.cleanupAbandoned(this));
        waitingForDownloadAccess = state != null && state.getBoolean("waitingForDownloadAccess");
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (HOME.equals(url)) {
                    webReady = true;
                    if (scanWhenPageReady) { scanWhenPageReady = false; queueDownloadScan(); }
                    if (pendingPreviewReport != null) {
                        String report = pendingPreviewReport; pendingPreviewReport = null;
                        callback("onNativePreviewCards", report);
                    }
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The main document is fixed; only the bundled RuiC viewer may be a child frame.
                if (request.isForMainFrame()) return !HOME.equals(request.getUrl().toString());
                return !trustedOrigin(request.getUrl())
                        || !"/assets/library/viewer/index.html".equals(request.getUrl().getPath());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return localResponse(request.getUrl());
            }
        });
        web.addJavascriptInterface(new NativeBridge(), "NativeCards");
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.WHITE);
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(container);
        applySystemBars();
        web.loadUrl(HOME);
        handleExternalFile(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalFile(intent);
    }

    private void handleExternalFile(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) && !Intent.ACTION_SEND.equals(intent.getAction())) return;
        try {
            Uri uri = PreviewSession.intentUri(intent);
            String type = intent.getType();
            queuePreview(() -> PreviewSession.openUri(this, uri, type));
        } catch (Exception error) { previewFailure(error); }
    }

    private void applySystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (fullscreen) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            // The container applies insets, including on Android 8–10, so hidden bars
            // free their space and returning to the album restores the same padding.
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (fullscreen) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        getWindow().getDecorView().requestApplyInsets();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
    }

    private WebResourceResponse localResponse(Uri uri) {
        try {
            if (!trustedOrigin(uri)) return denied();
            String path = uri.getPath();
            if (path == null || !CardStore.safeRelativePath(path.substring(1))) return denied();
            InputStream input;
            if (path.startsWith("/assets/library/")) {
                input = getAssets().open(path.substring("/assets/".length()));
            } else if (path.startsWith("/storage/")) {
                String relative = path.substring("/storage/".length());
                if (!CardStore.allowedDataFile(relative)) return denied();
                File requested = new File(store.cardRoot, relative).getCanonicalFile();
                if (!requested.getPath().startsWith(store.cardRoot.getCanonicalPath() + File.separator)) return denied();
                input = new FileInputStream(requested);
            } else if (path.startsWith("/preview/")) {
                String relative = path.substring("/preview/".length());
                int slash = relative.indexOf('/');
                if (slash < 1) return denied();
                PreviewSession session = previews.get(relative.substring(0, slash));
                if (session == null) return denied();
                input = session.openAsset(relative.substring(slash + 1));
            } else return denied();
            String mime = mime(path);
            WebResourceResponse response = new WebResourceResponse(mime,
                    mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") ? "UTF-8" : null, input);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Cache-Control", "no-cache");
            if (mime.equals("text/html")) {
                headers.put("Content-Security-Policy", "default-src 'self' data: blob:; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
                        + "worker-src 'self' blob:; frame-src https://appassets.androidplatform.net/assets/library/viewer/index.html; "
                        + "frame-ancestors 'self'; object-src 'none'; base-uri 'self'");
            }
            response.setResponseHeaders(headers);
            return response;
        } catch (Exception ignored) { return denied(); }
    }

    private static boolean trustedOrigin(Uri uri) {
        return "https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null;
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", null,
                new ByteArrayInputStream("Unavailable".getBytes(StandardCharsets.UTF_8)));
    }

    private static String mime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".glb")) return "model/gltf-binary";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    public final class NativeBridge {
        @JavascriptInterface public String listCards() { return store.listCards().toString(); }
        @JavascriptInterface public void scanDownloads() { queueDownloadScan(); }
        @JavascriptInterface public void requestDownloadAccess() { runOnUiThread(() -> openDownloadAccess()); }
        @JavascriptInterface public void previewDownload(String id) {
            queuePreview(() -> {
                File file = downloads.resolveCurrentFile(id);
                PreviewSession session = PreviewSession.openFile(MainActivity.this, file, file.getName());
                try { downloads.resolveCurrentFile(id); return session; }
                catch (Exception changed) { session.close(); throw changed; }
            });
        }
        @JavascriptInterface public void commitPreview(String sessionId) {
            if (destroyed) return;
            io.execute(() -> {
                try {
                    PreviewSession session = sessionId == null ? null : previews.get(sessionId);
                    if (session == null) throw new java.io.IOException("预览已关闭，请重新打开闪卡");
                    callback("onNativePreviewCommitted", session.commit(downloads).toString());
                    callback("onNativeCardsChanged", "");
                } catch (Exception error) {
                    try { callback("onNativePreviewCommitted", new JSONObject().put("ok", false).put("sessionId", sessionId)
                            .put("message", CardStore.safeError(error)).put("imported", 0).toString()); }
                    catch (Exception ignored) { }
                }
            });
        }
        @JavascriptInterface public void closePreview(String sessionId) {
            if (destroyed || sessionId == null) return;
            io.execute(() -> {
                PreviewSession session = previews.remove(sessionId);
                if (session != null) session.close();
            });
        }
        @JavascriptInterface public void importDownloads(String idsJson) {
            io.execute(() -> {
                JSONObject outcome = downloads.importSelected(idsJson,
                        progress -> callback("onNativeDownloadImportProgress", progress.toString()));
                callback("onNativeDownloadImportResult", outcome.toString());
                callback("onNativeCardsChanged", "");
            });
        }
        @JavascriptInterface public void importFiles() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp", "application/zip", "application/octet-stream"});
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(intent, IMPORT_REQUEST); }
                catch (Exception e) { result(false, "未找到文件选择器"); }
            });
        }
        @JavascriptInterface public void exportLibrary(String stateJson) {
            try {
                if (!CardStore.validStateJsonSize(stateJson)) throw new IllegalArgumentException();
                // A system picker may outlive this Activity or process. Keep the exact snapshot
                // in private storage instead of a field or a potentially oversized Binder Bundle.
                store.savePendingExportState(new JSONObject(stateJson).toString());
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_TITLE, "映光卡匣-备份-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()) + ".zip");
                    try { startActivityForResult(intent, EXPORT_REQUEST); }
                    catch (Exception e) { store.clearPendingExportState(); result(false, "未找到保存位置选择器"); }
                });
            } catch (Exception e) { result(false, "备份参数无效或过大"); }
        }
        @JavascriptInterface public boolean deleteCard(String id) { return store.deleteCard(id); }
        @JavascriptInterface public void setFullscreen(boolean enabled) {
            runOnUiThread(() -> {
                fullscreen = enabled;
                applySystemBars();
            });
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (code != RESULT_OK || data == null) {
            if (request == EXPORT_REQUEST) store.clearPendingExportState();
            result(false, "已取消"); return;
        }
        if (request == IMPORT_REQUEST) {
            List<Uri> uris = new ArrayList<>();
            ClipData clips = data.getClipData();
            if (clips != null) { for (int i = 0; i < Math.min(clips.getItemCount(), 50); i++) uris.add(clips.getItemAt(i).getUri()); }
            else if (data.getData() != null) uris.add(data.getData());
            io.execute(() -> {
                int imported = 0;
                List<String> errors = new ArrayList<>();
                JSONObject restored = null;
                for (Uri uri : uris) {
                    try {
                        CardStore.ImportResult outcome = store.importUri(uri);
                        imported += outcome.count;
                        if (outcome.state != null) restored = outcome.state;
                    } catch (Exception e) { errors.add(CardStore.safeError(e)); }
                }
                if (restored != null) callback("onNativeRestoreState", restored.toString());
                callback("onNativeCardsChanged", "");
                String message = "已导入 " + imported + " 张闪卡";
                if (!errors.isEmpty()) message += "；" + errors.size() + " 个文件未导入：" + errors.get(0);
                result(imported > 0 || restored != null, message);
            });
        } else if (request == EXPORT_REQUEST && data.getData() != null) {
            Uri uri = data.getData();
            io.execute(() -> {
                try {
                    String stateJson = store.readPendingExportState();
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new java.io.IOException("无法写入所选位置");
                        store.exportBackup(output, stateJson);
                    }
                    result(true, "备份已保存，包含导入闪卡及收藏、调节参数；内置卡由应用提供");
                } catch (Exception e) { result(false, CardStore.safeError(e)); }
                finally { store.clearPendingExportState(); }
            });
        }
    }

    private void result(boolean ok, String message) {
        try { callback("onNativeResult", new JSONObject().put("ok", ok).put("message", message).toString()); }
        catch (Exception ignored) { }
    }

    private interface PreviewFactory { PreviewSession open() throws Exception; }

    private void queuePreview(PreviewFactory factory) {
        if (destroyed) return;
        io.execute(() -> {
            if (destroyed) return;
            PreviewSession session = null;
            try {
                session = factory.open();
                JSONObject report = new JSONObject().put("ok", true).put("message", "临时预览，保存后加入卡库")
                        .put("sessionId", session.id).put("cards", session.cards());
                closeAllPreviews();
                previews.put(session.id, session);
                reportPreview(report.toString());
            } catch (Exception error) {
                if (session != null) session.close();
                previewFailure(error);
            }
        });
    }

    private void previewFailure(Exception error) {
        try { reportPreview(new JSONObject().put("ok", false).put("message", CardStore.safeError(error))
                .put("cards", new JSONArray()).toString()); }
        catch (Exception ignored) { }
    }

    private void reportPreview(String report) {
        runOnUiThread(() -> {
            if (destroyed) return;
            if (webReady) callback("onNativePreviewCards", report);
            else pendingPreviewReport = report;
        });
    }

    private void closeAllPreviews() {
        for (PreviewSession session : previews.values()) session.close();
        previews.clear();
    }

    private boolean hasDownloadAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void queueDownloadScan() {
        io.execute(() -> callback("onNativeDownloadsResult", downloads.scan().toString()));
    }

    private void openDownloadAccess() {
        if (hasDownloadAccess()) { queueDownloadScan(); return; }
        waitingForDownloadAccess = true;
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, DOWNLOAD_PERMISSION_REQUEST);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception perAppUnavailable) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (Exception unavailable) {
                waitingForDownloadAccess = false;
                queueDownloadScan();
            }
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == DOWNLOAD_PERMISSION_REQUEST) {
            waitingForDownloadAccess = false;
            queueDownloadScan();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("waitingForDownloadAccess", waitingForDownloadAccess);
        super.onSaveInstanceState(state);
    }
    private void callback(String name, String jsonArgument) {
        runOnUiThread(() -> { if (!destroyed && !isFinishing() && web != null) web.evaluateJavascript(
                "if(typeof window." + name + "==='function'){window." + name + "(" + jsonArgument + ");}", null); });
    }
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.handleNativeBack?window.handleNativeBack():false", value -> {
            if (!"true".equals(value)) new AlertDialog.Builder(this).setMessage("退出映光卡匣？")
                    .setPositiveButton("退出", (dialog, which) -> finish()).setNegativeButton("继续浏览", null).show();
        });
        else super.onBackPressed();
    }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        applySystemBars();
        if (waitingForDownloadAccess && Build.VERSION.SDK_INT >= 30) {
            waitingForDownloadAccess = false;
            if (webReady) queueDownloadScan(); else scanWhenPageReady = true;
        }
    }
    @Override protected void onDestroy() {
        destroyed = true;
        pendingPreviewReport = null;
        io.execute(this::closeAllPreviews);
        io.shutdown();
        if (web != null) { web.removeJavascriptInterface("NativeCards"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
