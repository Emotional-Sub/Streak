package com.streak.app.util;

import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 使用 PBKDF2WithHmacSHA256 对密码做加盐哈希。
 * salt 与 hash 均以 Base64（NO_WRAP）字符串形式存储在账号文件里。
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private PasswordHasher() {
    }

    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    public static String hash(String password, String saltBase64) {
        byte[] salt = Base64.decode(saltBase64, Base64.NO_WRAP);
        char[] chars = password.toCharArray();
        try {
            KeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * 常量时间比较，避免计时侧信道。
     */
    public static boolean verify(String password, String saltBase64, String expectedHashBase64) {
        if (password == null || saltBase64 == null || expectedHashBase64 == null) {
            return false;
        }
        String actual = hash(password, saltBase64);
        byte[] a = actual.getBytes();
        byte[] b = expectedHashBase64.getBytes();
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
