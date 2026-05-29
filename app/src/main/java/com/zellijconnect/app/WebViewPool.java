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
    private LoadingCallback loadingCallback;
    private LinkCallback linkCallback;

    // JavaScript interface for terminal readiness callback
    public class TerminalReadyBridge {
        private final String tabId;

        TerminalReadyBridge(String tabId) {
            this.tabId = tabId;
        }

        @android.webkit.JavascriptInterface
        public void onReady() {
            android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
            mainHandler.post(() -> {
                if (loadingCallback != null) {
                    loadingCallback.onTerminalReady(tabId);
                }
            });
        }
    }

    public interface ErrorCallback {
        void onError(String tabId);
        void onErrorCleared(String tabId);
    }

    public interface NavigationCallback {
        void onUrlChanged(String tabId, String newUrl);
    }

    public interface LoadingCallback {
        void onLoadingStarted(String tabId);
        void onLoadingFinished(String tabId);
        void onTerminalReady(String tabId);
    }

    public interface LinkCallback {
        void onExternalLink(String url);
    }

    public WebViewPool(Context context) {
        this.context = context;
        // Enable remote debugging via chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true);
    }

    public void setErrorCallback(ErrorCallback callback) {
        this.errorCallback = callback;
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    public void setLoadingCallback(LoadingCallback callback) {
        this.loadingCallback = callback;
    }

    public void setLinkCallback(LinkCallback callback) {
        this.linkCallback = callback;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebView getOrCreate(String tabId, String url) {
        WebView existing = webViews.get(tabId);
        if (existing != null) {
            // Ensure JavaScript interfaces are attached (might be missing on old WebViews)
            existing.addJavascriptInterface(new TerminalReadyBridge(tabId), "ZellijTerminalReady");
            return existing;
        }

        WebView webView = new WebView(context);
        configureWebView(webView, tabId);
        webViews.put(tabId, webView);

        webView.addJavascriptInterface(new ClipboardBridge(context), "ZellijClipboard");
        webView.addJavascriptInterface(new TerminalReadyBridge(tabId), "ZellijTerminalReady");
        TouchBridge touchBridge = new TouchBridge(context);
        if (linkCallback != null) {
            touchBridge.setLinkOpenCallback(url1 -> linkCallback.onExternalLink(url1));
        }
        webView.addJavascriptInterface(touchBridge, "ZellijTouch");
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
                if (loadingCallback != null) loadingCallback.onLoadingStarted(tabId);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (loadingCallback != null) loadingCallback.onLoadingFinished(tabId);
                // Inject clipboard bridge
                view.evaluateJavascript(ClipboardBridge.getInjectionScript(), null);
                // Auto-fill Zellij token if configured
                if (AppConfig.hasToken(context)) {
                    view.evaluateJavascript(getTokenAutofillScript(context), null);
                }
                // Inject terminal readiness checker after a short delay
                // to avoid interfering with page initialization
                view.postDelayed(() -> {
                    view.evaluateJavascript(getTerminalReadyScript(), null);
                    // Inject touch selection & link detection bridge
                    view.evaluateJavascript(TouchBridge.getInjectionScript(), null);
                }, 1000);
                // Notify URL change for tab label updates
                if (navigationCallback != null) navigationCallback.onUrlChanged(tabId, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + error.getDescription());
                    if (loadingCallback != null) loadingCallback.onLoadingFinished(tabId);
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
                // Keep Zellij navigation within the WebView
                if (url.startsWith(AppConfig.getBaseUrl(context))) {
                    return false;
                }
                // Open external links in phone browser
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (linkCallback != null) {
                        linkCallback.onExternalLink(url);
                    }
                    return true;
                }
                // Block other navigation
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // Enable text selection for copy/paste
        webView.setLongClickable(true);
        webView.setHapticFeedbackEnabled(true);
    }

    private static String getTokenAutofillScript(Context ctx) {
        String token = AppConfig.getZellijToken(ctx).replace("\\", "\\\\").replace("'", "\\'");
        return "(function() {\n" +
            "  if (window.__zellijTokenObserverInstalled) { try { window.__zellijTryFillToken && window.__zellijTryFillToken(); } catch(e){} return; }\n" +
            "  window.__zellijTokenObserverInstalled = true;\n" +
            "  var token = '" + token + "';\n" +
            "\n" +
            "  function findDialog() {\n" +
            "    // Identify the Zellij auth dialog by its AUTHENTICATE button\n" +
            "    var buttons = document.querySelectorAll('button, input[type=\"submit\"]');\n" +
            "    for (var i = 0; i < buttons.length; i++) {\n" +
            "      var btn = buttons[i];\n" +
            "      var text = (btn.textContent || btn.value || '').trim().toUpperCase();\n" +
            "      if (text !== 'AUTHENTICATE') continue;\n" +
            "      // Walk up to find a container that also holds a password input\n" +
            "      var node = btn;\n" +
            "      while (node && node !== document.body) {\n" +
            "        var input = node.querySelector && node.querySelector('input[type=\"password\"], input[type=\"text\"]');\n" +
            "        if (input) return { container: node, input: input, button: btn };\n" +
            "        node = node.parentElement;\n" +
            "      }\n" +
            "    }\n" +
            "    return null;\n" +
            "  }\n" +
            "\n" +
            "  function tryFillTokenDialog() {\n" +
            "    var found = findDialog();\n" +
            "    if (!found) return;\n" +
            "    if (found.container.__zellijFilled) return;\n" +
            "    found.container.__zellijFilled = true;\n" +
            "    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;\n" +
            "    nativeSetter.call(found.input, token);\n" +
            "    found.input.dispatchEvent(new Event('input', { bubbles: true }));\n" +
            "    found.input.dispatchEvent(new Event('change', { bubbles: true }));\n" +
            "    // Pre-fill only: the user presses AUTHENTICATE manually. Auto-clicking\n" +
            "    // here caused an infinite re-submit loop when the token was expired/invalid.\n" +
            "    addTokenControls(found.input);\n" +
            "    try { found.input.focus(); } catch (e) {}\n" +
            "  }\n" +
            "\n" +
            "  function setInputValue(input, val) {\n" +
            "    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;\n" +
            "    nativeSetter.call(input, val);\n" +
            "    input.dispatchEvent(new Event('input', { bubbles: true }));\n" +
            "    input.dispatchEvent(new Event('change', { bubbles: true }));\n" +
            "  }\n" +
            "\n" +
            "  function addTokenControls(input) {\n" +
            "    if (input.__zellijControls) return;\n" +
            "    input.__zellijControls = true;\n" +
            "    var bar = document.createElement('div');\n" +
            "    bar.style.cssText = 'display:flex;gap:8px;margin-top:8px;';\n" +
            "    function mkBtn(label) {\n" +
            "      var b = document.createElement('button');\n" +
            "      b.type = 'button';\n" +
            "      b.textContent = label;\n" +
            "      b.style.cssText = 'flex:1;padding:8px;font-size:14px;cursor:pointer;border:1px solid #888;border-radius:4px;background:#2a2a2a;color:#eee;';\n" +
            "      return b;\n" +
            "    }\n" +
            "    var eyeBtn = mkBtn('\\uD83D\\uDC41 Show');\n" +
            "    eyeBtn.addEventListener('click', function() {\n" +
            "      if (input.type === 'password') { input.type = 'text'; eyeBtn.textContent = '\\uD83D\\uDD12 Hide'; }\n" +
            "      else { input.type = 'password'; eyeBtn.textContent = '\\uD83D\\uDC41 Show'; }\n" +
            "    });\n" +
            "    var clearBtn = mkBtn('\\u2715 Clear');\n" +
            "    clearBtn.addEventListener('click', function() {\n" +
            "      setInputValue(input, '');\n" +
            "      try { input.focus(); } catch (e) {}\n" +
            "    });\n" +
            "    bar.appendChild(eyeBtn);\n" +
            "    bar.appendChild(clearBtn);\n" +
            "    if (input.parentElement) input.parentElement.insertBefore(bar, input.nextSibling);\n" +
            "  }\n" +
            "  window.__zellijTryFillToken = tryFillTokenDialog;\n" +
            "\n" +
            "  // Initial attempt for dialogs already in the DOM\n" +
            "  tryFillTokenDialog();\n" +
            "\n" +
            "  // Watch for the dialog appearing later (e.g. after WebSocket reconnect)\n" +
            "  var pending = false;\n" +
            "  var observer = new MutationObserver(function(mutations) {\n" +
            "    // Cheap pre-filter: only scan when added nodes mention AUTHENTICATE\n" +
            "    var relevant = false;\n" +
            "    for (var i = 0; i < mutations.length && !relevant; i++) {\n" +
            "      var added = mutations[i].addedNodes;\n" +
            "      for (var j = 0; j < added.length; j++) {\n" +
            "        var n = added[j];\n" +
            "        if (n.nodeType === 1 && ((n.textContent || '').toUpperCase().indexOf('AUTHENTICATE') >= 0)) { relevant = true; break; }\n" +
            "      }\n" +
            "    }\n" +
            "    if (!relevant || pending) return;\n" +
            "    pending = true;\n" +
            "    setTimeout(function() { pending = false; tryFillTokenDialog(); }, 50);\n" +
            "  });\n" +
            "  observer.observe(document.body, { childList: true, subtree: true });\n" +
            "})();";
    }

    private static String getTerminalReadyScript() {
        return "(function() {\n" +
            "  if (window.__zellijReadyCheckerInstalled) return;\n" +
            "  window.__zellijReadyCheckerInstalled = true;\n" +
            "\n" +
            "  function notifyReady() {\n" +
            "    if (typeof ZellijTerminalReady !== 'undefined' && ZellijTerminalReady.onReady) {\n" +
            "      try {\n" +
            "        ZellijTerminalReady.onReady();\n" +
            "      } catch(e) {\n" +
            "        console.error('ZellijConnect: Failed to notify ready', e);\n" +
            "      }\n" +
            "    } else {\n" +
            "      console.log('ZellijConnect: ZellijTerminalReady not available');\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  function isTerminalReady() {\n" +
            "    // Check for xterm.js terminal element\n" +
            "    var xtermScreen = document.querySelector('.xterm-screen');\n" +
            "    var xtermRows = document.querySelector('.xterm-rows');\n" +
            "    \n" +
            "    // Terminal exists if we have the screen element\n" +
            "    if (xtermScreen && xtermRows) {\n" +
            "      console.log('ZellijConnect: xterm elements found');\n" +
            "      return true;\n" +
            "    }\n" +
            "\n" +
            "    // Also check for canvas-based terminal\n" +
            "    var canvas = document.querySelector('.xterm canvas');\n" +
            "    if (canvas) {\n" +
            "      console.log('ZellijConnect: xterm canvas found');\n" +
            "      return true;\n" +
            "    }\n" +
            "\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  function checkReady() {\n" +
            "    if (isTerminalReady()) {\n" +
            "      console.log('ZellijConnect: Terminal is ready');\n" +
            "      notifyReady();\n" +
            "      return true;\n" +
            "    }\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // Check immediately\n" +
            "  if (checkReady()) return;\n" +
            "\n" +
            "  // Poll for readiness\n" +
            "  var attempts = 0;\n" +
            "  var maxAttempts = 60; // 30 seconds max\n" +
            "  var interval = setInterval(function() {\n" +
            "    attempts++;\n" +
            "    if (checkReady() || attempts >= maxAttempts) {\n" +
            "      clearInterval(interval);\n" +
            "      if (attempts >= maxAttempts) {\n" +
            "        console.log('ZellijConnect: Terminal ready check timed out');\n" +
            "        notifyReady();\n" +
            "      }\n" +
            "    }\n" +
            "  }, 500);\n" +
            "\n" +
            "  // Also watch for DOM changes\n" +
            "  var observer = new MutationObserver(function() {\n" +
            "    if (checkReady()) {\n" +
            "      observer.disconnect();\n" +
            "      clearInterval(interval);\n" +
            "    }\n" +
            "  });\n" +
            "  observer.observe(document.body, { childList: true, subtree: true });\n" +
            "\n" +
            "  console.log('ZellijConnect: Terminal ready checker installed');\n" +
            "})();";
    }
}
