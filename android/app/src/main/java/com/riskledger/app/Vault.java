package com.riskledger.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * API keys are encrypted with a key that never leaves the phone's Android Keystore,
 * stored outside Android backups, and never handed back to the page.
 */
final class Vault {
    private static SecretKey vaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("rl_api")) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("rl_api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry("rl_api", null)).getSecretKey();
    }

    static File file(Context ctx, String ex) { return new File(ctx.getNoBackupFilesDir(), "api_" + ex.replaceAll("[^a-z]", "") + ".bin"); }

    static void write(Context ctx, String ex, String key, String secret) throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", key);
        o.put("s", secret);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, vaultKey());
        byte[] ct = c.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
        String out = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        try (FileOutputStream f = new FileOutputStream(file(ctx, ex))) { f.write(out.getBytes(StandardCharsets.UTF_8)); }
    }

    static String[] read(Context ctx, String ex) {
        File f = file(ctx, ex);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            String[] parts = Net.readAll(in).split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, vaultKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject o = new JSONObject(new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return new String[]{o.getString("k"), o.getString("s")};
        } catch (Exception e) { return null; }
    }

    static void remove(Context ctx, String ex) { file(ctx, ex).delete(); }
}
