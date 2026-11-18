package org.stellarproximology.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/web/index.html";
    private static final int FILE_PICKER_CODE = 101;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFiles;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 7, 11));
        getWindow().setNavigationBarColor(Color.rgb(8, 7, 11));

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebAssets())
            .build();

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                new ServiceWorkerClientCompat() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                        return loader.shouldInterceptRequest(req.getUrl());
                    }
                }
            );
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_PICKER_CODE);
                    return true;
                } catch (Exception e) {
                    pendingFiles.onReceiveValue(null);
                    pendingFiles = null;
                    return false;
                }
            }
        });
        setContentView(webView);
        if (state == null) webView.loadUrl(START_URL);
        else webView.restoreState(state);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent intent) {
        super.onActivityResult(req, result, intent);
        if (req == FILE_PICKER_CODE && pendingFiles != null) {
            pendingFiles.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result, intent));
            pendingFiles = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // Asset URLs keep the web app's relative URLs and ES module imports intact.
    private final class WebAssets implements WebViewAssetLoader.PathHandler {
        @Override
        public WebResourceResponse handle(String path) {
            if (path == null || path.startsWith("/") || path.contains("..")) return null;
            try {
                InputStream bytes = getAssets().open(path);
                String p = path.toLowerCase(Locale.ROOT);
                String mime = typeFor(p);
                String encoding = isText(mime) ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, bytes);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static boolean isText(String type) {
        return type.startsWith("text/") || type.equals("application/javascript")
            || type.equals("application/json") || type.equals("application/manifest+json")
            || type.equals("image/svg+xml") || type.equals("application/x-ndjson");
    }

    private static String typeFor(String path) {
        if (path.endsWith(".mjs") || path.endsWith(".js") || path.endsWith(".cjs"))
            return "application/javascript";
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".jsonl")) return "application/x-ndjson";
        if (path.endsWith(".webmanifest")) return "application/manifest+json";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".pdf")) return "application/pdf";
        if (path.endsWith(".txt") || path.endsWith(".md")) return "text/plain";
        if (path.endsWith(".xml")) return "application/xml";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".ogg")) return "audio/ogg";
        if (path.endsWith(".wav")) return "audio/wav";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
