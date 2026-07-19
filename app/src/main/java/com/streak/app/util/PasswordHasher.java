package com.streak.app.util;

import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.nio.charset.StandardCharsets;
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
        if (password == null
                || !isValidStoredCredential(expectedHashBase64, saltBase64)) {
            return false;
        }
        try {
            String actual = hash(password, saltBase64);
            byte[] actualBytes = actual.getBytes(StandardCharsets.US_ASCII);
            byte[] expectedBytes = expectedHashBase64.getBytes(StandardCharsets.US_ASCII);
            if (actualBytes.length != expectedBytes.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < actualBytes.length; i++) {
                diff |= actualBytes[i] ^ expectedBytes[i];
            }
            return diff == 0;
        } catch (RuntimeException e) {
            // Damaged persisted credentials must behave like a failed login,
            // not crash the authentication path.
            return false;
        }
    }

    public static boolean isValidStoredCredential(String hashBase64, String saltBase64) {
        if (hashBase64 == null || hashBase64.isEmpty()
                || saltBase64 == null || saltBase64.isEmpty()) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(saltBase64, Base64.NO_WRAP);
            byte[] hash = Base64.decode(hashBase64, Base64.NO_WRAP);
            return salt.length == SALT_LENGTH_BYTES
                    && hash.length == KEY_LENGTH_BITS / 8
                    && isCanonicalBase64(saltBase64, salt)
                    && isCanonicalBase64(hashBase64, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCanonicalBase64(String value, byte[] decoded) {
        return Base64.encodeToString(decoded, Base64.NO_WRAP).equals(value);
    }
}
