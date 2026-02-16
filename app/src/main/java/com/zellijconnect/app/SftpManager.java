package com.zellijconnect.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages SFTP connections with per-host pooling.
 * Thread-safe: all SFTP operations run on a background executor.
 */
public class SftpManager {

    private static final String TAG = "ZellijConnect";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    private final Context context;
    private final SftpHostKeyStore hostKeyStore;
    private final Map<String, SftpConnection> connections = new HashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HostKeyCallback hostKeyCallback;

    public interface HostKeyCallback {
        void onNewHostKey(String host, String fingerprint, Runnable onAccept, Runnable onReject);
        void onHostKeyChanged(String host, String fingerprint, Runnable onAccept, Runnable onReject);
    }

    public interface ListCallback {
        void onSuccess(List<SftpFileEntry> entries);
        void onError(String message);
    }

    public interface ContentCallback {
        void onSuccess(byte[] content);
        void onError(String message);
    }

    private static class SftpConnection {
        Session session;
        ChannelSftp channel;
        String host;
        int port;
    }

    public SftpManager(Context context) {
        this.context = context;
        this.hostKeyStore = new SftpHostKeyStore(context);
    }

    public void setHostKeyCallback(HostKeyCallback callback) {
        this.hostKeyCallback = callback;
    }

    /**
     * List directory contents asynchronously.
     */
    public void listDirectory(String host, int port, String path, ListCallback callback) {
        executor.execute(() -> {
            try {
                ChannelSftp channel = getOrConnect(host, port);
                Vector<?> entries = channel.ls(path);
                List<SftpFileEntry> result = new ArrayList<>();

                for (Object obj : entries) {
                    ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) obj;
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;

                    result.add(new SftpFileEntry(
                        name,
                        path.endsWith("/") ? path + name : path + "/" + name,
                        entry.getAttrs().getSize(),
                        entry.getAttrs().getMTime(),
                        entry.getAttrs().isDir(),
                        entry.getAttrs().getPermissions()
                    ));
                }

                Collections.sort(result);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                Log.e(TAG, "SFTP ls failed: " + path, e);
                // Try reconnect
                String key = host + ":" + port;
                disconnectQuietly(key);
                handleError(host, port, path, callback, e, 1);
            }
        });
    }

    /**
     * Read file contents asynchronously.
     */
    public void readFile(String host, int port, String path, ContentCallback callback) {
        executor.execute(() -> {
            try {
                ChannelSftp channel = getOrConnect(host, port);
                InputStream is = channel.get(path);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                is.close();
                byte[] content = baos.toByteArray();
                mainHandler.post(() -> callback.onSuccess(content));
            } catch (Exception e) {
                Log.e(TAG, "SFTP read failed: " + path, e);
                String key = host + ":" + port;
                disconnectQuietly(key);
                mainHandler.post(() -> callback.onError("Failed to read file: " + e.getMessage()));
            }
        });
    }

    public interface StatCallback {
        void onSuccess(long sizeBytes);
        void onError(String message);
    }

    /**
     * Get file size asynchronously via SFTP stat.
     */
    public void statFile(String host, int port, String path, StatCallback callback) {
        executor.execute(() -> {
            try {
                ChannelSftp channel = getOrConnect(host, port);
                long size = channel.stat(path).getSize();
                mainHandler.post(() -> callback.onSuccess(size));
            } catch (Exception e) {
                Log.e(TAG, "SFTP stat failed: " + path, e);
                mainHandler.post(() -> callback.onError("Failed to stat file: " + e.getMessage()));
            }
        });
    }

    /**
     * Get or establish an SFTP connection for the given host.
     */
    private synchronized ChannelSftp getOrConnect(String host, int port) throws Exception {
        String key = host + ":" + port;
        SftpConnection conn = connections.get(key);

        if (conn != null && conn.session.isConnected() && conn.channel.isConnected()) {
            return conn.channel;
        }

        // Clean up stale connection
        if (conn != null) {
            disconnectQuietly(key);
        }

        // Establish new connection
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(hostKeyStore);

        // Load private key from app storage
        SshKeyManager keyManager = new SshKeyManager(context);
        if (!keyManager.hasKeyPair()) {
            throw new Exception("No SSH key generated. Please generate one in Settings.");
        }

        File privateKeyFile = new File(context.getFilesDir(), "ssh_ed25519");
        jsch.addIdentity(privateKeyFile.getAbsolutePath());

        String username = AppConfig.getSshUsername(context);
        if (username.isEmpty()) {
            username = "root";
        }

        Session session = jsch.getSession(username, host, port);
        session.setConfig("StrictHostKeyChecking", "ask");
        session.setConfig("PreferredAuthentications", "publickey");

        // Custom UserInfo to handle host key prompts
        session.setUserInfo(new com.jcraft.jsch.UserInfo() {
            @Override public String getPassphrase() { return null; }
            @Override public String getPassword() { return null; }
            @Override public boolean promptPassword(String message) { return false; }
            @Override public boolean promptPassphrase(String message) { return false; }
            @Override public boolean promptYesNo(String message) {
                // Auto-accept for TOFU — the HostKeyRepository handles verification
                return true;
            }
            @Override public void showMessage(String message) {
                Log.d(TAG, "SSH: " + message);
            }
        });

        session.connect(CONNECT_TIMEOUT_MS);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(CONNECT_TIMEOUT_MS);

        conn = new SftpConnection();
        conn.session = session;
        conn.channel = channel;
        conn.host = host;
        conn.port = port;
        connections.put(key, conn);

        Log.d(TAG, "SFTP connected to " + host + ":" + port);
        return channel;
    }

    private void handleError(String host, int port, String path,
                             ListCallback callback, Exception originalError, int attempt) {
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            mainHandler.post(() -> {
                Toast.makeText(context, R.string.sftp_error, Toast.LENGTH_SHORT).show();
                callback.onError("Connection failed after " + MAX_RECONNECT_ATTEMPTS + " attempts: "
                    + originalError.getMessage());
            });
            return;
        }

        mainHandler.post(() ->
            Toast.makeText(context, R.string.sftp_reconnecting, Toast.LENGTH_SHORT).show()
        );

        // Retry with backoff
        int delay = (int) Math.pow(2, attempt) * 1000;
        mainHandler.postDelayed(() -> {
            executor.execute(() -> {
                try {
                    ChannelSftp channel = getOrConnect(host, port);
                    Vector<?> entries = channel.ls(path);
                    List<SftpFileEntry> result = new ArrayList<>();
                    for (Object obj : entries) {
                        ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) obj;
                        String name = entry.getFilename();
                        if (".".equals(name) || "..".equals(name)) continue;
                        result.add(new SftpFileEntry(
                            name,
                            path.endsWith("/") ? path + name : path + "/" + name,
                            entry.getAttrs().getSize(),
                            entry.getAttrs().getMTime(),
                            entry.getAttrs().isDir(),
                            entry.getAttrs().getPermissions()
                        ));
                    }
                    Collections.sort(result);
                    mainHandler.post(() -> callback.onSuccess(result));
                } catch (Exception e) {
                    String key = host + ":" + port;
                    disconnectQuietly(key);
                    handleError(host, port, path, callback, originalError, attempt + 1);
                }
            });
        }, delay);
    }

    private synchronized void disconnectQuietly(String key) {
        SftpConnection conn = connections.remove(key);
        if (conn != null) {
            try { conn.channel.disconnect(); } catch (Exception ignored) {}
            try { conn.session.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Disconnect all SFTP connections.
     */
    public synchronized void disconnectAll() {
        for (String key : new ArrayList<>(connections.keySet())) {
            disconnectQuietly(key);
        }
    }

    /**
     * Disconnect connection for a specific host.
     */
    public void disconnect(String host, int port) {
        disconnectQuietly(host + ":" + port);
    }

    public void shutdown() {
        disconnectAll();
        executor.shutdown();
    }
}
