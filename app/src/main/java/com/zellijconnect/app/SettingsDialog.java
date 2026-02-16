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

import java.util.ArrayList;
import java.util.List;

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
    private Spinner spinnerTerminalIme;
    private Spinner spinnerDefaultIme;
    private TextView txtPublicKey;

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
        spinnerTerminalIme = findViewById(R.id.spinnerTerminalIme);
        spinnerDefaultIme = findViewById(R.id.spinnerDefaultIme);
        txtPublicKey = findViewById(R.id.txtPublicKey);
        TextView txtAppVersion = findViewById(R.id.txtAppVersion);
        Button btnGenerateSshKey = findViewById(R.id.btnGenerateSshKey);
        Button btnCopyPublicKey = findViewById(R.id.btnCopyPublicKey);
        Button btnSave = findViewById(R.id.btnSettingsSave);
        Button btnCancel = findViewById(R.id.btnSettingsCancel);

        // Load current values
        editBaseUrl.setText(AppConfig.getBaseUrl(ctx));
        editMetadataPort.setText(String.valueOf(AppConfig.getMetadataPort(ctx)));
        editAuthToken.setText(AppConfig.getZellijToken(ctx));

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
