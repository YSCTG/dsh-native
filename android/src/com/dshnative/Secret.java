package com.dshnative;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API key 的落盘保护。
 *
 * 密钥生成在 AndroidKeyStore 里（不进 app 数据目录、不可导出），
 * 用它做 AES-256-GCM 加密，密文存 SharedPreferences。
 *
 * 没有 AndroidX 的 security-crypto 可用（本项目零依赖），所以直接用平台 KeyStore + JCE，
 * 格式自己定：base64(iv) + ":" + base64(ciphertext)。
 *
 * 降级策略：Keystore 建不出来（个别设备/模拟器）才退回明文，并让 UI 标出来 ——
 * 宁可让人看见"没加密"，也不要假装加密了。
 */
public final class Secret {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dshnative_secret_v1";
    /** 旧别名。同一 App 里还能解出来，所以迁移时用它读一次，再用新别名重加密。 */
    private static final String PREF_ENC = "api_key_enc";
    private static final String PREF_PLAIN = "api_key";

    private Secret() {}

    public static boolean hasKey(SharedPreferences p) {
        return p.contains(PREF_ENC) || !p.getString(PREF_PLAIN, "").isEmpty();
    }

    /** 当前是否真的以密文形式保存。 */
    public static boolean isEncrypted(SharedPreferences p) {
        return p.contains(PREF_ENC);
    }

    public static void put(SharedPreferences p, String value) {
        String enc = encrypt(value);
        SharedPreferences.Editor e = p.edit();
        if (enc != null) {
            e.putString(PREF_ENC, enc);
            e.remove(PREF_PLAIN);
        } else {
            e.putString(PREF_PLAIN, value);
            e.remove(PREF_ENC);
        }
        e.apply();
    }

    public static String get(SharedPreferences p) {
        String enc = p.getString(PREF_ENC, null);
        if (enc != null) {
            String v = decrypt(enc);
            if (v != null) return v;
        }
        return p.getString(PREF_PLAIN, "");
    }

    /** 老版本把 key 明文存着，第一次启动时就地加密并删掉明文。 */
    public static void migrate(SharedPreferences p) {
        String plain = p.getString(PREF_PLAIN, "");
        if (plain.isEmpty() || p.contains(PREF_ENC)) return;
        put(p, plain);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decrypt(String blob) {
        try {
            int idx = blob.indexOf(':');
            if (idx <= 0) return null;
            byte[] iv = Base64.decode(blob.substring(0, idx), Base64.NO_WRAP);
            byte[] ct = Base64.decode(blob.substring(idx + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
