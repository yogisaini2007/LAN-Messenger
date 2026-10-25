
package com.lanmessenger.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordSecurity {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;

    private PasswordSecurity() {}

    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(
                password, salt, ITERATIONS, KEY_LENGTH
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            return factory.generateSecret(spec).getEncoded();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Password hashing failed", e
            );
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean verify(
            char[] password,
            byte[] salt,
            byte[] expectedHash
    ) {
        byte[] actual = hash(password, salt);

        try {
            return MessageDigest.isEqual(actual, expectedHash);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }
}
