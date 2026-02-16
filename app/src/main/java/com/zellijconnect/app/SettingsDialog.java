package com.zellijconnect.app;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
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
    private final SshKeyManager sshKeyManager;

    private EditText editBaseUrl;
    private EditText editMetadataPort;
    private EditText editAuthToken;
    private EditText editSshPort;
    private EditText editSshUsername;
    private Spinner spinnerTerminalIme;
    private Spinner spinnerDefaultIme;
    private TextView txtPublicKey;
    private Button btnTestConnection;
    private TextView txtTestResult;

    private List<InputMethodInfo> enabledImes;
    private List<String> imeLabels;
    private List<String> imeIds;

    public SettingsDialog(@NonNull Context context, SettingsListener listener) {
        super(context);
        this.listener = listener;
        this.sshKeyManager = new SshKeyManager(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_settings);

        Context ctx = getContext();

        // Find views
        editBaseUrl = findViewById(R.id.editBaseUrl);
        editMetadataPort = findViewById(R.id.editMetadataPort);
        editAuthToken = findViewById(R.id.editAuthToken);
        editSshPort = findViewById(R.id.editSshPort);
        editSshUsername = findViewById(R.id.editSshUsername);
        spinnerTerminalIme = findViewById(R.id.spinnerTerminalIme);
        spinnerDefaultIme = findViewById(R.id.spinnerDefaultIme);
        txtPublicKey = findViewById(R.id.txtPublicKey);
        TextView txtAppVersion = findViewById(R.id.txtAppVersion);
        Button btnGenerateSshKey = findViewById(R.id.btnGenerateSshKey);
        Button btnCopyPublicKey = findViewById(R.id.btnCopyPublicKey);
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

        // Populate IME spinners
        populateImeSpinners(ctx);

        // SSH key display
        refreshSshKeyDisplay();

        // App version
        try {
            PackageInfo pInfo = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            txtAppVersion.setText(ctx.getString(R.string.app_version, pInfo.versionName));
        } catch (Exception e) {
            txtAppVersion.setText(ctx.getString(R.string.app_version, "?"));
        }

        // Button listeners
        btnGenerateSshKey.setOnClickListener(v -> generateSshKey());
        btnCopyPublicKey.setOnClickListener(v -> copySshKey());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> saveSettings());
        btnCancel.setOnClickListener(v -> dismiss());
    }

    private void populateImeSpinners(Context ctx) {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        enabledImes = imm.getEnabledInputMethodList();
        imeLabels = new ArrayList<>();
        imeIds = new ArrayList<>();

        for (InputMethodInfo imi : enabledImes) {
            imeLabels.add(imi.loadLabel(ctx.getPackageManager()).toString());
            imeIds.add(imi.getId());
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

    private void refreshSshKeyDisplay() {
        if (sshKeyManager.hasKeyPair()) {
            String pubKey = sshKeyManager.getPublicKeyString();
            if (pubKey != null) {
                txtPublicKey.setText(pubKey);
            }
        } else {
            txtPublicKey.setText(R.string.no_ssh_key);
        }
    }

    private void generateSshKey() {
        try {
            sshKeyManager.generateKeyPair();
            refreshSshKeyDisplay();
            Toast.makeText(getContext(), R.string.ssh_key_generated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate SSH key", e);
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copySshKey() {
        String pubKey = sshKeyManager.getPublicKeyString();
        if (pubKey == null) {
            Toast.makeText(getContext(), R.string.no_ssh_key, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey));
        Toast.makeText(getContext(), R.string.ssh_key_copied, Toast.LENGTH_SHORT).show();
    }

    private void testConnection() {
        Context ctx = getContext();

        if (!sshKeyManager.hasKeyPair()) {
            txtTestResult.setText(R.string.sftp_no_key);
            txtTestResult.setTextColor(ctx.getColor(com.google.android.material.R.color.design_default_color_error));
            txtTestResult.setVisibility(android.view.View.VISIBLE);
            return;
        }

        // Read current form values (not saved yet)
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

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            Session session = null;
            ChannelSftp channel = null;
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(new SftpHostKeyStore(ctx));

                File privateKeyFile = new File(ctx.getFilesDir(), "ssh_ed25519");
                jsch.addIdentity(privateKeyFile.getAbsolutePath());

                session = jsch.getSession(fUsername, fHost, fPort);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setConfig("PreferredAuthentications", "publickey");
                session.setUserInfo(new com.jcraft.jsch.UserInfo() {
                    @Override public String getPassphrase() { return null; }
                    @Override public String getPassword() { return null; }
                    @Override public boolean promptPassword(String m) { return false; }
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
