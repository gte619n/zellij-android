package com.zellijconnect.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;

/**
 * Trust-on-first-use (TOFU) host key store backed by SharedPreferences.
 * Stores host key fingerprints on first connection and warns if they change.
 */
public class SftpHostKeyStore implements HostKeyRepository {

    private static final String TAG = "ZellijConnect";
    private static final String PREFS_NAME = "sftp_host_keys";

    private final SharedPreferences prefs;

    public enum VerifyResult {
        NEW_HOST,       // Never seen this host before
        MATCH,          // Host key matches stored key
        CHANGED         // Host key differs from stored key (danger!)
    }

    public SftpHostKeyStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Check if a host key matches what we have stored.
     */
    public VerifyResult verify(String host, byte[] key) {
        String stored = prefs.getString(hostKey(host), null);
        if (stored == null) {
            return VerifyResult.NEW_HOST;
        }
        String incoming = Base64.encodeToString(key, Base64.NO_WRAP);
        return stored.equals(incoming) ? VerifyResult.MATCH : VerifyResult.CHANGED;
    }

    /**
     * Store (trust) a host key.
     */
    public void trust(String host, byte[] key) {
        String encoded = Base64.encodeToString(key, Base64.NO_WRAP);
        prefs.edit().putString(hostKey(host), encoded).apply();
        Log.d(TAG, "Trusted host key for " + host);
    }

    /**
     * Get a human-readable fingerprint of a key.
     */
    public static String fingerprint(byte[] key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key);
            StringBuilder sb = new StringBuilder();
            sb.append("SHA256:");
            sb.append(Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String hostKey(String host) {
        return "host_" + host;
    }

    // --- HostKeyRepository interface (used by JSch) ---

    @Override
    public int check(String host, byte[] key) {
        VerifyResult result = verify(host, key);
        switch (result) {
            case MATCH: return HostKeyRepository.OK;
            case CHANGED: return HostKeyRepository.CHANGED;
            default: return HostKeyRepository.NOT_INCLUDED;
        }
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        // HostKey.getKey() returns base64-encoded String; decode to byte[] for trust()
        byte[] keyBytes = Base64.decode(hostkey.getKey(), Base64.DEFAULT);
        trust(hostkey.getHost(), keyBytes);
    }

    @Override
    public void remove(String host, String type) {
        prefs.edit().remove(hostKey(host)).apply();
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        remove(host, type);
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return "ZellijConnect-TOFU";
    }

    @Override
    public HostKey[] getHostKey() {
        return new HostKey[0];
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return new HostKey[0];
    }
}
