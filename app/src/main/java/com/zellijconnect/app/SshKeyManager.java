package com.zellijconnect.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class SshKeyManager {

    private static final String TAG = "ZellijConnect";
    private static final String PRIVATE_KEY_FILE = "ssh_ed25519";
    private static final String PUBLIC_KEY_FILE = "ssh_ed25519.pub";

    private final Context context;

    public SshKeyManager(Context context) {
        this.context = context;
    }

    public boolean hasKeyPair() {
        File privFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
        File pubFile = new File(context.getFilesDir(), PUBLIC_KEY_FILE);
        return privFile.exists() && pubFile.exists();
    }

    public void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();

        // Save private key (PKCS8 encoded)
        byte[] privateBytes = keyPair.getPrivate().getEncoded();
        try (FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), PRIVATE_KEY_FILE))) {
            fos.write(privateBytes);
        }

        // Save public key in OpenSSH format
        String publicKeyStr = encodePublicKeyOpenSSH(keyPair);
        try (FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), PUBLIC_KEY_FILE))) {
            fos.write(publicKeyStr.getBytes(StandardCharsets.UTF_8));
        }

        Log.d(TAG, "SSH Ed25519 keypair generated");
    }

    public String getPublicKeyString() {
        try {
            File pubFile = new File(context.getFilesDir(), PUBLIC_KEY_FILE);
            if (!pubFile.exists()) return null;

            byte[] data = new byte[(int) pubFile.length()];
            try (FileInputStream fis = new FileInputStream(pubFile)) {
                fis.read(data);
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read public key", e);
            return null;
        }
    }

    private String encodePublicKeyOpenSSH(KeyPair keyPair) throws Exception {
        // Get the raw Ed25519 public key point
        EdECPublicKey edPub = (EdECPublicKey) keyPair.getPublic();
        EdECPoint point = edPub.getPoint();

        // Convert the affine Y coordinate to 32 bytes (little-endian)
        byte[] yBytes = point.getY().toByteArray();
        byte[] raw = new byte[32];
        // BigInteger is big-endian, we need little-endian
        for (int i = 0; i < Math.min(yBytes.length, 32); i++) {
            raw[i] = yBytes[yBytes.length - 1 - i];
        }
        // Set the high bit of the last byte if x is odd
        if (point.isXOdd()) {
            raw[31] |= (byte) 0x80;
        }

        // Build the OpenSSH wire format: string "ssh-ed25519" + string <32-byte key>
        byte[] keyType = "ssh-ed25519".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + keyType.length + 4 + raw.length);
        buf.putInt(keyType.length);
        buf.put(keyType);
        buf.putInt(raw.length);
        buf.put(raw);

        String encoded = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        return "ssh-ed25519 " + encoded + " zellij-connect@android";
    }
}
