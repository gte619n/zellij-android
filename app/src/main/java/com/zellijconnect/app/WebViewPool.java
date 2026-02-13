package com.zellijconnect.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.util.Log;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.HashMap;
import java.util.Map;

public class WebViewPool {

    private static final String TAG = "ZellijConnect";

    private final Context context;
    private final Map<String, WebView> webViews = new HashMap<>();
    private ErrorCallback errorCallback;
    private NavigationCallback navigationCallback;

    public interface ErrorCallback {
        void onError(String tabId);
        void onErrorCleared(String tabId);
    }

    public interface NavigationCallback {
        void onUrlChanged(String tabId, String newUrl);
    }

    public WebViewPool(Context context) {
        this.context = context;
    }

    public void setErrorCallback(ErrorCallback callback) {
        this.errorCallback = callback;
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebView getOrCreate(String tabId, String url) {
        WebView existing = webViews.get(tabId);
        if (existing != null) {
            return existing;
        }

        WebView webView = new WebView(context);
        configureWebView(webView, tabId);
        webViews.put(tabId, webView);

        webView.addJavascriptInterface(new ClipboardBridge(context), "ZellijClipboard");
        webView.loadUrl(url);

        return webView;
    }

    public WebView get(String tabId) {
        return webViews.get(tabId);
    }

    public void remove(String tabId) {
        WebView webView = webViews.remove(tabId);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
    }

    public void reloadAll() {
        for (WebView webView : webViews.values()) {
            webView.reload();
        }
    }

    public void reload(String tabId) {
        WebView webView = webViews.get(tabId);
        if (webView != null) {
            webView.reload();
        }
    }

    public void destroyAll() {
        for (WebView webView : webViews.values()) {
            webView.stopLoading();
            webView.destroy();
        }
        webViews.clear();
    }

    public int getActiveCount() {
        return webViews.size();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView, String tabId) {
        WebSettings settings = webView.getSettings();

        // Core functionality
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // DOM storage covers database needs on modern WebView
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Disable zoom - terminal has fixed layout
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Viewport
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // Performance
        settings.setBlockNetworkImage(false);
        settings.setLoadsImagesAutomatically(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (errorCallback != null) errorCallback.onErrorCleared(tabId);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject clipboard bridge
                view.evaluateJavascript(ClipboardBridge.getInjectionScript(), null);
                // Notify URL change for tab label updates
                if (navigationCallback != null) navigationCallback.onUrlChanged(tabId, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + error.getDescription());
                    if (errorCallback != null) errorCallback.onError(tabId);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Trust Tailscale certificates (system trust store)
                // In production, we'd be more selective here
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Keep all navigation within the WebView
                if (url.startsWith(AppConfig.getBaseUrl())) {
                    return false;
                }
                // Block external navigation
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // Disable long-click context menu to avoid conflicts with terminal
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
    }
}
