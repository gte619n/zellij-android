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
    private String terminalImeId;
    private String defaultImeId;

    public IMESwitchManager(Context context) {
        this.context = context;
        this.hasPermission = checkPermission();
        this.terminalImeId = AppConfig.getTerminalImeId(context);
        this.defaultImeId = AppConfig.getDefaultImeId(context);
    }

    public boolean hasPermission() {
        return hasPermission;
    }

    public void refreshPermission() {
        this.hasPermission = checkPermission();
    }

    public void updateImeIds(String terminalIme, String defaultIme) {
        this.terminalImeId = terminalIme;
        this.defaultImeId = defaultIme;
    }

    private boolean checkPermission() {
        try {
            ContentResolver resolver = context.getContentResolver();
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
            String currentIme = getCurrentIme();
            if (!terminalImeId.equals(currentIme)) {
                Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    terminalImeId
                );
                Log.d(TAG, "Switched to terminal keyboard: " + terminalImeId);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to switch to terminal keyboard", e);
            hasPermission = false;
        }
    }

    public void switchToDefaultKeyboard() {
        if (!hasPermission) return;
        try {
            String currentIme = getCurrentIme();
            if (!defaultImeId.equals(currentIme)) {
                Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    defaultImeId
                );
                Log.d(TAG, "Switched to default keyboard: " + defaultImeId);
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
