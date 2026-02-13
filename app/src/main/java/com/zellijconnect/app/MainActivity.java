package com.zellijconnect.app;

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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

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

        // Request notification permission for foreground service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Init views
        webViewContainer = findViewById(R.id.webViewContainer);
        errorBanner = findViewById(R.id.errorBanner);
        tabStrip = findViewById(R.id.tabStrip);
        Button btnRetry = findViewById(R.id.btnRetry);
        ImageButton btnAddTab = findViewById(R.id.btnAddTab);
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

        // Setup tab strip
        setupTabStrip();

        // Button listeners
        btnAddTab.setOnClickListener(v -> tabManager.addTab(AppConfig.getGatewayUrl()));
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
        tabAdapter = new TabAdapter(tabManager, position -> tabManager.selectTab(position));
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
                            webViewPool.remove(tab.id);
                        }
                        tabManager.removeTab(position);
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
                    webView.evaluateJavascript(
                        "document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'PageUp', code: 'PageUp', keyCode: 33, which: 33, bubbles: true}));" +
                        "document.activeElement.dispatchEvent(new KeyboardEvent('keyup', {key: 'PageUp', code: 'PageUp', keyCode: 33, which: 33, bubbles: true}));",
                        null
                    );
                    return true;
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    webView.evaluateJavascript(
                        "document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'PageDown', code: 'PageDown', keyCode: 34, which: 34, bubbles: true}));" +
                        "document.activeElement.dispatchEvent(new KeyboardEvent('keyup', {key: 'PageDown', code: 'PageDown', keyCode: 34, which: 34, bubbles: true}));",
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
