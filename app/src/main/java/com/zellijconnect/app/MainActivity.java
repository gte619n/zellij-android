package com.zellijconnect.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.net.Uri;
import android.content.Intent;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity implements TabManager.Listener {

    private static final String TAG = "ZellijConnect";
    private static final String PREFS_NAME = "zellij_prefs";
    private static final String KEY_IMMERSIVE = "immersive_mode";

    private TabManager tabManager;
    private WebViewPool webViewPool;
    private IMESwitchManager imeSwitchManager;
    private ConnectionMonitor connectionMonitor;
    private TabAdapter tabAdapter;

    private FrameLayout webViewContainer;
    private LinearLayout errorBanner;
    private LinearLayout connectingIndicator;
    private RecyclerView tabStrip;
    private boolean isImmersive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check setup guide
        if (!SetupGuideActivity.isSetupComplete(this)) {
            startActivity(new Intent(this, SetupGuideActivity.class));
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Handle keyboard insets to resize content when keyboard appears
        View mainLayout = findViewById(R.id.webViewContainer);
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, windowInsets) -> {
            Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            // Apply bottom padding for keyboard
            v.setPadding(0, 0, 0, imeInsets.bottom);
            // Notify terminal of resize
            TabManager.Tab active = tabManager != null ? tabManager.getActiveTab() : null;
            if (active != null && webViewPool != null) {
                WebView wv = webViewPool.get(active.id);
                if (wv != null) {
                    wv.evaluateJavascript(
                        "if (window.term && window.term.fit) { window.term.fit(); } " +
                        "window.dispatchEvent(new Event('resize'));", null);
                }
            }
            return windowInsets;
        });

        // Request notification permission for foreground service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Init views
        webViewContainer = findViewById(R.id.webViewContainer);
        errorBanner = findViewById(R.id.errorBanner);
        connectingIndicator = findViewById(R.id.connectingIndicator);
        tabStrip = findViewById(R.id.tabStrip);
        Button btnRetry = findViewById(R.id.btnRetry);
        Button btnEscape = findViewById(R.id.btnEscape);
        ImageButton btnAddTab = findViewById(R.id.btnAddTab);
        ImageButton btnOpenBrowser = findViewById(R.id.btnOpenBrowser);
        ImageButton btnToggleImmersive = findViewById(R.id.btnToggleImmersive);

        // Init managers
        imeSwitchManager = new IMESwitchManager(this);
        webViewPool = new WebViewPool(this);
        tabManager = new TabManager(this);
        tabManager.setListener(this);
        connectionMonitor = new ConnectionMonitor(errorBanner, btnRetry, webViewPool);

        // Wire WebViewPool callbacks
        webViewPool.setErrorCallback(new WebViewPool.ErrorCallback() {
            @Override
            public void onError(String tabId) {
                connectionMonitor.onError(tabId);
            }
            @Override
            public void onErrorCleared(String tabId) {
                connectionMonitor.onErrorCleared(tabId);
            }
        });

        webViewPool.setNavigationCallback((tabId, newUrl) ->
            tabManager.updateTabUrl(tabId, newUrl)
        );

        webViewPool.setLoadingCallback(new WebViewPool.LoadingCallback() {
            @Override
            public void onLoadingStarted(String tabId) {
                TabManager.Tab active = tabManager.getActiveTab();
                if (active != null && active.id.equals(tabId)) {
                    connectingIndicator.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onLoadingFinished(String tabId) {
                TabManager.Tab active = tabManager.getActiveTab();
                if (active != null && active.id.equals(tabId)) {
                    connectingIndicator.setVisibility(View.GONE);
                }
            }
        });

        webViewPool.setLinkCallback(url -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        });

        // Setup tab strip
        setupTabStrip();

        // Button listeners
        btnEscape.setOnClickListener(v -> sendEscapeKey());
        btnAddTab.setOnClickListener(v -> tabManager.addTab(AppConfig.getGatewayUrl()));
        btnOpenBrowser.setOnClickListener(v -> openBrowser());
        btnToggleImmersive.setOnClickListener(v -> toggleImmersiveMode());

        // Restore immersive mode preference
        isImmersive = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_IMMERSIVE, false);
        if (isImmersive) {
            applyImmersiveMode(true);
        }

        // Back button: go back in WebView or minimize app
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                TabManager.Tab active = tabManager.getActiveTab();
                if (active != null) {
                    WebView webView = webViewPool.get(active.id);
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                }
                moveTaskToBack(true);
            }
        });

        // Restore tabs or create default
        tabManager.restoreOrCreateDefault();

        // Start keep-alive service
        KeepAliveService.start(this, tabManager.getTabCount());
    }

    private void setupTabStrip() {
        tabAdapter = new TabAdapter(tabManager,
            position -> tabManager.selectTab(position),
            (position, tabId) -> closeTabWithDetach(tabId, position)
        );
        tabStrip.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        );
        tabStrip.setAdapter(tabAdapter);

        // Swipe-to-dismiss (vertical swipe removes tab)
        ItemTouchHelper touchHelper = new ItemTouchHelper(
            new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
                @Override
                public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh,
                                      RecyclerView.ViewHolder target) {
                    return false;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                    int position = vh.getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        TabManager.Tab tab = tabManager.getTabAt(position);
                        if (tab != null) {
                            closeTabWithDetach(tab.id, position);
                        }
                    }
                }
            }
        );
        touchHelper.attachToRecyclerView(tabStrip);

        // Horizontal swipe gesture on tab strip to switch tabs
        tabStrip.addOnScrollListener(new RecyclerView.OnScrollListener() {
            // The RecyclerView naturally scrolls horizontally, which is our tab-switching UX
        });
    }

    // --- TabManager.Listener ---

    @Override
    public void onTabAdded(TabManager.Tab tab, int position) {
        tabAdapter.notifyItemInserted(position);
        showWebViewForTab(tab);
        KeepAliveService.updateTabCount(this, tabManager.getTabCount());
    }

    @Override
    public void onTabRemoved(TabManager.Tab tab, int position) {
        tabAdapter.notifyItemRemoved(position);
        KeepAliveService.updateTabCount(this, tabManager.getTabCount());
    }

    @Override
    public void onTabSelected(TabManager.Tab tab, int position) {
        tabAdapter.notifyDataSetChanged();
        showWebViewForTab(tab);
        tabStrip.smoothScrollToPosition(position);
    }

    @Override
    public void onTabsChanged() {
        tabAdapter.notifyDataSetChanged();
        TabManager.Tab active = tabManager.getActiveTab();
        if (active != null) {
            showWebViewForTab(active);
        }
    }

    private void sendEscapeKey() {
        TabManager.Tab active = tabManager.getActiveTab();
        if (active != null) {
            WebView webView = webViewPool.get(active.id);
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var el = document.activeElement || document.body;" +
                    "  el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true}));" +
                    "  el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true}));" +
                    "})();",
                    null
                );
            }
        }
    }

    private void openBrowser() {
        // Open browser at same host but port 5173
        String baseUrl = AppConfig.getBaseUrl();
        try {
            Uri uri = Uri.parse(baseUrl);
            String browserUrl = uri.getScheme() + "://" + uri.getHost() + ":5173";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open browser", e);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showSessionPicker() {
        SessionPickerDialog dialog = new SessionPickerDialog(this, new SessionPickerDialog.SessionPickerListener() {
            @Override
            public void onNewSession() {
                tabManager.addTab(AppConfig.getGatewayUrl());
            }

            @Override
            public void onSessionSelected(String sessionName) {
                // Connect to specific session
                String sessionUrl = AppConfig.getBaseUrl() + "/session/" + sessionName;
                tabManager.addTab(sessionUrl);
            }
        });
        dialog.show();

        // Fetch sessions using a hidden WebView
        fetchSessions(dialog);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void fetchSessions(SessionPickerDialog dialog) {
        WebView fetchView = new WebView(this);
        fetchView.getSettings().setJavaScriptEnabled(true);
        fetchView.getSettings().setDomStorageEnabled(true);

        fetchView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Try to extract session names from the page
                // Look for links or elements containing session info
                String extractScript =
                    "(function() {" +
                    "  var sessions = [];" +
                    "  // Look for session links (common patterns)" +
                    "  var links = document.querySelectorAll('a[href*=\"session\"], a[href*=\"attach\"]');" +
                    "  links.forEach(function(link) {" +
                    "    var name = link.textContent.trim();" +
                    "    if (name && sessions.indexOf(name) === -1) sessions.push(name);" +
                    "  });" +
                    "  // Also look for list items or divs with session names" +
                    "  var items = document.querySelectorAll('.session, .session-name, [data-session]');" +
                    "  items.forEach(function(item) {" +
                    "    var name = item.textContent.trim() || item.getAttribute('data-session');" +
                    "    if (name && sessions.indexOf(name) === -1) sessions.push(name);" +
                    "  });" +
                    "  // Look for any element containing session-like names" +
                    "  var allText = document.body.innerText;" +
                    "  var matches = allText.match(/[a-z]+-[a-z]+-[a-z]+/gi);" +
                    "  if (matches) {" +
                    "    matches.forEach(function(m) {" +
                    "      if (sessions.indexOf(m) === -1) sessions.push(m);" +
                    "    });" +
                    "  }" +
                    "  return JSON.stringify(sessions);" +
                    "})();";

                view.evaluateJavascript(extractScript, result -> {
                    runOnUiThread(() -> {
                        try {
                            if (result != null && !result.equals("null") && !result.equals("\"[]\"")) {
                                String json = result.replace("\\\"", "\"");
                                if (json.startsWith("\"")) json = json.substring(1);
                                if (json.endsWith("\"")) json = json.substring(0, json.length() - 1);

                                java.util.List<String> sessions = new java.util.ArrayList<>();
                                // Simple JSON array parsing
                                json = json.replace("[", "").replace("]", "").replace("\"", "");
                                if (!json.isEmpty()) {
                                    for (String s : json.split(",")) {
                                        String trimmed = s.trim();
                                        if (!trimmed.isEmpty()) {
                                            sessions.add(trimmed);
                                        }
                                    }
                                }
                                dialog.setSessions(sessions);
                            } else {
                                dialog.setSessions(new java.util.ArrayList<>());
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing sessions", e);
                            dialog.setSessions(new java.util.ArrayList<>());
                        }
                        view.destroy();
                    });
                });
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    runOnUiThread(() -> {
                        dialog.showError("Could not connect to server");
                        view.destroy();
                    });
                }
            }
        });

        fetchView.loadUrl(AppConfig.getGatewayUrl());
    }

    private void closeTabWithDetach(String tabId, int position) {
        WebView webView = webViewPool.get(tabId);
        if (webView != null) {
            // Send Zellij detach command: Ctrl+O followed by 'd'
            // This detaches the session so it can be reattached later
            String detachScript =
                "(function() {" +
                "  var el = document.activeElement || document.body;" +
                "  // Send Ctrl+O" +
                "  el.dispatchEvent(new KeyboardEvent('keydown', {key: 'o', code: 'KeyO', keyCode: 79, which: 79, ctrlKey: true, bubbles: true}));" +
                "  el.dispatchEvent(new KeyboardEvent('keyup', {key: 'o', code: 'KeyO', keyCode: 79, which: 79, ctrlKey: true, bubbles: true}));" +
                "  // Send 'd' after a short delay" +
                "  setTimeout(function() {" +
                "    el.dispatchEvent(new KeyboardEvent('keydown', {key: 'd', code: 'KeyD', keyCode: 68, which: 68, bubbles: true}));" +
                "    el.dispatchEvent(new KeyboardEvent('keyup', {key: 'd', code: 'KeyD', keyCode: 68, which: 68, bubbles: true}));" +
                "  }, 50);" +
                "})();";
            webView.evaluateJavascript(detachScript, null);
        }

        // Remove tab after a brief delay to allow detach command to process
        webViewContainer.postDelayed(() -> {
            webViewPool.remove(tabId);
            tabManager.removeTab(position);
        }, 150);
    }

    private void showWebViewForTab(TabManager.Tab tab) {
        // Remove current WebView from container (but don't destroy it)
        // Keep all WebViews alive in the pool
        for (int i = webViewContainer.getChildCount() - 1; i >= 0; i--) {
            View child = webViewContainer.getChildAt(i);
            if (child instanceof WebView) {
                webViewContainer.removeViewAt(i);
            }
        }

        WebView webView = webViewPool.getOrCreate(tab.id, tab.url);

        // Remove from any existing parent
        if (webView.getParent() != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }

        webViewContainer.addView(webView, 0,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );

        webView.requestFocus();
    }

    // --- IME Switching ---

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            imeSwitchManager.switchToTerminalKeyboard();
            TabManager.Tab active = tabManager.getActiveTab();
            if (active != null) {
                WebView wv = webViewPool.get(active.id);
                if (wv != null) {
                    imeSwitchManager.showKeyboard(wv);
                }
            }
            if (isImmersive) {
                applyImmersiveMode(true);
            }
        } else {
            imeSwitchManager.switchToDefaultKeyboard();
        }
    }

    // --- Volume Key Remapping ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            TabManager.Tab active = tabManager.getActiveTab();
            WebView webView = active != null ? webViewPool.get(active.id) : null;

            if (webView != null) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                    // Send Ctrl+Shift+Alt+K for scroll up (configure in Zellij)
                    webView.evaluateJavascript(
                        "(function() {" +
                        "  var el = document.activeElement || document.body;" +
                        "  el.dispatchEvent(new KeyboardEvent('keydown', {" +
                        "    key: 'k', code: 'KeyK', keyCode: 75, which: 75," +
                        "    ctrlKey: true, shiftKey: true, altKey: true, bubbles: true" +
                        "  }));" +
                        "  el.dispatchEvent(new KeyboardEvent('keyup', {" +
                        "    key: 'k', code: 'KeyK', keyCode: 75, which: 75," +
                        "    ctrlKey: true, shiftKey: true, altKey: true, bubbles: true" +
                        "  }));" +
                        "})();",
                        null
                    );
                    return true;
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    // Send Ctrl+Shift+Alt+J for scroll down (configure in Zellij)
                    webView.evaluateJavascript(
                        "(function() {" +
                        "  var el = document.activeElement || document.body;" +
                        "  el.dispatchEvent(new KeyboardEvent('keydown', {" +
                        "    key: 'j', code: 'KeyJ', keyCode: 74, which: 74," +
                        "    ctrlKey: true, shiftKey: true, altKey: true, bubbles: true" +
                        "  }));" +
                        "  el.dispatchEvent(new KeyboardEvent('keyup', {" +
                        "    key: 'j', code: 'KeyJ', keyCode: 74, which: 74," +
                        "    ctrlKey: true, shiftKey: true, altKey: true, bubbles: true" +
                        "  }));" +
                        "})();",
                        null
                    );
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // --- Immersive Mode ---

    private void toggleImmersiveMode() {
        isImmersive = !isImmersive;
        applyImmersiveMode(isImmersive);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IMMERSIVE, isImmersive)
            .apply();
    }

    private void applyImmersiveMode(boolean immersive) {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;

        if (immersive) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        } else {
            controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }
    }

    // --- Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        // Reload tabs that may have disconnected while backgrounded
        TabManager.Tab active = tabManager.getActiveTab();
        if (active != null) {
            connectionMonitor.checkAndReconnect(active.id);
        }
    }

    @Override
    protected void onDestroy() {
        if (connectionMonitor != null) connectionMonitor.destroy();
        if (webViewPool != null) webViewPool.destroyAll();
        KeepAliveService.stop(this);
        super.onDestroy();
    }

}
