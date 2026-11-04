package org.localocr.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin native shell. The OCR engine (Tesseract, compiled to WebAssembly) and its
 * language models ship inside the APK's assets and are served to the WebView from
 * a virtual origin. Every request to any other host is refused, so nothing ever
 * leaves the device.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.local";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0f1115"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f1115"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1115"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new LocalOnlyClient());
        web.setWebChromeClient(new Chrome());
        web.loadUrl(START_URL);
    }

    // ---- Serve bundled assets; block everything else ----
    private class LocalOnlyClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme();
            if ("blob".equals(scheme) || "data".equals(scheme)) return null; // in-memory, local
            if (!HOST.equals(u.getHost())) return blocked();
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            try {
                InputStream in = getAssets().open("www" + path);
                WebResourceResponse r = new WebResourceResponse(mime(path), null, in);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Access-Control-Allow-Origin", "*");
                h.put("Cache-Control", "no-store");
                r.setResponseHeaders(h);
                return r;
            } catch (IOException e) {
                WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
                r.setStatusCodeAndReasonPhrase(404, "Not Found");
                return r;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return !HOST.equals(req.getUrl().getHost()); // never navigate away
        }

        private WebResourceResponse blocked() {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
            r.setStatusCodeAndReasonPhrase(403, "Offline app");
            return r;
        }

        private String mime(String p) {
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js")) return "application/javascript";
            if (p.endsWith(".wasm")) return "application/wasm";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            return "application/octet-stream"; // .traineddata.gz is decompressed by the engine
        }
    }

    // ---- Camera permission + gallery picker ----
    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() { @Override public void run() {
                boolean wantsCamera = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                }
                if (!wantsCamera) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingPermission = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            }});
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            try {
                startActivityForResult(Intent.createChooser(i, "Choose image"), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
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

    // ---- Copy / share from JS ----
    private class Bridge {
        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() { @Override public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("OCR text", text));
            }});
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Share text"));
            }});
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBackPressed && window.onBackPressed()", new ValueCallback<String>() {
            @Override public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
