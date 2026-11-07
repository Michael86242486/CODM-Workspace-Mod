package com.gotoubun;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores local app config values using Android Keystore-backed AES/GCM when available.
 * Falls back to plain SharedPreferences if hardware-backed/Keystore encryption is unavailable.
 * This protects local settings only; it does not affect game/runtime feature logic.
 */
final class SecureConfigStore {
    private static final String TAG = "CODM-Workspace";
    private static final String PREFS = "config";
    private static final String FALLBACK_PREFS = "config_plain_fallback";
    private static final String ALIAS = "codm_workspace_cfg_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private SecureConfigStore() {}

    static String getString(Context ctx, String key, String def) {
        if (ctx == null || key == null) return def;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String enc = sp.getString("e_" + key, null);
            if (enc == null) {
                // migrate/read legacy plain value if present
                String legacy = sp.getString(key, null);
                if (legacy != null) {
                    putString(ctx, key, legacy);
                    sp.edit().remove(key).apply();
                    return legacy;
                }
                return def;
            }
            String dec = decrypt(ctx, enc);
            return dec != null ? dec : def;
        } catch (Throwable t) {
            Log.w(TAG, "SecureConfigStore read fallback for " + key, t);
            try {
                return ctx.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE).getString(key, def);
            } catch (Throwable ignored) {
                return def;
            }
        }
    }

    static void putString(Context ctx, String key, String value) {
        if (ctx == null || key == null) return;
        String safeValue = value == null ? "" : value;
        try {
            String enc = encrypt(ctx, safeValue);
            if (enc != null) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("e_" + key, enc).apply();
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "SecureConfigStore write fallback for " + key, t);
        }
        try {
            ctx.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE).edit().putString(key, safeValue).apply();
        } catch (Throwable t) {
            Log.w(TAG, "SecureConfigStore plain fallback failed for " + key, t);
        }
    }

    private static String encrypt(Context ctx, String plain) throws Exception {
        SecretKey key = getOrCreateKey();
        if (key == null) return null;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        if (iv == null || iv.length != IV_LEN) return null;
        ByteBuffer bb = ByteBuffer.allocate(1 + iv.length + ct.length);
        bb.put((byte) iv.length);
        bb.put(iv);
        bb.put(ct);
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP);
    }

    private static String decrypt(Context ctx, String payload) throws Exception {
        SecretKey key = getOrCreateKey();
        if (key == null || payload == null) return null;
        byte[] all = Base64.decode(payload, Base64.NO_WRAP);
        ByteBuffer bb = ByteBuffer.wrap(all);
        int ivLen = bb.get() & 0xff;
        if (ivLen <= 0 || ivLen > 32 || ivLen > bb.remaining()) return null;
        byte[] iv = new byte[ivLen];
        bb.get(iv);
        byte[] ct = new byte[bb.remaining()];
        bb.get(ct);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey getOrCreateKey() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            KeyStore.Entry existing = ks.getEntry(ALIAS, null);
            if (existing instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true);
            kg.init(b.build());
            return kg.generateKey();
        } catch (Throwable t) {
            Log.w(TAG, "Keystore key unavailable; using config fallback.", t);
            return null;
        }
    }
}
