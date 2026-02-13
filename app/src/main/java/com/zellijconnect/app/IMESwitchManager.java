package com.zellijconnect.app;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

public class IMESwitchManager {

    private static final String TAG = "ZellijConnect";

    private final Context context;
    private boolean hasPermission;

    public IMESwitchManager(Context context) {
        this.context = context;
        this.hasPermission = checkPermission();
    }

    public boolean hasPermission() {
        return hasPermission;
    }

    public void refreshPermission() {
        this.hasPermission = checkPermission();
    }

    private boolean checkPermission() {
        try {
            ContentResolver resolver = context.getContentResolver();
            // Try reading a secure setting to verify permission
            Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD);
            // Try writing to test write permission - write the current value back
            String current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD);
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, current);
            return true;
        } catch (SecurityException e) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not granted");
            return false;
        }
    }

    public void switchToTerminalKeyboard() {
        if (!hasPermission) return;
        try {
            String terminalIme = AppConfig.getTerminalImeId();
            String currentIme = getCurrentIme();
            if (!terminalIme.equals(currentIme)) {
                Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    terminalIme
                );
                Log.d(TAG, "Switched to terminal keyboard: " + terminalIme);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to switch to terminal keyboard", e);
            hasPermission = false;
        }
    }

    public void switchToDefaultKeyboard() {
        if (!hasPermission) return;
        try {
            String defaultIme = AppConfig.getDefaultImeId();
            String currentIme = getCurrentIme();
            if (!defaultIme.equals(currentIme)) {
                Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    defaultIme
                );
                Log.d(TAG, "Switched to default keyboard: " + defaultIme);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to switch to default keyboard", e);
            hasPermission = false;
        }
    }

    public void showKeyboard(android.view.View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private String getCurrentIme() {
        return Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD
        );
    }
}
