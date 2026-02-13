package com.zellijconnect.app;

public final class AppConfig {

    private AppConfig() {}

    public static String getBaseUrl() {
        return BuildConfig.ZELLIJ_BASE_URL;
    }

    public static String getGatewayPath() {
        return BuildConfig.ZELLIJ_GATEWAY_PATH;
    }

    public static String getGatewayUrl() {
        return getBaseUrl() + getGatewayPath();
    }

    public static String getTerminalImeId() {
        return BuildConfig.TERMINAL_IME_ID;
    }

    public static String getDefaultImeId() {
        return BuildConfig.DEFAULT_IME_ID;
    }

    public static String extractTabLabel(String url) {
        String baseUrl = getBaseUrl();
        if (url.startsWith(baseUrl)) {
            String path = url.substring(baseUrl.length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.isEmpty()) {
                return "home";
            }
            // Take the last path segment
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
            return path;
        }
        return "tab";
    }
}
