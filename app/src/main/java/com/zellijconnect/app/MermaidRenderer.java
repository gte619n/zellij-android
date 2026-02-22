package com.zellijconnect.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Renders mermaid diagram code into Bitmaps using an off-screen WebView.
 * The WebView loads a bundled HTML harness with mermaid.js, then captures
 * rendered SVG diagrams as PNG bitmaps via a JavaScript bridge.
 */
public class MermaidRenderer {

    private static final String TAG = "ZellijConnect";
    private static final long RENDER_TIMEOUT_MS = 15_000;

    public interface Callback {
        void onRendered(Bitmap bitmap);
        void onError(String errorMessage);
    }

    private static class PendingRender {
        final String mermaidCode;
        final String cacheKey;
        final Callback callback;
        PendingRender(String code, String key, Callback cb) {
            this.mermaidCode = code;
            this.cacheKey = key;
            this.callback = cb;
        }
    }

    private final Context context;
    private final MermaidBitmapCache cache;
    private final Handler mainHandler;
    private final Queue<PendingRender> pendingQueue = new LinkedList<>();

    private WebView webView;
    private boolean webViewReady;
    private PendingRender currentRender;
    private Runnable timeoutRunnable;
    private int maxWidth;

    public MermaidRenderer(Context context, MermaidBitmapCache cache) {
        this.context = context.getApplicationContext();
        this.cache = cache;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Set the max width for scaling rendered bitmaps (typically TextView width). */
    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
    }

    /**
     * Render mermaid code to a Bitmap. Checks cache first.
     * Callback is always invoked on the main thread.
     */
    public void render(String mermaidCode, Callback callback) {
        String cacheKey = String.valueOf(mermaidCode.hashCode());

        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            callback.onRendered(cached);
            return;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> render(mermaidCode, callback));
            return;
        }

        ensureWebView();

        PendingRender pending = new PendingRender(mermaidCode, cacheKey, callback);
        if (webViewReady && currentRender == null) {
            executeRender(pending);
        } else {
            pendingQueue.add(pending);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (webView != null) return;

        webView = new WebView(context);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);

        webView.addJavascriptInterface(new MermaidBridge(), "MermaidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webViewReady = true;
                processPendingQueue();
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.e(TAG, "Mermaid WebView render process gone");
                handleWebViewCrash();
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/mermaid/render.html");
    }

    private void executeRender(PendingRender pending) {
        currentRender = pending;

        String escaped = pending.mermaidCode
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String js = "renderMermaid('" + escaped + "');";
        webView.evaluateJavascript(js, null);

        // Timeout safety
        timeoutRunnable = () -> {
            if (currentRender == pending) {
                Log.w(TAG, "Mermaid render timed out");
                currentRender = null;
                pending.callback.onError("Diagram rendering timed out");
                processPendingQueue();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, RENDER_TIMEOUT_MS);
    }

    private void processPendingQueue() {
        if (currentRender != null || !webViewReady) return;

        PendingRender next = pendingQueue.poll();
        if (next != null) {
            executeRender(next);
        }
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void handleWebViewCrash() {
        webView = null;
        webViewReady = false;
        cancelTimeout();

        if (currentRender != null) {
            currentRender.callback.onError("Renderer crashed");
            currentRender = null;
        }
        PendingRender p;
        while ((p = pendingQueue.poll()) != null) {
            p.callback.onError("Renderer crashed");
        }
    }

    private Bitmap scaleBitmapToFit(Bitmap source) {
        if (maxWidth <= 0 || source.getWidth() <= maxWidth) return source;

        float ratio = (float) maxWidth / source.getWidth();
        int newHeight = Math.round(source.getHeight() * ratio);
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth, newHeight, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    /** Release the WebView and clear pending work. */
    public void destroy() {
        cancelTimeout();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
            webViewReady = false;
        }
        currentRender = null;
        pendingQueue.clear();
    }

    /** JavaScript interface called from render.html */
    private class MermaidBridge {

        @JavascriptInterface
        public void onRendered(String base64Png, int width, int height) {
            byte[] bytes = Base64.decode(base64Png, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            mainHandler.post(() -> {
                cancelTimeout();
                if (currentRender != null && bitmap != null) {
                    Bitmap scaled = scaleBitmapToFit(bitmap);
                    cache.put(currentRender.cacheKey, scaled);
                    currentRender.callback.onRendered(scaled);
                    currentRender = null;
                    processPendingQueue();
                }
            });
        }

        @JavascriptInterface
        public void onError(String message) {
            mainHandler.post(() -> {
                cancelTimeout();
                if (currentRender != null) {
                    Log.w(TAG, "Mermaid render error: " + message);
                    currentRender.callback.onError(message);
                    currentRender = null;
                    processPendingQueue();
                }
            });
        }
    }
}
