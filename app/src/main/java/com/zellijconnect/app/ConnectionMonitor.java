package com.zellijconnect.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.Set;

public class ConnectionMonitor {

    private static final String TAG = "ZellijConnect";
    private static final long[] BACKOFF_DELAYS = {2000, 4000, 8000, 16000, 30000};

    private final LinearLayout errorBanner;
    private final Button retryButton;
    private final WebViewPool webViewPool;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> errorTabs = new HashSet<>();
    private int retryAttempt = 0;
    private Runnable pendingRetry;

    public ConnectionMonitor(LinearLayout errorBanner, Button retryButton, WebViewPool webViewPool) {
        this.errorBanner = errorBanner;
        this.retryButton = retryButton;
        this.webViewPool = webViewPool;

        retryButton.setOnClickListener(v -> retryNow());
    }

    public void onError(String tabId) {
        errorTabs.add(tabId);
        showBanner();
        scheduleRetry(tabId);
    }

    public void onErrorCleared(String tabId) {
        errorTabs.remove(tabId);
        if (errorTabs.isEmpty()) {
            hideBanner();
            retryAttempt = 0;
            cancelPendingRetry();
        }
    }

    public void checkAndReconnect(String activeTabId) {
        if (errorTabs.contains(activeTabId)) {
            webViewPool.reload(activeTabId);
        }
    }

    private void showBanner() {
        errorBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        errorBanner.setVisibility(View.GONE);
    }

    private void scheduleRetry(String tabId) {
        cancelPendingRetry();
        long delay = BACKOFF_DELAYS[Math.min(retryAttempt, BACKOFF_DELAYS.length - 1)];
        Log.d(TAG, "Scheduling retry in " + delay + "ms (attempt " + retryAttempt + ")");

        pendingRetry = () -> {
            webViewPool.reload(tabId);
            retryAttempt++;
        };
        handler.postDelayed(pendingRetry, delay);
    }

    private void retryNow() {
        cancelPendingRetry();
        retryAttempt = 0;
        for (String tabId : new HashSet<>(errorTabs)) {
            webViewPool.reload(tabId);
        }
    }

    private void cancelPendingRetry() {
        if (pendingRetry != null) {
            handler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
    }

    public void destroy() {
        cancelPendingRetry();
    }
}
