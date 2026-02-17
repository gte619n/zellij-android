package com.zellijconnect.app;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsDialog extends Dialog {

    private static final String TAG = "ZellijConnect";

    public interface SettingsListener {
        void onSettingsChanged();
    }

    private final SettingsListener listener;

    private EditText editBaseUrl;
    private EditText editMetadataPort;
    private EditText editAuthToken;
    private EditText editSshPort;
    private EditText editSshUsername;
    private EditText editSshPassword;
    private Spinner spinnerTerminalIme;
    private Spinner spinnerDefaultIme;
    private Button btnTestConnection;
    private TextView txtTestResult;

    private List<InputMethodInfo> enabledImes;
    private List<String> imeLabels;
    private List<String> imeIds;

    public SettingsDialog(@NonNull Context context, SettingsListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_settings);

        // Set dialog width to 90% of screen width
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.9);
            window.setAttributes(params);
        }

        Context ctx = getContext();

        // Find views
        editBaseUrl = findViewById(R.id.editBaseUrl);
        editMetadataPort = findViewById(R.id.editMetadataPort);
        editAuthToken = findViewById(R.id.editAuthToken);
        editSshPort = findViewById(R.id.editSshPort);
        editSshUsername = findViewById(R.id.editSshUsername);
        editSshPassword = findViewById(R.id.editSshPassword);
        spinnerTerminalIme = findViewById(R.id.spinnerTerminalIme);
        spinnerDefaultIme = findViewById(R.id.spinnerDefaultIme);
        TextView txtAppVersion = findViewById(R.id.txtAppVersion);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        txtTestResult = findViewById(R.id.txtTestResult);
        Button btnSave = findViewById(R.id.btnSettingsSave);
        Button btnCancel = findViewById(R.id.btnSettingsCancel);

        // Load current values
        editBaseUrl.setText(AppConfig.getBaseUrl(ctx));
        editMetadataPort.setText(String.valueOf(AppConfig.getMetadataPort(ctx)));
        editAuthToken.setText(AppConfig.getZellijToken(ctx));
        editSshPort.setText(String.valueOf(AppConfig.getSshPort(ctx)));
        editSshUsername.setText(AppConfig.getSshUsername(ctx));
        editSshPassword.setText(AppConfig.getSshPassword(ctx));

        // Populate IME spinners
        populateImeSpinners(ctx);

        // App version
        try {
            PackageInfo pInfo = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            txtAppVersion.setText(ctx.getString(R.string.app_version, pInfo.versionName));
        } catch (Exception e) {
            txtAppVersion.setText(ctx.getString(R.string.app_version, "?"));
        }

        // Button listeners
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> saveSettings());
        btnCancel.setOnClickListener(v -> dismiss());
    }

    private void populateImeSpinners(Context ctx) {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        enabledImes = imm.getEnabledInputMethodList();
        imeLabels = new ArrayList<>();
        imeIds = new ArrayList<>();

        Log.d(TAG, "Found " + enabledImes.size() + " enabled IMEs:");
        for (InputMethodInfo imi : enabledImes) {
            String label = imi.loadLabel(ctx.getPackageManager()).toString();
            String id = imi.getId();
            Log.d(TAG, "  IME: " + label + " -> " + id);
            imeLabels.add(label);
            imeIds.add(id);
        }

        // Also check app's configured default IME - Samsung devices may not include GBoard in enabled list
        // even though it's installed and usable
        List<InputMethodInfo> allImes = imm.getInputMethodList();
        String configuredDefault = AppConfig.getDefaultImeId(ctx);
        Log.d(TAG, "App configured default IME: " + configuredDefault + ", in list: " + imeIds.contains(configuredDefault));

        // Add any installed IME that's not in the enabled list (for both configured defaults)
        for (String imeToCheck : new String[]{configuredDefault, AppConfig.getTerminalImeId(ctx)}) {
            if (imeToCheck != null && !imeToCheck.isEmpty() && !imeIds.contains(imeToCheck)) {
                for (InputMethodInfo imi : allImes) {
                    if (imi.getId().equals(imeToCheck)) {
                        String label = imi.loadLabel(ctx.getPackageManager()).toString();
                        imeLabels.add(label);
                        imeIds.add(imeToCheck);
                        Log.d(TAG, "Added missing IME: " + label + " -> " + imeToCheck);
                        break;
                    }
                }
            }
        }

        // Samsung randomly hides keyboards from InputMethodManager APIs
        // Check if common keyboards are installed and add them manually if needed
        String[][] knownKeyboards = {
            {"com.google.android.inputmethod.latin", "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", "Gboard"},
            {"juloo.keyboard2", "juloo.keyboard2/.Keyboard2", "Unexpected Keyboard"},
        };
        for (String[] kb : knownKeyboards) {
            String packageName = kb[0];
            String imeId = kb[1];
            String label = kb[2];
            Log.d(TAG, "Checking keyboard: " + label + " (package=" + packageName + ", id=" + imeId + ")");
            Log.d(TAG, "  Already in list: " + imeIds.contains(imeId));
            if (!imeIds.contains(imeId)) {
                try {
                    ctx.getPackageManager().getPackageInfo(packageName, 0);
                    imeLabels.add(label);
                    imeIds.add(imeId);
                    Log.d(TAG, "  Added " + label + " manually");
                } catch (Exception e) {
                    Log.d(TAG, "  Package not found: " + e.getMessage());
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, imeLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerTerminalIme.setAdapter(adapter);
        spinnerDefaultIme.setAdapter(adapter);

        // Select current values
        String currentTerminal = AppConfig.getTerminalImeId(ctx);
        String currentDefault = AppConfig.getDefaultImeId(ctx);

        int terminalIdx = imeIds.indexOf(currentTerminal);
        if (terminalIdx >= 0) spinnerTerminalIme.setSelection(terminalIdx);

        int defaultIdx = imeIds.indexOf(currentDefault);
        if (defaultIdx >= 0) spinnerDefaultIme.setSelection(defaultIdx);
    }

    private void testConnection() {
        Context ctx = getContext();

        // Read current form values (not saved yet)
        String password = editSshPassword.getText().toString();
        if (password.isEmpty()) {
            txtTestResult.setText(R.string.sftp_no_auth);
            txtTestResult.setTextColor(ctx.getColor(com.google.android.material.R.color.design_default_color_error));
            txtTestResult.setVisibility(android.view.View.VISIBLE);
            return;
        }

        String baseUrl = editBaseUrl.getText().toString().trim();
        String host;
        try {
            java.net.URL url = new java.net.URL(baseUrl);
            host = url.getHost();
        } catch (Exception e) {
            host = baseUrl.replaceAll("https?://", "").replaceAll("[:/].*", "");
        }

        int port;
        try {
            port = Integer.parseInt(editSshPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 22;
        }

        String username = editSshUsername.getText().toString().trim();
        if (username.isEmpty()) username = "root";

        btnTestConnection.setEnabled(false);
        txtTestResult.setText(R.string.sftp_testing);
        txtTestResult.setTextColor(ctx.getColor(com.google.android.material.R.color.material_on_surface_emphasis_medium));
        txtTestResult.setVisibility(android.view.View.VISIBLE);

        final String fHost = host;
        final int fPort = port;
        final String fUsername = username;
        final String fPassword = password;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            Session session = null;
            ChannelSftp channel = null;
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(new SftpHostKeyStore(ctx));

                session = jsch.getSession(fUsername, fHost, fPort);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setConfig("PreferredAuthentications", "password");
                session.setPassword(fPassword);

                session.setUserInfo(new com.jcraft.jsch.UserInfo() {
                    @Override public String getPassphrase() { return null; }
                    @Override public String getPassword() { return fPassword; }
                    @Override public boolean promptPassword(String m) { return true; }
                    @Override public boolean promptPassphrase(String m) { return false; }
                    @Override public boolean promptYesNo(String m) { return true; }
                    @Override public void showMessage(String m) {}
                });

                session.connect(10000);
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(10000);

                // List home directory to verify
                Vector<?> entries = channel.ls(".");
                int count = 0;
                for (Object obj : entries) {
                    String name = ((ChannelSftp.LsEntry) obj).getFilename();
                    if (!".".equals(name) && !"..".equals(name)) count++;
                }

                final int itemCount = count;
                final String displayHost = fHost + ":" + fPort;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    txtTestResult.setText(ctx.getString(R.string.sftp_test_success, displayHost, itemCount));
                    txtTestResult.setTextColor(ctx.getColor(com.google.android.material.R.color.material_deep_teal_200));
                    btnTestConnection.setEnabled(true);
                });
            } catch (Exception e) {
                final String errorMsg = e.getMessage();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    txtTestResult.setText(ctx.getString(R.string.sftp_test_failed, errorMsg));
                    txtTestResult.setTextColor(ctx.getColor(com.google.android.material.R.color.design_default_color_error));
                    btnTestConnection.setEnabled(true);
                });
            } finally {
                if (channel != null) try { channel.disconnect(); } catch (Exception ignored) {}
                if (session != null) try { session.disconnect(); } catch (Exception ignored) {}
                executor.shutdown();
            }
        });
    }

    private void saveSettings() {
        Context ctx = getContext();

        // Server config
        String baseUrl = editBaseUrl.getText().toString().trim();
        if (!baseUrl.isEmpty()) {
            AppConfig.setBaseUrl(ctx, baseUrl);
        }

        String portStr = editMetadataPort.getText().toString().trim();
        if (!portStr.isEmpty()) {
            try {
                int port = Integer.parseInt(portStr);
                AppConfig.setMetadataPort(ctx, port);
            } catch (NumberFormatException ignored) {}
        }

        String token = editAuthToken.getText().toString().trim();
        AppConfig.setZellijToken(ctx, token);

        // SSH/SFTP config
        String sshPortStr = editSshPort.getText().toString().trim();
        if (!sshPortStr.isEmpty()) {
            try {
                int sshPort = Integer.parseInt(sshPortStr);
                AppConfig.setSshPort(ctx, sshPort);
            } catch (NumberFormatException ignored) {}
        }

        String sshUsername = editSshUsername.getText().toString().trim();
        AppConfig.setSshUsername(ctx, sshUsername);

        String sshPassword = editSshPassword.getText().toString();
        AppConfig.setSshPassword(ctx, sshPassword);

        // Keyboard config
        int terminalPos = spinnerTerminalIme.getSelectedItemPosition();
        int defaultPos = spinnerDefaultIme.getSelectedItemPosition();
        if (terminalPos >= 0 && terminalPos < imeIds.size()) {
            AppConfig.setTerminalImeId(ctx, imeIds.get(terminalPos));
        }
        if (defaultPos >= 0 && defaultPos < imeIds.size()) {
            AppConfig.setDefaultImeId(ctx, imeIds.get(defaultPos));
        }

        dismiss();
        if (listener != null) {
            listener.onSettingsChanged();
        }
    }
}
