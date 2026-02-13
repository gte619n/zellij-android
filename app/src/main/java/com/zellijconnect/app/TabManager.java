package com.zellijconnect.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TabManager {

    private static final String TAG = "ZellijConnect";
    private static final String PREFS_NAME = "zellij_tabs";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_ACTIVE_TAB = "activeTabId";

    private final Context context;
    private final List<Tab> tabs = new ArrayList<>();
    private String activeTabId;
    private Listener listener;

    public interface Listener {
        void onTabAdded(Tab tab, int position);
        void onTabRemoved(Tab tab, int position);
        void onTabSelected(Tab tab, int position);
        void onTabsChanged();
    }

    public static class Tab {
        public final String id;
        public String url;
        public String label;

        public Tab(String url) {
            this.id = UUID.randomUUID().toString();
            this.url = url;
            this.label = AppConfig.extractTabLabel(url);
        }

        public Tab(String id, String url, String label) {
            this.id = id;
            this.url = url;
            this.label = label;
        }
    }

    public TabManager(Context context) {
        this.context = context;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void restoreOrCreateDefault() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String tabsJson = prefs.getString(KEY_TABS, null);
        String savedActiveId = prefs.getString(KEY_ACTIVE_TAB, null);

        if (tabsJson != null) {
            try {
                JSONArray arr = new JSONArray(tabsJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    Tab tab = new Tab(
                        obj.getString("id"),
                        obj.getString("url"),
                        obj.getString("label")
                    );
                    tabs.add(tab);
                }
                if (!tabs.isEmpty()) {
                    activeTabId = savedActiveId;
                    // Validate activeTabId exists
                    if (getTabById(activeTabId) == null) {
                        activeTabId = tabs.get(0).id;
                    }
                    Log.d(TAG, "Restored " + tabs.size() + " tabs");
                    if (listener != null) listener.onTabsChanged();
                    return;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to restore tabs", e);
            }
        }

        // No saved tabs - create default gateway tab
        addTab(AppConfig.getGatewayUrl());
    }

    public Tab addTab(String url) {
        Tab tab = new Tab(url);
        tabs.add(tab);
        activeTabId = tab.id;
        persist();
        int position = tabs.size() - 1;
        if (listener != null) {
            listener.onTabAdded(tab, position);
            listener.onTabSelected(tab, position);
        }
        return tab;
    }

    public void removeTab(int position) {
        if (tabs.size() <= 1) {
            // Last tab - replace with gateway
            Tab oldTab = tabs.get(0);
            tabs.remove(0);
            if (listener != null) listener.onTabRemoved(oldTab, 0);

            addTab(AppConfig.getGatewayUrl());
            return;
        }

        Tab removed = tabs.remove(position);
        if (listener != null) listener.onTabRemoved(removed, position);

        if (removed.id.equals(activeTabId)) {
            int newPos = Math.min(position, tabs.size() - 1);
            activeTabId = tabs.get(newPos).id;
            if (listener != null) listener.onTabSelected(tabs.get(newPos), newPos);
        }
        persist();
    }

    public void selectTab(int position) {
        if (position < 0 || position >= tabs.size()) return;
        Tab tab = tabs.get(position);
        if (tab.id.equals(activeTabId)) return;

        activeTabId = tab.id;
        persist();
        if (listener != null) listener.onTabSelected(tab, position);
    }

    public void selectNextTab() {
        int current = getActiveTabPosition();
        if (current < tabs.size() - 1) {
            selectTab(current + 1);
        }
    }

    public void selectPreviousTab() {
        int current = getActiveTabPosition();
        if (current > 0) {
            selectTab(current - 1);
        }
    }

    public Tab getActiveTab() {
        return getTabById(activeTabId);
    }

    public int getActiveTabPosition() {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(activeTabId)) return i;
        }
        return 0;
    }

    public List<Tab> getTabs() {
        return tabs;
    }

    public int getTabCount() {
        return tabs.size();
    }

    public Tab getTabAt(int position) {
        if (position < 0 || position >= tabs.size()) return null;
        return tabs.get(position);
    }

    public void updateTabUrl(String tabId, String newUrl) {
        Tab tab = getTabById(tabId);
        if (tab != null) {
            tab.url = newUrl;
            tab.label = AppConfig.extractTabLabel(newUrl);
            persist();
            if (listener != null) listener.onTabsChanged();
        }
    }

    private Tab getTabById(String id) {
        if (id == null) return null;
        for (Tab tab : tabs) {
            if (tab.id.equals(id)) return tab;
        }
        return null;
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab tab : tabs) {
                JSONObject obj = new JSONObject();
                obj.put("id", tab.id);
                obj.put("url", tab.url);
                obj.put("label", tab.label);
                arr.put(obj);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TABS, arr.toString())
                .putString(KEY_ACTIVE_TAB, activeTabId)
                .apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to persist tabs", e);
        }
    }
}
