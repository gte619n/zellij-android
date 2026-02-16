package com.zellijconnect.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {

    private static final String PREFS_NAME = "zellij_settings";

    private AppConfig() {}

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Getters (SharedPreferences with BuildConfig fallbacks) ---

    public static String getBaseUrl(Context ctx) {
        return getPrefs(ctx).getString("base_url", BuildConfig.ZELLIJ_BASE_URL);
    }

    public static String getGatewayPath(Context ctx) {
        return getPrefs(ctx).getString("gateway_path", BuildConfig.ZELLIJ_GATEWAY_PATH);
    }

    public static String getGatewayUrl(Context ctx) {
        return getBaseUrl(ctx) + getGatewayPath(ctx);
    }

    public static String getTerminalImeId(Context ctx) {
        return getPrefs(ctx).getString("terminal_ime_id", BuildConfig.TERMINAL_IME_ID);
    }

    public static String getDefaultImeId(Context ctx) {
        return getPrefs(ctx).getString("default_ime_id", BuildConfig.DEFAULT_IME_ID);
    }

    public static String getZellijToken(Context ctx) {
        return getPrefs(ctx).getString("zellij_token", BuildConfig.ZELLIJ_TOKEN);
    }

    public static boolean hasToken(Context ctx) {
        String token = getZellijToken(ctx);
        return token != null && !token.isEmpty();
    }

    public static int getMetadataPort(Context ctx) {
        return getPrefs(ctx).getInt("metadata_port", 7601);
    }

    // --- Setters ---

    public static void setBaseUrl(Context ctx, String url) {
        getPrefs(ctx).edit().putString("base_url", url).apply();
    }

    public static void setGatewayPath(Context ctx, String path) {
        getPrefs(ctx).edit().putString("gateway_path", path).apply();
    }

    public static void setTerminalImeId(Context ctx, String imeId) {
        getPrefs(ctx).edit().putString("terminal_ime_id", imeId).apply();
    }

    public static void setDefaultImeId(Context ctx, String imeId) {
        getPrefs(ctx).edit().putString("default_ime_id", imeId).apply();
    }

    public static void setZellijToken(Context ctx, String token) {
        getPrefs(ctx).edit().putString("zellij_token", token).apply();
    }

    public static void setMetadataPort(Context ctx, int port) {
        getPrefs(ctx).edit().putInt("metadata_port", port).apply();
    }

    // --- Utility (no Context needed) ---

    public static String extractTabLabel(String url, String baseUrl) {
        if (url.startsWith(baseUrl)) {
            String path = url.substring(baseUrl.length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.isEmpty()) {
                return "home";
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
            return path;
        }
        return "tab";
    }
}
