
package com.lanmessenger.database.repository.groups;

import com.lanmessenger.backend.service.groups.GroupModels.Group;
import com.lanmessenger.backend.service.groups.GroupModels.Member;
import com.lanmessenger.backend.service.groups.GroupModels.Message;
import com.lanmessenger.backend.service.groups.GroupModels.Role;
import com.lanmessenger.database.DatabaseManager;

import java.sql.*;
import java.util.*;

public final class GroupRepository {

    private final String localUserId;

    public record Pending(
            String eventId,
            String peerId,
            byte[] payload
    ) {
        public Pending {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public GroupRepository(String localUserId)
            throws SQLException {

        if (localUserId == null ||
                localUserId.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing local user ID");
        }

        this.localUserId = localUserId;
        initialize();
    }

    private void initialize() throws SQLException {

        try (Connection c = DatabaseManager.open();
             Statement s = c.createStatement()) {

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_groups (
                    local_user TEXT NOT NULL,
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    creator TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    avatar BLOB,
                    PRIMARY KEY(local_user, id)
                )
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_members (
                    local_user TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    username TEXT NOT NULL,
                    role TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY(local_user, group_id, peer_id)
                )
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_messages (
                    local_user TEXT NOT NULL,
                    id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sent_at INTEGER NOT NULL,
                    deleted_globally INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(local_user, id)
                )
                """);

            s.execute("""
                CREATE INDEX IF NOT EXISTS gc_history_idx
                ON gc_messages(local_user, group_id, sent_at)
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_hidden (
                    local_user TEXT NOT NULL,
                    id TEXT NOT NULL,
                    PRIMARY KEY(local_user, id)
                )
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_deletions (
                    local_user TEXT NOT NULL,
                    id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    PRIMARY KEY(local_user, id)
                )
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS gc_outbox (
                    local_user TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    frame BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(local_user, event_id, peer_id)
                )
                """);
        }
    }

    public synchronized boolean saveRoster(
            Group group,
            List<Member> members
    ) throws SQLException {

        if (members == null ||
                members.isEmpty() ||
                members.stream().noneMatch(m ->
                        m.userId().equals(group.creatorId())
                        && m.role() == Role.OWNER)) {

            throw new IllegalArgumentException(
                    "Group must include its owner");
        }

        for (Member m : members) {
            if (!m.groupId().equals(group.id())) {
                throw new IllegalArgumentException(
                        "Mismatched group member");
            }
        }

        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);

            try {
                long existing = 0;

                try (PreparedStatement p =
                             c.prepareStatement("""
                                 SELECT revision FROM gc_groups
                                 WHERE local_user=? AND id=?
                                 """)) {

                    p.setString(1, localUserId);
                    p.setString(2, group.id());

                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) {
                            existing = r.getLong(1);
                        }
                    }
                }

                if (existing >= group.revision()) {
                    c.rollback();
                    return false;
                }

                try (PreparedStatement p =
                             c.prepareStatement("""
                                 INSERT INTO gc_groups
                                 (local_user,id,title,creator,
                                  revision,created_at,avatar)
                                 VALUES(?,?,?,?,?,?,?)
                                 ON CONFLICT(local_user,id)
                                 DO UPDATE SET
                                    title=excluded.title,
                                    creator=excluded.creator,
                                    revision=excluded.revision,
                                    avatar=excluded.avatar
                                 """)) {

                    p.setString(1, localUserId);
                    p.setString(2, group.id());
                    p.setString(3, group.name());
                    p.setString(4, group.creatorId());
                    p.setLong(5, group.revision());
                    p.setLong(6, group.createdAt());
                    p.setBytes(7, group.avatar());
                    p.executeUpdate();
                }

                try (PreparedStatement p =
                             c.prepareStatement("""
                                 DELETE FROM gc_members
                                 WHERE local_user=? AND group_id=?
                                 """)) {

                    p.setString(1, localUserId);
                    p.setString(2, group.id());
                    p.executeUpdate();
                }

                try (PreparedStatement p =
                             c.prepareStatement("""
                                 INSERT INTO gc_members
                                 (local_user,group_id,peer_id,
                                  username,role,joined_at)
                                 VALUES(?,?,?,?,?,?)
                                 """)) {

                    for (Member m : members) {
                        p.setString(1, localUserId);
                        p.setString(2, group.id());
                        p.setString(3, m.userId());
                        p.setString(4, m.username());
                        p.setString(5, m.role().name());
                        p.setLong(6, m.joinedAt());
                        p.addBatch();
                    }

                    p.executeBatch();
                }

                c.commit();
                return true;

            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public synchronized Group findGroup(String id)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT * FROM gc_groups
                 WHERE local_user=? AND id=?
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, id);

            try (ResultSet r = p.executeQuery()) {
                return r.next() ? toGroup(r) : null;
            }
        }
    }

    public synchronized List<Group> groups()
            throws SQLException {

        List<Group> result = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT g.* FROM gc_groups g
                 JOIN gc_members m
                   ON m.local_user=g.local_user
                  AND m.group_id=g.id
                  AND m.peer_id=?
                 WHERE g.local_user=?
                 ORDER BY g.title COLLATE NOCASE
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, localUserId);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(toGroup(r));
                }
            }
        }

        return result;
    }

    public synchronized List<Member> members(String groupId)
            throws SQLException {

        List<Member> result = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT * FROM gc_members
                 WHERE local_user=? AND group_id=?
                 ORDER BY role,username COLLATE NOCASE
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, groupId);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(new Member(
                            groupId,
                            r.getString("peer_id"),
                            r.getString("username"),
                            Role.valueOf(r.getString("role")),
                            r.getLong("joined_at")
                    ));
                }
            }
        }

        return result;
    }

    public synchronized void saveMessage(Message m)
            throws SQLException {

        try (Connection c = DatabaseManager.open()) {

            try (PreparedStatement p =
                         c.prepareStatement("""
                             INSERT OR IGNORE INTO gc_messages
                             (local_user,id,group_id,sender_id,
                              body,sent_at,deleted_globally)
                             SELECT ?,?,?,?,?,?,?
                             WHERE NOT EXISTS(
                                 SELECT 1 FROM gc_hidden
                                 WHERE local_user=? AND id=?
                             )
                             """)) {

                p.setString(1, localUserId);
                p.setString(2, m.id());
                p.setString(3, m.groupId());
                p.setString(4, m.senderId());

                p.setString(5,
                        m.deletedForEveryone() ? "" : m.text());

                p.setLong(6, m.timestamp());
                p.setInt(7, m.deletedForEveryone() ? 1 : 0);
                p.setString(8, localUserId);
                p.setString(9, m.id());
                p.executeUpdate();
            }

            try (PreparedStatement p =
                         c.prepareStatement("""
                             UPDATE gc_messages
                             SET body='',deleted_globally=1
                             WHERE local_user=? AND id=?
                               AND EXISTS (
                                 SELECT 1 FROM gc_deletions d
                                 WHERE d.local_user=?
                                   AND d.id=?
                                   AND d.sender_id=
                                       gc_messages.sender_id
                               )
                             """)) {

                p.setString(1, localUserId);
                p.setString(2, m.id());
                p.setString(3, localUserId);
                p.setString(4, m.id());
                p.executeUpdate();
            }
        }
    }

    public synchronized Message findMessage(String id)
            throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT * FROM gc_messages
                 WHERE local_user=? AND id=?
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, id);

            try (ResultSet r = p.executeQuery()) {
                return r.next() ? toMessage(r) : null;
            }
        }
    }

    public synchronized List<Message> history(String groupId)
            throws SQLException {

        List<Message> result = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT * FROM gc_messages
                 WHERE local_user=? AND group_id=?
                 ORDER BY sent_at,id
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, groupId);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(toMessage(r));
                }
            }
        }

        return result;
    }

    // Delete for Me:
    // Remove local content but remember the ID,
    // preventing duplicate network events restoring it.

    public synchronized void deleteForMe(String messageId)
            throws SQLException {

        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);

            try (PreparedStatement hidden =
                         c.prepareStatement("""
                             INSERT OR IGNORE INTO gc_hidden
                             (local_user,id) VALUES(?,?)
                             """);
                 PreparedStatement erase =
                         c.prepareStatement("""
                             DELETE FROM gc_messages
                             WHERE local_user=? AND id=?
                             """)) {

                hidden.setString(1, localUserId);
                hidden.setString(2, messageId);
                hidden.executeUpdate();

                erase.setString(1, localUserId);
                erase.setString(2, messageId);
                erase.executeUpdate();

                c.commit();

            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // Delete for Everyone:
    // Clear stored text and retain a tombstone
    // so late deliveries cannot recreate that text.

    public synchronized void deleteForEveryone(
            String groupId,
            String messageId,
            String originalSenderId
    ) throws SQLException {

        Message existing = findMessage(messageId);

        if (existing != null &&
                (!existing.groupId().equals(groupId) ||
                 !existing.senderId().equals(originalSenderId))) {

            throw new SecurityException(
                    "Only the sender may delete this message");
        }

        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);

            try (PreparedStatement tombstone =
                         c.prepareStatement("""
                             INSERT OR IGNORE INTO gc_deletions
                             (local_user,id,group_id,sender_id)
                             VALUES(?,?,?,?)
                             """);
                 PreparedStatement erase =
                         c.prepareStatement("""
                             UPDATE gc_messages
                             SET body='',deleted_globally=1
                             WHERE local_user=? AND id=?
                               AND group_id=? AND sender_id=?
                             """)) {

                tombstone.setString(1, localUserId);
                tombstone.setString(2, messageId);
                tombstone.setString(3, groupId);
                tombstone.setString(4, originalSenderId);
                tombstone.executeUpdate();

                erase.setString(1, localUserId);
                erase.setString(2, messageId);
                erase.setString(3, groupId);
                erase.setString(4, originalSenderId);
                erase.executeUpdate();

                c.commit();

            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // Independent outgoing queue for offline members.

    public synchronized void queue(
            String eventId,
            String peerId,
            byte[] frame
    ) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 INSERT OR IGNORE INTO gc_outbox
                 (local_user,event_id,peer_id,frame,created_at)
                 VALUES(?,?,?,?,?)
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, eventId);
            p.setString(3, peerId);
            p.setBytes(4, frame);
            p.setLong(5, System.currentTimeMillis());
            p.executeUpdate();
        }
    }

    public synchronized List<Pending> pending(String peerId)
            throws SQLException {

        List<Pending> result = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 SELECT event_id,frame FROM gc_outbox
                 WHERE local_user=? AND peer_id=?
                 ORDER BY rowid LIMIT 500
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, peerId);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(new Pending(
                            r.getString(1),
                            peerId,
                            r.getBytes(2)
                    ));
                }
            }
        }

        return result;
    }

    public synchronized void acknowledge(
            String eventId,
            String peerId
    ) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                 DELETE FROM gc_outbox
                 WHERE local_user=? AND event_id=? AND peer_id=?
                 """)) {

            p.setString(1, localUserId);
            p.setString(2, eventId);
            p.setString(3, peerId);
            p.executeUpdate();
        }
    }

    private static Group toGroup(ResultSet r)
            throws SQLException {

        return new Group(
                r.getString("id"),
                r.getString("title"),
                r.getString("creator"),
                r.getLong("revision"),
                r.getLong("created_at"),
                r.getBytes("avatar")
        );
    }

    private static Message toMessage(ResultSet r)
            throws SQLException {

        return new Message(
                r.getString("id"),
                r.getString("group_id"),
                r.getString("sender_id"),
                r.getString("body"),
                r.getLong("sent_at"),
                r.getInt("deleted_globally") != 0
        );
    }
}
