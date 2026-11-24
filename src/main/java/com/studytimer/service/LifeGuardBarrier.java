package com.studytimer.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 生命保障障碍的判断逻辑：问题答案匹配、密码哈希。 */
public final class LifeGuardBarrier {

    public static final int COOLDOWN_SECONDS = 60;
    private static final int ITERATIONS = 120_000;

    private LifeGuardBarrier() {
    }

    /** 预设答案为空时输入任意文字即可；否则去掉首尾空白后必须逐字一致。 */
    public static boolean answerMatches(String expected, String input) {
        String typed = input == null ? "" : input.trim();
        if (typed.isEmpty()) {
            return false;
        }
        String want = expected == null ? "" : expected.trim();
        return want.isEmpty() || want.equals(typed);
    }

    /** 设置密码：返回新的 {hash, salt}。 */
    public static String[] hashNewPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltText = Base64.getEncoder().encodeToString(salt);
        return new String[]{hash(password, salt), saltText};
    }

    public static boolean passwordMatches(LifeGuardStore.Settings settings, String password) {
        if (!settings.hasPassword()) {
            return true;
        }
        if (password == null || password.isEmpty()) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(settings.passwordSalt);
            return MessageDigest.isEqual(
                    hash(password, salt).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    settings.passwordHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }
}
