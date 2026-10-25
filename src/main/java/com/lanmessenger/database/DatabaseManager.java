
package com.lanmessenger.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public final class DatabaseManager {

    private static final Path DATA_DIR = createDataPath();
    private static final Path DB_PATH =
            DATA_DIR.resolve("messenger.db");

    private DatabaseManager() {}

    private static Path createDataPath() {
        String appData = System.getenv("APPDATA");

        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "LANMessenger", "data");
        }

        return Path.of(
                System.getProperty("user.home"),
                ".lanmessenger", "data"
        );
    }

    public static Path getDataDirectory() {
        return DATA_DIR;
    }

    public static Connection open() throws SQLException {
        try {
            Files.createDirectories(DATA_DIR);
            Class.forName("org.sqlite.JDBC");

            Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + DB_PATH.toAbsolutePath()
            );

            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA foreign_keys = ON");
            }

            return connection;

        } catch (Exception e) {
            throw new SQLException(
                    "Unable to open LAN Messenger database", e
            );
        }
    }

    public static void initialize() throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {

            st.execute("PRAGMA journal_mode = WAL");

            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    password_salt BLOB NOT NULL,
                    password_hash BLOB NOT NULL,
                    avatar BLOB,
                    created_at INTEGER NOT NULL
                )
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    owner_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    username TEXT NOT NULL,
                    avatar BLOB,
                    last_seen INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(owner_id, peer_id)
                )
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    owner_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    receiver_id TEXT NOT NULL,
                    body TEXT NOT NULL DEFAULT '',
                    sent_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    globally_deleted INTEGER NOT NULL DEFAULT 0,
                    file_name TEXT,
                    file_path TEXT,
                    PRIMARY KEY(owner_id, id)
                )
                """);

            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_messages_history
                ON messages(owner_id, sender_id, receiver_id, sent_at)
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS hidden_messages (
                    owner_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    hidden_at INTEGER NOT NULL,
                    PRIMARY KEY(owner_id, message_id)
                )
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS deletion_events (
                    event_id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_events (
                    event_id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    target_peer_id TEXT NOT NULL,
                    packet_type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0
                )
                """);
        }
    }
}
