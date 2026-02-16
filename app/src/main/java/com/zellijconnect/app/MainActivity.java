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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable connectingTimeout = () -> connectingIndicator.setVisibility(View.GONE);

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
        connectingIndicator.setOnClickListener(v -> v.setVisibility(View.GONE));
        tabStrip = findViewById(R.id.tabStrip);
        Button btnRetry = findViewById(R.id.btnRetry);
        Button btnEscape = findViewById(R.id.btnEscape);
        ImageButton btnAddTab = findViewById(R.id.btnAddTab);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
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
                    // Safety timeout: hide indicator after 10 seconds regardless
                    connectingIndicator.removeCallbacks(connectingTimeout);
                    connectingIndicator.postDelayed(connectingTimeout, 10000);
                }
            }

            @Override
            public void onLoadingFinished(String tabId) {
                // Page loaded but terminal may not be ready yet
                // Keep indicator visible until onTerminalReady is called
            }

            @Override
            public void onTerminalReady(String tabId) {
                // Terminal has content and is ready for interaction
                connectingIndicator.setVisibility(View.GONE);
                connectingIndicator.removeCallbacks(connectingTimeout);
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
        btnAddTab.setOnClickListener(v -> showSessionPicker());
        btnSettings.setOnClickListener(v -> showSettings());
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

    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(this, () -> {
            // Update IME manager with new keyboard IDs
            imeSwitchManager.updateImeIds(
                AppConfig.getTerminalImeId(this),
                AppConfig.getDefaultImeId(this)
            );
        });
        dialog.show();
    }

    private void showSessionPicker() {
        SessionPickerDialog dialog = new SessionPickerDialog(this, new SessionPickerDialog.SessionPickerListener() {
            @Override
            public void onGateway() {
                tabManager.addTab(AppConfig.getGatewayUrl(MainActivity.this));
            }

            @Override
            public void onCreateSession(String sessionName) {
                // Create a new named session
                String sessionUrl = AppConfig.getBaseUrl(MainActivity.this) + "/" + sessionName + "?action=create";
                tabManager.addTab(sessionUrl);
            }

            @Override
            public void onAttachSession(String sessionName) {
                // Attach to existing session
                String sessionUrl = AppConfig.getBaseUrl(MainActivity.this) + "/" + sessionName;
                tabManager.addTab(sessionUrl);
            }
        });
        dialog.show();

        // Fetch sessions from the status API
        fetchSessions(dialog);
    }

    private void fetchSessions(SessionPickerDialog dialog) {
        executor.execute(() -> {
            List<SessionInfo> sessions = new ArrayList<>();
            try {
                // Build API URL (same host as Zellij, configurable port) - HTTPS via Tailscale
                Uri baseUri = Uri.parse(AppConfig.getBaseUrl(MainActivity.this));
                int metadataPort = AppConfig.getMetadataPort(MainActivity.this);
                String apiUrl = "https://" + baseUri.getHost() + ":" + metadataPort + "/api/sessions";

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(response.toString());
                    JSONArray sessionArray = json.getJSONArray("sessions");
                    for (int i = 0; i < sessionArray.length(); i++) {
                        JSONObject sessionJson = sessionArray.getJSONObject(i);
                        sessions.add(SessionInfo.fromJson(sessionJson));
                    }
                }
                conn.disconnect();

                final List<SessionInfo> finalSessions = sessions;
                runOnUiThread(() -> dialog.setSessions(finalSessions));
            } catch (Exception e) {
                Log.e(TAG, "Error fetching sessions", e);
                runOnUiThread(() -> {
                    // Show empty list on error - user can still use Gateway or create new
                    dialog.setSessions(new ArrayList<>());
                });
            }
        });
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
        executor.shutdown();
        KeepAliveService.stop(this);
        super.onDestroy();
    }

}
