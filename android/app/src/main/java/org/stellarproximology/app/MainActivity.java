package org.stellarproximology.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
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
    private Button catalogButton;
    private final List<String> catalogPaths = new ArrayList<>();

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
        // The native Explore control exposes every original HTML entry point.
        // Source code and the original archived site remain byte-for-byte intact.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 7, 11));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(3), dp(8), dp(3));
        TextView appTitle = new TextView(this);
        appTitle.setText("STELLAR PROXIMOLOGY");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        appTitle.setLetterSpacing(0.05f);
        toolbar.addView(appTitle, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        catalogButton = new Button(this);
        catalogButton.setText("Explore");
        catalogButton.setAllCaps(false);
        catalogButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        catalogButton.setOnClickListener(v -> showCatalog());
        toolbar.addView(catalogButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // Opening the original ZIP on a worker thread avoids blocking the app UI.
        new Thread(() -> {
            try {
                ZipFile opened = prepareArchive();
                List<String> discovered = discoverPages(opened);
                archive = opened;
                runOnUiThread(() -> {
                    catalogPaths.clear();
                    catalogPaths.addAll(discovered);
                    catalogButton.setText("Explore " + discovered.size());
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private List<String> discoverPages(ZipFile source) {
        List<String> paths = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = source.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String path = entry.getName();
            if (!entry.isDirectory() && path.startsWith("assets/web/") &&
                path.toLowerCase(Locale.ROOT).endsWith(".html")) {
                paths.add(path.substring("assets/".length()));
            }
        }
        Collections.sort(paths, Comparator.comparingInt(this::pagePriority)
            .thenComparing(String::compareTo));
        return paths;
    }

    private int pagePriority(String path) {
        if (path.equals("web/index.html")) return 0;
        if (path.equals("web/legacy-shell.html")) return 1;
        if (path.equals("web/suite/index.html")) return 2;
        if (path.equals("web/synthia-runtime.html")) return 3;
        if (path.endsWith("synthia-sovereign-studio/dist/public/index.html")) return 4;
        if (path.endsWith("cynthia-organism-browser/index.html")) return 5;
        if (path.endsWith("synthia-complete-system/index.html")) return 6;
        if (path.endsWith("messy-modern/messy_modern.html")) return 7;
        if (path.endsWith("resonance-body.html")) return 8;
        if (path.endsWith("graph-center.html")) return 9;
        return 100;
    }

    private String pageLabel(String path) {
        switch (pagePriority(path)) {
            case 0: return "Stellar Proximology — main interface";
            case 1: return "Synthia OS — preserved shell";
            case 2: return "SynthAI Suite Center";
            case 3: return "Synthia — cultivation ground";
            case 4: return "YOU–N–I–VERSE Studio";
            case 5: return "Synthia Consciousness Engine";
            case 6: return "Synthia Kiosk";
            case 7: return "MESSY Modern";
            case 8: return "Resonance Body";
            case 9: return "Graph Center";
            default: return path.substring("web/".length());
        }
    }

    private void showCatalog() {
        if (archive == null || catalogPaths.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Archive is loading.")
                .setPositiveButton("Close", null).show();
            return;
        }
        String[] choices = new String[catalogPaths.size()];
        for (int i = 0; i < choices.length; i++) {
            String path = catalogPaths.get(i);
            choices[i] = pageLabel(path) + "\\n" + path;
        }
        new AlertDialog.Builder(this)
            .setTitle("Original archive — " + choices.length + " interfaces")
            .setItems(choices, (dialog, which) -> {
                webView.loadUrl("https://appassets.androidplatform.net/assets/" +
                    catalogPaths.get(which));
            })
            .setNegativeButton("Close", null)
            .show();
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
