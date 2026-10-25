package com.lanmessenger.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MessengerApi extends AutoCloseable {

    enum MessageStatus {
        PENDING, SENT, DELIVERED, READ
    }

    record Profile(
            String id,
            String username,
            byte[] avatar
    ) {}

    record Contact(
            String id,
            String username,
            byte[] avatar,
            boolean online,
            long lastSeen
    ) {}

    record ChatMessage(
            String id,
            String senderId,
            String receiverId,
            String text,
            long timestamp,
            MessageStatus status,
            boolean deletedForEveryone
    ) {}

    interface Listener {
        default void onContactsChanged(List<Contact> contacts) {}

        default void onMessageChanged(ChatMessage message) {}

        default void onMessageDeleted(String messageId) {}

        default void onError(String operation, Throwable error) {}
    }

    // Local account authentication
    CompletableFuture<Profile> register(
            String username, char[] password, Path avatar
    );

    CompletableFuture<Profile> login(
            String username, char[] password
    );

    // Peer-to-peer networking
    CompletableFuture<Void> startNetworking();

    void addListener(Listener listener);

    void removeListener(Listener listener);

    // Contacts and chat history
    CompletableFuture<List<Contact>> getContacts();

    CompletableFuture<List<ChatMessage>> getHistory(
            String contactId
    );

    // Private messaging
    CompletableFuture<String> sendMessage(
            String contactId, String text
    );

    CompletableFuture<Void> markAsRead(
            String messageId
    );

    // Message deletion
    CompletableFuture<Void> deleteForMe(
            String messageId
    );

    CompletableFuture<Void> deleteForEveryone(
            String messageId
    );

    CompletableFuture<Void> clearConversation(
            String contactId
    );

    // File sharing
    CompletableFuture<Void> sendFile(
            String contactId, Path file
    );

    // Profile picture synchronization
    CompletableFuture<Void> updateAvatar(
            Path image
    );

    @Override
    void close();
}