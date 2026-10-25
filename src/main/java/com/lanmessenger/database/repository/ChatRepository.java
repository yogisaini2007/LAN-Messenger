
package com.lanmessenger.database.repository;

import com.lanmessenger.backend.protocol.WirePacket;
import com.lanmessenger.core.MessengerApi.ChatMessage;
import com.lanmessenger.core.MessengerApi.MessageStatus;
import com.lanmessenger.database.DatabaseManager;
import java.io.*;
import java.sql.*;
import java.util.*;

public final class ChatRepository {
    private final String owner;

    public record Pending(String peerId, WirePacket packet) {}

    public ChatRepository(String ownerId) {
        if (ownerId == null || ownerId.isBlank())
            throw new IllegalArgumentException("Missing account ID");
        owner = ownerId;
    }

    public synchronized void save(ChatMessage message) throws SQLException {
        try (Connection c = DatabaseManager.open()) {
            boolean tombstoned;
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT 1 FROM deletion_events WHERE owner_id=? AND message_id=? LIMIT 1")) {
                q.setString(1, owner);
                q.setString(2, message.id());
                try (ResultSet r = q.executeQuery()) {
                    tombstoned = r.next();
                }
            }

            try (PreparedStatement p = c.prepareStatement("""
                    INSERT OR IGNORE INTO messages
                    (owner_id,id,sender_id,receiver_id,body,sent_at,status,globally_deleted)
                    VALUES (?,?,?,?,?,?,?,?)
                    """)) {

                p.setString(1, owner);
                p.setString(2, message.id());
                p.setString(3, message.senderId());
                p.setString(4, message.receiverId());
                p.setString(5,
                        tombstoned || message.deletedForEveryone()
                                ? "" : message.text());
                p.setLong(6, message.timestamp());
                p.setString(7, message.status().name());
                p.setInt(8,
                        tombstoned || message.deletedForEveryone()
                                ? 1 : 0);
                p.executeUpdate();
            }
        }
    }

    public synchronized ChatMessage find(String id)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement(
                     "SELECT * FROM messages WHERE owner_id=? AND id=?")) {

            p.setString(1, owner);
            p.setString(2, id);

            try (ResultSet r = p.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    public synchronized List<ChatMessage> history(String contactId)
            throws SQLException {

        List<ChatMessage> result = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     SELECT m.* FROM messages m
                     LEFT JOIN hidden_messages h
                       ON h.owner_id=m.owner_id AND h.message_id=m.id
                     WHERE m.owner_id=? AND h.message_id IS NULL
                       AND ((m.sender_id=? AND m.receiver_id=?)
                         OR (m.sender_id=? AND m.receiver_id=?))
                     ORDER BY m.sent_at, m.id
                     """)) {

            p.setString(1, owner);
            p.setString(2, owner);
            p.setString(3, contactId);
            p.setString(4, contactId);
            p.setString(5, owner);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(map(r));
                }
            }
        }
        return result;
    }

    public synchronized ChatMessage setStatus(
            String id, MessageStatus status) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     UPDATE messages SET status=?
                     WHERE owner_id=? AND id=? AND sender_id=?
                       AND (CASE status
                             WHEN 'PENDING' THEN 0
                             WHEN 'SENT' THEN 1
                             WHEN 'DELIVERED' THEN 2
                             WHEN 'READ' THEN 3
                             ELSE -1 END) < ?
                     """)) {

            p.setString(1, status.name());
            p.setString(2, owner);
            p.setString(3, id);
            p.setString(4, owner);
            p.setInt(5, status.ordinal());

            return p.executeUpdate() > 0 ? find(id) : null;
        }
    }

    public synchronized ChatMessage markIncomingRead(String id)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     UPDATE messages SET status='READ'
                     WHERE owner_id=? AND id=? AND receiver_id=?
                       AND status <> 'READ' AND globally_deleted=0
                     """)) {

            p.setString(1, owner);
            p.setString(2, id);
            p.setString(3, owner);

            return p.executeUpdate() > 0 ? find(id) : null;
        }
    }

    public synchronized void hide(String id) throws SQLException {
        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     INSERT OR IGNORE INTO hidden_messages
                     (owner_id,message_id,hidden_at)
                     SELECT owner_id,id,? FROM messages
                     WHERE owner_id=? AND id=?
                     """)) {

            p.setLong(1, System.currentTimeMillis());
            p.setString(2, owner);
            p.setString(3, id);
            p.executeUpdate();
        }
    }

    public synchronized void hideConversation(String contactId)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     INSERT OR IGNORE INTO hidden_messages
                     (owner_id,message_id,hidden_at)
                     SELECT owner_id,id,? FROM messages
                     WHERE owner_id=? AND
                       ((sender_id=? AND receiver_id=?)
                       OR (sender_id=? AND receiver_id=?))
                     """)) {

            p.setLong(1, System.currentTimeMillis());
            p.setString(2, owner);
            p.setString(3, owner);
            p.setString(4, contactId);
            p.setString(5, contactId);
            p.setString(6, owner);
            p.executeUpdate();
        }
    }

    public synchronized void deleteGlobally(
            String messageId,
            String authorId,
            String eventId) throws SQLException {

        ChatMessage existing = find(messageId);

        if (existing != null &&
                !existing.senderId().equals(authorId)) {
            throw new SecurityException(
                    "Only the author can delete for everyone");
        }

        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);

            try (PreparedStatement event = c.prepareStatement("""
                    INSERT OR IGNORE INTO deletion_events
                    (event_id,owner_id,message_id,actor_id,created_at)
                    VALUES (?,?,?,?,?)
                    """);
                 PreparedStatement change = c.prepareStatement("""
                    UPDATE messages
                    SET body='',globally_deleted=1
                    WHERE owner_id=? AND id=? AND sender_id=?
                    """)) {

                event.setString(1, eventId);
                event.setString(2, owner);
                event.setString(3, messageId);
                event.setString(4, authorId);
                event.setLong(5, System.currentTimeMillis());
                event.executeUpdate();

                change.setString(1, owner);
                change.setString(2, messageId);
                change.setString(3, authorId);
                change.executeUpdate();

                c.commit();

            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public synchronized void queue(
            String peerId, WirePacket packet) throws SQLException {

        byte[] bytes;

        try (ByteArrayOutputStream b =
                     new ByteArrayOutputStream();
             DataOutputStream out =
                     new DataOutputStream(b)) {

            packet.write(out);
            out.flush();
            bytes = b.toByteArray();

        } catch (IOException e) {
            throw new SQLException(
                    "Cannot serialize event", e);
        }

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     INSERT OR IGNORE INTO pending_events
                     (event_id,owner_id,target_peer_id,
                      packet_type,payload,created_at)
                     VALUES (?,?,?,?,?,?)
                     """)) {

            p.setString(1, packet.id());
            p.setString(2, owner);
            p.setString(3, peerId);
            p.setString(4, packet.type());
            p.setString(5,
                    Base64.getEncoder().encodeToString(bytes));
            p.setLong(6, System.currentTimeMillis());
            p.executeUpdate();
        }
    }

    public synchronized List<Pending> pending(String peerId)
            throws SQLException {

        List<Pending> results = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     SELECT payload FROM pending_events
                     WHERE owner_id=? AND target_peer_id=?
                     ORDER BY created_at,event_id LIMIT 500
                     """)) {

            p.setString(1, owner);
            p.setString(2, peerId);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        byte[] bytes = Base64.getDecoder()
                                .decode(r.getString(1));

                        WirePacket packet = WirePacket.read(
                                new DataInputStream(
                                        new ByteArrayInputStream(bytes)));

                        results.add(new Pending(peerId, packet));

                    } catch (IOException |
                             IllegalArgumentException e) {
                        throw new SQLException(
                                "Corrupt queued event", e);
                    }
                }
            }
        }

        return results;
    }

    public synchronized void dropPending(String eventId)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                     DELETE FROM pending_events
                     WHERE owner_id=? AND event_id=?
                     """)) {

            p.setString(1, owner);
            p.setString(2, eventId);
            p.executeUpdate();
        }
    }

    private static ChatMessage map(ResultSet r) throws SQLException {
        return new ChatMessage(
                r.getString("id"),
                r.getString("sender_id"),
                r.getString("receiver_id"),
                r.getString("body"),
                r.getLong("sent_at"),
                MessageStatus.valueOf(r.getString("status")),
                r.getInt("globally_deleted") != 0
        );
    }
}
