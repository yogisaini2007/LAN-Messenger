
package com.lanmessenger.backend.service.attachments;

import com.lanmessenger.database.DatabaseManager;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Independent attachment metadata storage.
 *
 * Supports:
 * - Private and group file metadata
 * - Persistent local file copies
 * - Offline recipient queues
 * - Attachment lookup for chat UI
 * - Local deletion and conversation clearing
 *
 * Does not modify existing private/group message tables.
 */
public final class AttachmentRepository {

    public record Attachment(
            String id,
            String chatType,
            String chatId,
            String senderId,
            String filename,
            String mimeType,
            long size,
            String localPath,
            long createdAt,
            String status
    ) {}

    private final String userId;
    private final Path cacheRoot;

    public AttachmentRepository(String localUserId)
            throws SQLException {

        if (localUserId == null || localUserId.isBlank()) {
            throw new IllegalArgumentException(
                    "Local user ID required");
        }

        this.userId = localUserId;

        String appData = System.getenv("APPDATA");

        Path base = (appData == null || appData.isBlank())
                ? Path.of(
                        System.getProperty("user.home"),
                        ".lanmessenger")
                : Path.of(appData, "LANMessenger");

        String safeUser = UUID.nameUUIDFromBytes(
                localUserId.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))
                .toString();

        this.cacheRoot = base.resolve("attachment-cache")
                .resolve(safeUser);

        initialize();
    }

    private void initialize() throws SQLException {

        try (Connection c = DatabaseManager.open();
             Statement s = c.createStatement()) {

            s.execute("""
                CREATE TABLE IF NOT EXISTS lm_attachment_items (
                    local_user TEXT NOT NULL,
                    id TEXT NOT NULL,
                    chat_type TEXT NOT NULL,
                    chat_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    local_path TEXT,
                    created_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    hidden INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(local_user, id)
                )
                """);

            s.execute("""
                CREATE INDEX IF NOT EXISTS lm_attachment_chat_idx
                ON lm_attachment_items(
                    local_user,
                    chat_type,
                    chat_id,
                    created_at
                )
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS lm_attachment_queue (
                    local_user TEXT NOT NULL,
                    attachment_id TEXT NOT NULL,
                    recipient_id TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'QUEUED',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(
                        local_user,
                        attachment_id,
                        recipient_id
                    )
                )
                """);
        }
    }

    private static String require(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " required");
        }

        return value;
    }

    private static String chatType(String type) {

        if (!"PRIVATE".equals(type)
                && !"GROUP".equals(type)) {

            throw new IllegalArgumentException(
                    "Type must be PRIVATE or GROUP");
        }

        return type;
    }

    private static String safeName(String name) {

        require(name, "File name");

        if (name.length() > 255
                || name.contains("/")
                || name.contains("\\")
                || name.indexOf('\0') >= 0
                || name.equals(".")
                || name.equals("..")) {

            throw new IllegalArgumentException(
                    "Invalid file name");
        }

        return name;
    }

    /**
     * Copies the source file into app data.
     * The cached copy remains available for offline peers
     * even if the original file is moved.
     */
    public synchronized Attachment prepareOutgoing(
            Path source,
            String type,
            String chatId,
            String senderId,
            Collection<String> recipientIds
    ) throws IOException, SQLException {

        Objects.requireNonNull(source, "source");

        chatType(type);
        require(chatId, "Chat ID");
        require(senderId, "Sender ID");

        if (recipientIds == null || recipientIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one recipient required");
        }

        if (!Files.isRegularFile(source)) {
            throw new IOException("Source file not found");
        }

        String filename = safeName(
                source.getFileName().toString());

        String id = UUID.randomUUID().toString();

        Path destination = cacheRoot
                .resolve(id)
                .resolve(filename);

        Files.createDirectories(
                destination.getParent());

        Files.copy(
                source,
                destination,
                StandardCopyOption.COPY_ATTRIBUTES);

        String mime = Files.probeContentType(source);

        if (mime == null) {
            mime = "application/octet-stream";
        }

        Attachment item = new Attachment(
                id,
                type,
                chatId,
                senderId,
                filename,
                mime,
                Files.size(destination),
                destination.toString(),
                System.currentTimeMillis(),
                "QUEUED"
        );

        try {
            save(item);

            try (Connection c = DatabaseManager.open()) {
                c.setAutoCommit(false);

                try (PreparedStatement p =
                             c.prepareStatement("""
                                 INSERT OR IGNORE INTO
                                 lm_attachment_queue
                                 (local_user,attachment_id,recipient_id)
                                 VALUES(?,?,?)
                                 """)) {

                    Set<String> unique =
                            new LinkedHashSet<>(recipientIds);

                    for (String recipient : unique) {

                        require(recipient, "Recipient ID");

                        if (recipient.equals(senderId)) {
                            continue;
                        }

                        p.setString(1, userId);
                        p.setString(2, id);
                        p.setString(3, recipient);

                        p.addBatch();
                    }

                    p.executeBatch();
                    c.commit();

                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }

        } catch (Exception e) {

            try (Connection c = DatabaseManager.open();
                 PreparedStatement p =
                         c.prepareStatement("""
                             DELETE FROM lm_attachment_items
                             WHERE local_user=? AND id=?
                             """)) {

                p.setString(1, userId);
                p.setString(2, id);
                p.executeUpdate();

            } catch (SQLException cleanupError) {
                e.addSuppressed(cleanupError);
            }

            Files.deleteIfExists(destination);
            Files.deleteIfExists(destination.getParent());

            if (e instanceof SQLException sql) {
                throw sql;
            }

            if (e instanceof IOException io) {
                throw io;
            }

            throw new IOException(
                    "Could not queue attachment", e);
        }

        return item;
    }

    /**
     * Store metadata for a file received through LAN transfer.
     */
    public synchronized void saveIncoming(Attachment a)
            throws SQLException {

        Objects.requireNonNull(a);

        chatType(a.chatType());
        require(a.id(), "Attachment ID");
        require(a.chatId(), "Chat ID");
        require(a.senderId(), "Sender ID");
        safeName(a.filename());

        if (a.size() < 0) {
            throw new IllegalArgumentException("Invalid size");
        }

        save(a);
    }

    private void save(Attachment a) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         INSERT OR IGNORE INTO lm_attachment_items
                         (local_user,id,chat_type,chat_id,sender_id,
                          filename,mime_type,size_bytes,local_path,
                          created_at,status)
                         VALUES(?,?,?,?,?,?,?,?,?,?,?)
                         """)) {

            p.setString(1, userId);
            p.setString(2, a.id());
            p.setString(3, a.chatType());
            p.setString(4, a.chatId());
            p.setString(5, a.senderId());
            p.setString(6, a.filename());
            p.setString(7, a.mimeType());
            p.setLong(8, a.size());
            p.setString(9, a.localPath());
            p.setLong(10, a.createdAt());
            p.setString(11, a.status());

            p.executeUpdate();
        }
    }

    /**
     * Update local path once a received file exists on disk.
     */
    public synchronized void markFileAvailable(
            String id,
            Path localFile
    ) throws SQLException, IOException {

        if (localFile == null
                || !Files.isRegularFile(localFile)) {

            throw new IOException("Received file is missing");
        }

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         UPDATE lm_attachment_items
                         SET local_path=?,status='RECEIVED'
                         WHERE local_user=? AND id=?
                         """)) {

            p.setString(
                    1,
                    localFile.toAbsolutePath()
                            .normalize()
                            .toString());

            p.setString(2, userId);
            p.setString(3, require(id, "Attachment ID"));

            p.executeUpdate();
        }
    }

    public synchronized Optional<Attachment> find(String id)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         SELECT * FROM lm_attachment_items
                         WHERE local_user=? AND id=? AND hidden=0
                         """)) {

            p.setString(1, userId);
            p.setString(2, require(id, "Attachment ID"));

            try (ResultSet r = p.executeQuery()) {

                return r.next()
                        ? Optional.of(read(r))
                        : Optional.empty();
            }
        }
    }

    /**
     * Retrieve file cards for a specific chat.
     */
    public synchronized List<Attachment> forChat(
            String type,
            String chatId
    ) throws SQLException {

        List<Attachment> items = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         SELECT * FROM lm_attachment_items
                         WHERE local_user=?
                           AND chat_type=?
                           AND chat_id=?
                           AND hidden=0
                         ORDER BY created_at,id
                         """)) {

            p.setString(1, userId);
            p.setString(2, chatType(type));
            p.setString(3, require(chatId, "Chat ID"));

            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    items.add(read(rs));
                }
            }
        }

        return items;
    }

    /**
     * Returns queued files for an offline/reconnected peer.
     */
    public synchronized List<Attachment> pendingFor(
            String recipientId
    ) throws SQLException {

        List<Attachment> items = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         SELECT a.*
                         FROM lm_attachment_items a
                         JOIN lm_attachment_queue q
                           ON q.local_user=a.local_user
                          AND q.attachment_id=a.id
                         WHERE a.local_user=?
                           AND q.recipient_id=?
                           AND q.state='QUEUED'
                         ORDER BY a.created_at,a.id
                         """)) {

            p.setString(1, userId);
            p.setString(
                    2,
                    require(recipientId, "Recipient ID"));

            try (ResultSet rs = p.executeQuery()) {

                while (rs.next()) {
                    items.add(read(rs));
                }
            }
        }

        return items;
    }

    public synchronized void markDelivered(
            String fileId,
            String recipientId
    ) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         UPDATE lm_attachment_queue
                         SET state='DELIVERED',
                             last_error=''
                         WHERE local_user=?
                           AND attachment_id=?
                           AND recipient_id=?
                         """)) {

            p.setString(1, userId);
            p.setString(2, require(fileId, "File ID"));
            p.setString(
                    3,
                    require(recipientId, "Recipient ID"));

            p.executeUpdate();
        }
    }

    public synchronized void recordAttempt(
            String fileId,
            String recipientId,
            String error
    ) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         UPDATE lm_attachment_queue
                         SET attempts=attempts+1,
                             last_error=?
                         WHERE local_user=?
                           AND attachment_id=?
                           AND recipient_id=?
                         """)) {

            String description = error == null
                    ? ""
                    : error.substring(
                            0,
                            Math.min(200, error.length()));

            p.setString(1, description);
            p.setString(2, userId);
            p.setString(3, require(fileId, "File ID"));
            p.setString(
                    4,
                    require(recipientId, "Recipient ID"));

            p.executeUpdate();
        }
    }

    /**
     * Hide attachment from local chat.
     * Does not delete the recipient's copy.
     */
    public synchronized void hideForMe(String fileId)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         UPDATE lm_attachment_items
                         SET hidden=1
                         WHERE local_user=? AND id=?
                         """)) {

            p.setString(1, userId);
            p.setString(2, require(fileId, "File ID"));

            p.executeUpdate();
        }
    }

    /**
     * Hide all attachment cards from one chat.
     * Pending outgoing transfers are kept.
     */
    public synchronized void clearChat(
            String type,
            String chatId
    ) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p =
                     c.prepareStatement("""
                         UPDATE lm_attachment_items
                         SET hidden=1
                         WHERE local_user=?
                           AND chat_type=?
                           AND chat_id=?
                         """)) {

            p.setString(1, userId);
            p.setString(2, chatType(type));
            p.setString(3, require(chatId, "Chat ID"));

            p.executeUpdate();
        }
    }

    private static Attachment read(ResultSet r)
            throws SQLException {

        return new Attachment(
                r.getString("id"),
                r.getString("chat_type"),
                r.getString("chat_id"),
                r.getString("sender_id"),
                r.getString("filename"),
                r.getString("mime_type"),
                r.getLong("size_bytes"),
                r.getString("local_path"),
                r.getLong("created_at"),
                r.getString("status")
        );
    }
}
