package com.jijian.translate;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePrefs {
    final SharedPreferences prefs;
    private final String alias;
    SecurePrefs(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        alias = context.getPackageName() + ".deepseek.aes";
    }
    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    synchronized String loadKey() throws Exception {
        String saved = prefs.getString("key.enc", ""); if (saved.isEmpty()) return "";
        String[] parts = saved.split(":"); if (parts.length != 2) throw new Exception("密钥无法读取，请重新填写。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    synchronized void saveKey(String key) throws Exception {
        if (key.trim().isEmpty()) { if (!prefs.edit().remove("key.enc").commit()) throw new Exception("密钥未能清除。"); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(key.trim().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("key.enc", encoded).commit()) throw new Exception("密钥未能保存。");
    }
    double budget() { try { return Double.parseDouble(prefs.getString("budget", "15")); } catch (Exception e) { return 15; } }
    String source() { return prefs.getString("source", "自动识别"); }
    String target() { return prefs.getString("target", "简体中文"); }
    int textSize() { return prefs.getInt("textSize", 17); }
    boolean autoSpeak() { return prefs.getBoolean("autoSpeak", false); }
    int speechRate() { int value=prefs.getInt("speechRate",0);return value==-20||value==20?value:0; }
}
