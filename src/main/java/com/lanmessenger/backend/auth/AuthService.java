
package com.lanmessenger.backend.auth;

import com.lanmessenger.core.MessengerApi.Profile;
import com.lanmessenger.database.DatabaseManager;
import com.lanmessenger.security.PasswordSecurity;
import com.lanmessenger.util.AvatarImages;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Arrays;
import java.util.UUID;

public final class AuthService {

    public Profile register(
            String username,
            char[] password,
            Path avatarPath
    ) throws SQLException, IOException {

        validate(username, password);

        String name = username.trim();
        String id = UUID.randomUUID().toString();

        byte[] salt = PasswordSecurity.generateSalt();
        byte[] hash;

        try {
            hash = PasswordSecurity.hash(password, salt);
        } finally {
            Arrays.fill(password, '\0');
        }

        byte[] avatar = AvatarImages.process(avatarPath);

        try (Connection conn = DatabaseManager.open();
             PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO accounts
                 (id, username, password_salt,
                  password_hash, avatar, created_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """)) {

            ps.setString(1, id);
            ps.setString(2, name);
            ps.setBytes(3, salt);
            ps.setBytes(4, hash);
            ps.setBytes(5, avatar);
            ps.setLong(6, System.currentTimeMillis());

            ps.executeUpdate();

            return new Profile(id, name, avatar.clone());

        } catch (SQLException e) {
            if (e.getErrorCode() == 19) {
                throw new IllegalArgumentException(
                        "Username already registered on this PC"
                );
            }
            throw e;
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    public Profile login(
            String username,
            char[] password
    ) throws SQLException {

        if (username == null || password == null) {
            throw new IllegalArgumentException(
                    "Username and password are required"
            );
        }

        try (Connection conn = DatabaseManager.open();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT id, username, password_salt,
                        password_hash, avatar
                 FROM accounts
                 WHERE username = ? COLLATE NOCASE
                 """)) {

            ps.setString(1, username.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "Invalid username or password"
                    );
                }

                byte[] salt = rs.getBytes("password_salt");
                byte[] hash = rs.getBytes("password_hash");

                if (!PasswordSecurity.verify(
                        password, salt, hash
                )) {
                    throw new IllegalArgumentException(
                            "Invalid username or password"
                    );
                }

                byte[] avatar = rs.getBytes("avatar");

                return new Profile(
                        rs.getString("id"),
                        rs.getString("username"),
                        avatar == null ? new byte[0] : avatar
                );
            }

        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public Profile getProfile(String userId)
            throws SQLException {

        try (Connection conn = DatabaseManager.open();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT id, username, avatar
                 FROM accounts WHERE id = ?
                 """)) {

            ps.setString(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "Account not found"
                    );
                }

                byte[] avatar = rs.getBytes("avatar");

                return new Profile(
                        rs.getString("id"),
                        rs.getString("username"),
                        avatar == null ? new byte[0] : avatar
                );
            }
        }
    }

    public Profile updateAvatar(
            String userId,
            Path imagePath
    ) throws SQLException, IOException {

        byte[] avatar = AvatarImages.process(imagePath);

        try (Connection conn = DatabaseManager.open();
             PreparedStatement ps = conn.prepareStatement("""
                 UPDATE accounts
                 SET avatar = ?
                 WHERE id = ?
                 """)) {

            ps.setBytes(1, avatar);
            ps.setString(2, userId);

            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException(
                        "Account not found"
                );
            }
        }

        return getProfile(userId);
    }

    private static void validate(
            String username,
            char[] password
    ) {
        if (username == null ||
                !username.trim().matches("[a-zA-Z0-9_.-]{3,24}")) {

            throw new IllegalArgumentException(
                    "Username must contain 3-24 letters, " +
                    "numbers, dots, underscores or hyphens"
            );
        }

        if (password == null ||
                password.length < 8 ||
                password.length > 256) {

            throw new IllegalArgumentException(
                    "Password must contain 8-256 characters"
            );
        }
    }
}
