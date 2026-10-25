
package com.lanmessenger.database.repository.groups;

import com.lanmessenger.database.DatabaseManager;
import com.lanmessenger.backend.service.groups.GroupModels.Message;

import java.sql.*;
import java.util.*;

/**
 * Independent local group conversation clearing module.
 *
 * Clears:
 * - Local group message history
 * - Local attachment cards
 *
 * Preserves:
 * - Group membership
 * - Group administration
 * - Other members' conversations
 * - Files already saved to disk
 * - Pending outgoing file deliveries
 *
 * Uses tombstones to prevent deleted messages from
 * reappearing when an offline peer reconnects.
 */
public final class GroupConversationClearStore {

    private final String localUserId;

    public GroupConversationClearStore(
            String localUserId
    ) throws SQLException {

        if (localUserId == null ||
                localUserId.isBlank()) {
            throw new IllegalArgumentException(
                    "Local user ID required");
        }

        this.localUserId = localUserId;
        initialize();
    }

    private void initialize() throws SQLException {

        try (Connection connection = DatabaseManager.open();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                CREATE TABLE IF NOT EXISTS
                lm_group_clear_state (
                    local_user TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    cleared_at INTEGER NOT NULL,
                    PRIMARY KEY(local_user, group_id)
                )
                """);
        }
    }

    private static String requireGroup(String groupId) {

        if (groupId == null ||
                groupId.isBlank() ||
                groupId.length() > 200) {

            throw new IllegalArgumentException(
                    "Invalid group ID");
        }

        return groupId;
    }

    /**
     * Clears one complete group conversation locally.
     *
     * One transaction:
     * 1. Saves the local clearing timestamp.
     * 2. Creates tombstones for all existing messages.
     * 3. Deletes local message rows.
     * 4. Hides local group attachment cards.
     *
     * Returns the number of messages removed.
     *
     * Group membership must be verified by the caller.
     */
    public synchronized int clearGroup(
            String groupId
    ) throws SQLException {

        requireGroup(groupId);

        try (Connection connection = DatabaseManager.open()) {

            connection.setAutoCommit(false);

            try {
                long now = System.currentTimeMillis();

                // Record when this user cleared the group.
                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                 INSERT INTO lm_group_clear_state
                                 (local_user, group_id, cleared_at)
                                 VALUES (?, ?, ?)
                                 ON CONFLICT(local_user, group_id)
                                 DO UPDATE SET cleared_at =
                                     MAX(
                                         lm_group_clear_state.cleared_at,
                                         excluded.cleared_at
                                     )
                                 """)) {

                    statement.setString(1, localUserId);
                    statement.setString(2, groupId);
                    statement.setLong(3, now);
                    statement.executeUpdate();
                }

                // Remember message IDs to prevent replay.
                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                 INSERT OR IGNORE INTO gc_hidden
                                 (local_user, id)
                                 SELECT local_user, id
                                 FROM gc_messages
                                 WHERE local_user = ?
                                   AND group_id = ?
                                 """)) {

                    statement.setString(1, localUserId);
                    statement.setString(2, groupId);
                    statement.executeUpdate();
                }

                // Remove the complete local message history.
                int removed;

                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                 DELETE FROM gc_messages
                                 WHERE local_user = ?
                                   AND group_id = ?
                                 """)) {

                    statement.setString(1, localUserId);
                    statement.setString(2, groupId);

                    removed = statement.executeUpdate();
                }

                // Attachments are optional.
                // Don't fail when file sharing is disabled.
                if (tableExists(
                        connection,
                        "lm_attachment_items")) {

                    try (PreparedStatement statement =
                                 connection.prepareStatement("""
                                     UPDATE lm_attachment_items
                                     SET hidden = 1
                                     WHERE local_user = ?
                                       AND chat_type = 'GROUP'
                                       AND chat_id = ?
                                     """)) {

                        statement.setString(1, localUserId);
                        statement.setString(2, groupId);
                        statement.executeUpdate();
                    }
                }

                connection.commit();
                return removed;

            } catch (SQLException | RuntimeException error) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }

                throw error;
            }
        }
    }

    private static boolean tableExists(
            Connection connection,
            String name
    ) throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                         SELECT 1 FROM sqlite_master
                         WHERE type = 'table'
                           AND name = ?
                         LIMIT 1
                         """)) {

            statement.setString(1, name);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Returns the local clear timestamp.
     * Zero means no clearing has happened yet.
     */
    public synchronized long clearedAt(
            String groupId
    ) throws SQLException {

        requireGroup(groupId);

        try (Connection connection = DatabaseManager.open();
             PreparedStatement statement =
                     connection.prepareStatement("""
                         SELECT cleared_at
                         FROM lm_group_clear_state
                         WHERE local_user = ?
                           AND group_id = ?
                         """)) {

            statement.setString(1, localUserId);
            statement.setString(2, groupId);

            try (ResultSet result = statement.executeQuery()) {

                return result.next()
                        ? result.getLong(1)
                        : 0L;
            }
        }
    }

    /**
     * Filters old group messages that may arrive
     * later from an offline computer.
     *
     * New messages after clearing remain visible.
     */
    public synchronized List<Message> visibleHistory(
            String groupId,
            List<Message> messages
    ) throws SQLException {

        requireGroup(groupId);

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        long cutoff = clearedAt(groupId);

        if (cutoff == 0L) {
            return List.copyOf(messages);
        }

        List<Message> visible = new ArrayList<>();

        for (Message message : messages) {

            if (message.timestamp() > cutoff) {
                visible.add(message);
            }
        }

        return List.copyOf(visible);
    }

    /**
     * Used when one new group message event arrives.
     */
    public synchronized boolean isVisible(
            String groupId,
            long timestamp
    ) throws SQLException {

        return timestamp > clearedAt(groupId);
    }
}
