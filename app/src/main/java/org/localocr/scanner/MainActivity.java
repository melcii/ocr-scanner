package org.localocr.scanner;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin native shell around a local web UI.
 *
 * The OCR engine (Tesseract compiled to WebAssembly) and its language models ship in the
 * APK's assets and are served to the WebView from a virtual https origin. Every request to
 * any other host is refused, so images and text never leave the device.
 *
 * Built for Android 17 (API 37): edge-to-edge insets, predictive back, system photo picker,
 * no backups/device transfer of app data, and a locked-down WebView.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.local";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_ALERTS = 3;
    private static final String SHARED_PREFIX = "/shared/";
    private static final int MAX_SHARE_CHARS = 100_000;

    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;

    // Predictive back (API 33+): only intercept back while the in-app camera is open,
    // otherwise let the system play its back-to-home animation.
    private Object cameraBackCallback; // OnBackInvokedCallback, typed as Object for API < 33
    private boolean cameraOpen = false;

    // Image handed to us by Share or by a screenshot notification. Served to the page at
    // https://appassets.local/shared/<token>, read straight from the content URI (no copy).
    private volatile Uri sharedUri;
    private volatile int sharedToken = 0;
    private boolean pageLoaded = false;
    private boolean sharedPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = isDarkMode();
        int bg = Color.parseColor(dark ? "#0f1115" : "#f4f5f8");

        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setUpEdgeToEdge(dark, bg);
        configureWebView();
        web.loadUrl(START_URL);
        handleIncoming(getIntent());
        ScreenshotJob.schedule(this); // re-arm if enabled
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permission may have been revoked in Settings while we were away.
        if (ScreenshotJob.isEnabled(this) && !ScreenshotJob.hasMediaPermission(this)) {
            ScreenshotJob.setEnabled(this, false);
            reportAlerts(false, "Photo access was turned off, so screenshot alerts are off");
        }
    }

    // ---- Share → OCR Scanner, and taps on screenshot notifications ----
    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type == null || !type.startsWith("image/")) return;
            uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                uri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (ScreenshotJob.ACTION_SCAN.equals(action)) {
            uri = intent.getData();
            int id = intent.getIntExtra(ScreenshotJob.EXTRA_NOTIF_ID, 0);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && id != 0) nm.cancel(id);
        }
        if (uri == null || !"content".equals(uri.getScheme())) return;
        sharedUri = uri;
        sharedToken++;
        if (pageLoaded) deliverShared(); else sharedPending = true;
        intent.setAction(null); // don't re-scan on recreate
    }

    private void deliverShared() {
        sharedPending = false;
        web.evaluateJavascript("window.openSharedImage && window.openSharedImage('https://"
                + HOST + SHARED_PREFIX + sharedToken + "')", null);
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---- Edge-to-edge (enforced for apps targeting API 35+) ----
    @SuppressWarnings("deprecation")
    private void setUpEdgeToEdge(boolean dark, int bg) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout()
                                    | WindowInsets.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            if (!dark) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    // ---- WebView lockdown ----
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);           // the UI and OCR engine are JavaScript
        s.setDomStorageEnabled(false);          // nothing is stored
        s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false);            // no file:// access
        s.setAllowContentAccess(false);         // no content:// URL loading (picker uses its own channel)
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false); // camera preview <video>
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= 26) {
            // Safe Browsing checks remote URLs; this app only ever shows its own bundled page,
            // and turning it off keeps the app from contacting any server at all.
            s.setSafeBrowsingEnabled(false);
        }
        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new LocalOnlyClient());
        web.setWebChromeClient(new Chrome());
    }

    /** True only when the WebView is showing our own bundled page. Call on the UI thread. */
    private boolean isTrustedPage() {
        String url = web.getUrl();
        return url != null && HOST.equals(Uri.parse(url).getHost())
                && "https".equals(Uri.parse(url).getScheme());
    }

    // ---- Serve bundled assets; block everything else ----
    private class LocalOnlyClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return empty(403, "Offline app");
            if (!"GET".equalsIgnoreCase(req.getMethod())) return empty(405, "Method Not Allowed");
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (path.contains("..")) return empty(400, "Bad Request");
            if (path.startsWith(SHARED_PREFIX)) return serveShared(path);
            try {
                InputStream in;
                try {
                    in = getAssets().open("www" + path);
                } catch (IOException notFound) {
                    // The Gradle build un-gzips "*.gz" assets and drops the extension.
                    // The OCR engine checks the gzip header itself, so plain data is fine.
                    if (!path.endsWith(".gz")) throw notFound;
                    in = getAssets().open("www" + path.substring(0, path.length() - 3));
                }
                WebResourceResponse r = new WebResourceResponse(mime(path), null, in);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Access-Control-Allow-Origin", "https://" + HOST);
                h.put("Cache-Control", "no-store");
                h.put("X-Content-Type-Options", "nosniff");
                h.put("Cross-Origin-Resource-Policy", "same-origin");
                h.put("Referrer-Policy", "no-referrer");
                h.put("Permissions-Policy", "geolocation=(), microphone=(), payment=(), usb=()");
                r.setResponseHeaders(h);
                return r;
            } catch (IOException e) {
                return empty(404, "Not Found");
            }
        }

        private WebResourceResponse serveShared(String path) {
            Uri uri = sharedUri;
            if (uri == null || !path.equals(SHARED_PREFIX + sharedToken)) return empty(404, "Not Found");
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return empty(404, "Not Found");
                String type = getContentResolver().getType(uri);
                if (type == null || !type.startsWith("image/")) type = "image/jpeg";
                WebResourceResponse r = new WebResourceResponse(type, null, in);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Cache-Control", "no-store");
                h.put("X-Content-Type-Options", "nosniff");
                r.setResponseHeaders(h);
                return r;
            } catch (Exception e) {
                return empty(404, "Not Found");
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && HOST.equals(Uri.parse(url).getHost())) {
                pageLoaded = true;
                if (sharedPending) deliverShared();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return !HOST.equals(req.getUrl().getHost()); // never navigate away
        }

        private WebResourceResponse empty(int code, String reason) {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
            r.setStatusCodeAndReasonPhrase(code, reason);
            return r;
        }

        private String mime(String p) {
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js")) return "text/javascript";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".wasm")) return "application/wasm";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            return "application/octet-stream"; // language models
        }
    }

    // ---- Camera permission + image picker ----
    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() { @Override public void run() {
                Uri origin = request.getOrigin();
                boolean trusted = origin != null && HOST.equals(origin.getHost());
                boolean onlyCamera = request.getResources().length == 1
                        && PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(request.getResources()[0]);
                if (!trusted || !onlyCamera) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    if (pendingPermission != null) pendingPermission.deny();
                    pendingPermission = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            }});
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermission == request) pendingPermission = null;
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i;
            if (Build.VERSION.SDK_INT >= 33) {
                // System photo picker: no storage permission, user picks exactly one image.
                i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                i.setType("image/*");
            } else {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
            }
            try {
                startActivityForResult(i, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("image/*");
                    startActivityForResult(fallback, REQ_FILE);
                } catch (ActivityNotFoundException e2) {
                    fileCallback = null;
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_ALERTS) { finishEnablingAlerts(); return; }
        if (code == REQ_CAMERA && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCallback != null) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
            return;
        }
        super.onActivityResult(code, result, data);
    }

    // ---- Copy / share / camera state from JS (only honoured for our own page) ----
    private class Bridge {
        @JavascriptInterface
        public void copy(final String text) {
            if (text == null) return;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (!isTrustedPage()) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("OCR text", text);
                if (Build.VERSION.SDK_INT >= 24) {
                    // Scanned text can contain personal data: keep it out of the clipboard preview.
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(Build.VERSION.SDK_INT >= 33
                            ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
            }});
        }

        @JavascriptInterface
        public void share(final String text) {
            if (text == null) return;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (!isTrustedPage()) return;
                String t = text.length() > MAX_SHARE_CHARS ? text.substring(0, MAX_SHARE_CHARS) : text;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t);
                startActivity(Intent.createChooser(i, "Share text"));
            }});
        }

        @JavascriptInterface
        public boolean getScreenshotAlerts() {
            return ScreenshotJob.isEnabled(MainActivity.this) && ScreenshotJob.hasMediaPermission(MainActivity.this);
        }

        @JavascriptInterface
        public void setScreenshotAlerts(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() {
                if (!isTrustedPage()) return;
                if (!on) {
                    ScreenshotJob.setEnabled(MainActivity.this, false);
                    reportAlerts(false, null);
                    return;
                }
                List<String> need = missingAlertPermissions();
                if (need.isEmpty()) { finishEnablingAlerts(); return; }
                requestPermissions(need.toArray(new String[0]), REQ_ALERTS);
            }});
        }

        @JavascriptInterface
        public void setCameraOpen(final boolean open) {
            runOnUiThread(new Runnable() { @Override public void run() {
                if (!isTrustedPage()) return;
                cameraOpen = open;
                updateBackCallback();
            }});
        }
    }

    // ---- Screenshot alerts (opt-in) ----
    private List<String> missingAlertPermissions() {
        List<String> need = new ArrayList<String>();
        if (!ScreenshotJob.hasMediaPermission(this)) {
            if (Build.VERSION.SDK_INT >= 33) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
                if (Build.VERSION.SDK_INT >= 34) need.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
            } else {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!ScreenshotJob.hasNotificationPermission(this)) need.add(Manifest.permission.POST_NOTIFICATIONS);
        return need;
    }

    private void finishEnablingAlerts() {
        boolean media = ScreenshotJob.hasMediaPermission(this);
        boolean notif = ScreenshotJob.hasNotificationPermission(this);
        if (media && notif) {
            ScreenshotJob.setEnabled(this, true);
            reportAlerts(true, null);
            return;
        }
        ScreenshotJob.setEnabled(this, false);
        String msg;
        if (!media && Build.VERSION.SDK_INT >= 34
                && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                   == PackageManager.PERMISSION_GRANTED) {
            msg = "Needs \"Allow all\" photo access to see new screenshots.";
        } else if (!media) {
            msg = "Needs photo access to see new screenshots.";
        } else {
            msg = "Needs notification permission.";
        }
        // If Android won't show the prompt again (denied twice), send the user to App info.
        boolean canAskAgain = false;
        for (String p : missingAlertPermissions()) {
            if (shouldShowRequestPermissionRationale(p)) canAskAgain = true;
        }
        reportAlerts(false, canAskAgain ? msg : msg + " Opening Settings…");
        if (!canAskAgain) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (Exception ignored) { }
        }
    }

    private void reportAlerts(boolean on, String message) {
        if (web == null) return;
        String m = message == null ? "null" : "'" + message.replace("\\", "\\\\").replace("'", "\\'") + "'";
        web.evaluateJavascript("window.onScreenshotAlerts && window.onScreenshotAlerts(" + on + "," + m + ")", null);
    }

    // ---- Back handling: predictive back on API 33+, legacy below ----
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (cameraOpen && cameraBackCallback == null) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override public void onBackInvoked() { closeCameraFromNative(); }
            };
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            cameraBackCallback = cb;
        } else if (!cameraOpen && cameraBackCallback != null) {
            d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) cameraBackCallback);
            cameraBackCallback = null;
        }
    }

    private void closeCameraFromNative() {
        web.evaluateJavascript("window.onBackPressed && window.onBackPressed()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Only reached on Android 12L and below (predictive back handles 13+).
        if (cameraOpen) { closeCameraFromNative(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Release the camera when the app leaves the screen.
        if (cameraOpen) closeCameraFromNative();
    }

    @Override
    protected void onDestroy() {
        if (pendingPermission != null) { pendingPermission.deny(); pendingPermission = null; }
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
