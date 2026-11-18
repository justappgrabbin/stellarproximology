package org.stellarproximology.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    private static final String START_URL =
        "https://appassets.androidplatform.net/assets/web/index.html";
    private static final String ASSET_NAME = "stellar-source.zip";
    private static final String SOURCE_SHA256 =
        "503f7ca959767d531b528b89ac21b7761afa8fba1c6e1ffb9e3c41835df4a0aa";
    private static final long SOURCE_LENGTH = 92476071L;
    private static final int FILE_PICKER_CODE = 101;
    private volatile ZipFile archive;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFiles;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 7, 11));
        getWindow().setNavigationBarColor(Color.rgb(8, 7, 11));

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new ArchiveAssets()).build();

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
        webView.setBackgroundColor(Color.rgb(8, 7, 11));
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

        // Opening the original ZIP on a worker thread avoids blocking the app UI.
        new Thread(() -> {
            try {
                ZipFile opened = prepareArchive();
                archive = opened;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (state != null && webView.restoreState(state) != null) return;
                    webView.loadUrl(START_URL);
                });
            } catch (Exception e) {
                Log.e("StellarProximology", "Original archive unavailable", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        webView.loadData("<h3>Could not open the local app archive.</h3>",
                                         "text/html", "UTF-8");
                    }
                });
            }
        }, "StellarArchiveBootstrap").start();
    }

    private ZipFile prepareArchive() throws IOException {
        File target = new File(getFilesDir(), ASSET_NAME);
        if (!target.isFile() || target.length() != SOURCE_LENGTH) {
            File temporary = new File(getFilesDir(), ASSET_NAME + ".tmp");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[1024 * 128];
                try (InputStream input = getAssets().open(ASSET_NAME);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    int count;
                    while ((count = input.read(block)) != -1) {
                        digest.update(block, 0, count);
                        output.write(block, 0, count);
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) {
                    hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                }
                if (temporary.length() != SOURCE_LENGTH ||
                    !SOURCE_SHA256.equals(hex.toString())) {
                    throw new IOException("Bundled ZIP failed source integrity verification");
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("Could not replace old local archive");
                }
                if (!temporary.renameTo(target)) {
                    throw new IOException("Could not install verified local archive");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            } finally {
                temporary.delete();
            }
        }
        return new ZipFile(target);
    }

    private final class ArchiveAssets implements WebViewAssetLoader.PathHandler {
        @Override
        public WebResourceResponse handle(String path) {
            ZipFile current = archive;
            if (current == null || path == null || path.startsWith("/") ||
                path.contains("..")) return null;
            try {
                ZipEntry item = current.getEntry("assets/" + path);
                if (item == null || item.isDirectory()) return null;
                String mime = typeFor(path.toLowerCase(Locale.ROOT));
                String encoding = isText(mime) ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, current.getInputStream(item));
            } catch (IOException e) {
                return null;
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == FILE_PICKER_CODE && pendingFiles != null) {
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
        ZipFile current = archive;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
        super.onDestroy();
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
