
package com.lanmessenger.backend.service.messaging;

import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.protocol.WirePacket;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.core.MessengerApi.ChatMessage;
import com.lanmessenger.core.MessengerApi.MessageStatus;
import com.lanmessenger.database.repository.ChatRepository;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public final class MessagingService
        implements AutoCloseable, PeerNetwork.Listener {

    public interface Listener {

        default void onMessageChanged(ChatMessage message) {}

        default void onMessageHidden(String messageId) {}

        default void onConversationCleared(String peerId) {}

        default void onPeerOnline(PeerInfo peer) {}

        default void onPeerOffline(String peerId) {}

        default void onError(String action, Throwable error) {}
    }

    private final String accountId;
    private final PeerNetwork network;
    private final ChatRepository repository;

    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private final Set<String> flushing =
            ConcurrentHashMap.newKeySet();

    private final ExecutorService io =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lan-messaging");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService retry =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lan-message-retry");
                t.setDaemon(true);
                return t;
            });

    public MessagingService(
            String accountId, PeerNetwork network) {

        this.accountId = Objects.requireNonNull(accountId);
        this.network = Objects.requireNonNull(network);
        this.repository = new ChatRepository(accountId);

        network.setPacketHandler(this::receive);
        network.addListener(this);

        retry.scheduleWithFixedDelay(
                this::retrySafely, 4, 5, TimeUnit.SECONDS);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<String> sendMessage(
            String peerId, String text) {

        return CompletableFuture.supplyAsync(() -> {

            if (peerId == null ||
                    peerId.isBlank() ||
                    peerId.equals(accountId)) {
                throw new IllegalArgumentException(
                        "Choose another user");
            }

            if (text == null ||
                    text.isBlank() ||
                    text.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException(
                        "Message must contain 1-16384 bytes");
            }

            WirePacket packet = WirePacket.text(
                    "CHAT", accountId, peerId, text);

            ChatMessage message = new ChatMessage(
                    packet.id(),
                    accountId,
                    peerId,
                    text,
                    packet.timestamp(),
                    MessageStatus.PENDING,
                    false
            );

            try {
                repository.save(message);
                repository.queue(peerId, packet);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }

            changed(message);
            flushLater(peerId);

            return packet.id();

        }, io);
    }

    public CompletableFuture<List<ChatMessage>> getHistory(
            String peerId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.history(peerId);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, io);
    }

    public CompletableFuture<Void> deleteForMe(
            String messageId) {

        return CompletableFuture.runAsync(() -> {

            try {
                repository.hide(messageId);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }

            fire(l -> l.onMessageHidden(messageId));

        }, io);
    }

    public CompletableFuture<Void> clearConversation(
            String peerId) {

        return CompletableFuture.runAsync(() -> {

            try {
                repository.hideConversation(peerId);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }

            fire(l -> l.onConversationCleared(peerId));

        }, io);
    }

    public CompletableFuture<Void> deleteForEveryone(
            String messageId) {

        return CompletableFuture.runAsync(() -> {

            try {
                ChatMessage old = repository.find(messageId);

                if (old == null ||
                        !old.senderId().equals(accountId)) {
                    throw new IllegalArgumentException(
                            "You can only delete your own sent message for everyone");
                }

                if (old.deletedForEveryone()) {
                    return;
                }

                WirePacket deletion = WirePacket.text(
                        "DELETE_GLOBAL",
                        accountId,
                        old.receiverId(),
                        messageId
                );

                repository.deleteGlobally(
                        messageId,
                        accountId,
                        deletion.id()
                );

                // Remove any unsent copy of the original message.
                repository.dropPending(messageId);

                repository.queue(
                        old.receiverId(), deletion);

                ChatMessage updated =
                        repository.find(messageId);

                if (updated != null) {
                    changed(updated);
                }

                flushLater(old.receiverId());

            } catch (SQLException e) {
                throw new CompletionException(e);
            }

        }, io);
    }

    public CompletableFuture<Void> markAsRead(
            String messageId) {

        return CompletableFuture.runAsync(() -> {

            try {
                ChatMessage message =
                        repository.markIncomingRead(messageId);

                if (message == null) {
                    return;
                }

                changed(message);

                WirePacket receipt = WirePacket.text(
                        "READ",
                        accountId,
                        message.senderId(),
                        messageId
                );

                repository.queue(
                        message.senderId(), receipt);

                flushLater(message.senderId());

            } catch (SQLException e) {
                throw new CompletionException(e);
            }

        }, io);
    }

    private void receive(
            WirePacket packet,
            InetAddress remote) throws Exception {

        if (!packet.recipientId().equals(accountId)) {
            throw new SecurityException(
                    "Message for another account");
        }

        boolean recognized = network.getPeers()
                .stream()
                .anyMatch(p ->
                        p.id().equals(packet.senderId())
                        && p.address().equals(remote));

        if (!recognized) {
            throw new SecurityException(
                    "Unknown peer or address");
        }

        switch (packet.type()) {

            case "CHAT" -> {

                if (packet.payload().length > 16384) {
                    throw new IllegalArgumentException(
                            "Message too large");
                }

                ChatMessage message = new ChatMessage(
                        packet.id(),
                        packet.senderId(),
                        accountId,
                        packet.text(),
                        packet.timestamp(),
                        MessageStatus.DELIVERED,
                        false
                );

                repository.save(message);

                ChatMessage stored =
                        repository.find(packet.id());

                if (stored != null) {
                    changed(stored);
                }

                WirePacket receipt = WirePacket.text(
                        "DELIVERED",
                        accountId,
                        packet.senderId(),
                        packet.id()
                );

                repository.queue(
                        packet.senderId(), receipt);

                flushLater(packet.senderId());
            }

            case "DELIVERED", "READ" -> {

                String messageId = packet.text();

                ChatMessage existing =
                        repository.find(messageId);

                if (existing == null ||
                        !existing.senderId().equals(accountId) ||
                        !existing.receiverId()
                                .equals(packet.senderId())) {
                    return;
                }

                MessageStatus status =
                        packet.type().equals("READ")
                                ? MessageStatus.READ
                                : MessageStatus.DELIVERED;

                ChatMessage updated =
                        repository.setStatus(messageId, status);

                if (updated != null) {
                    changed(updated);
                }
            }

            case "DELETE_GLOBAL" -> {

                String messageId = packet.text();

                repository.deleteGlobally(
                        messageId,
                        packet.senderId(),
                        packet.id()
                );

                ChatMessage updated =
                        repository.find(messageId);

                if (updated != null) {
                    changed(updated);
                }
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported packet type: " + packet.type());
        }
    }

    private void flushLater(String peerId) {

        if (!flushing.add(peerId)) {
            return;
        }

        try {
            io.execute(() -> {

                try {
                    for (ChatRepository.Pending pending :
                            repository.pending(peerId)) {

                        boolean online = network.getPeers()
                                .stream()
                                .anyMatch(p ->
                                        p.id().equals(peerId));

                        if (!online) {
                            break;
                        }

                        boolean received;

                        try {
                            received = network.sendTo(
                                    peerId,
                                    pending.packet()
                            ).get(9, TimeUnit.SECONDS);

                        } catch (Exception ex) {
                            error("message transfer", ex);
                            break;
                        }

                        if (!received) {
                            break;
                        }

                        repository.dropPending(
                                pending.packet().id());

                        if (pending.packet().type()
                                .equals("CHAT")) {

                            ChatMessage updated =
                                    repository.setStatus(
                                            pending.packet().id(),
                                            MessageStatus.SENT
                                    );

                            if (updated != null) {
                                changed(updated);
                            }
                        }
                    }

                } catch (Exception e) {
                    error("outbox", e);

                } finally {
                    flushing.remove(peerId);
                }
            });

        } catch (RejectedExecutionException e) {
            flushing.remove(peerId);
        }
    }

    private void retrySafely() {

        try {
            for (PeerInfo peer : network.getPeers()) {
                flushLater(peer.id());
            }
        } catch (Exception e) {
            error("retry", e);
        }
    }

    private void changed(ChatMessage message) {
        fire(l -> l.onMessageChanged(message));
    }

    private void error(String action, Throwable cause) {
        fire(l -> l.onError(action, cause));
    }

    private void fire(
            java.util.function.Consumer<Listener> event) {

        for (Listener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException ignored) {
                // UI errors cannot interrupt messaging.
            }
        }
    }

    @Override
    public void onPeerOnline(PeerInfo peer) {
        fire(l -> l.onPeerOnline(peer));
        flushLater(peer.id());
    }

    @Override
    public void onPeerOffline(String peerId) {
        fire(l -> l.onPeerOffline(peerId));
    }

    @Override
    public void onNetworkError(
            String action, Throwable cause) {
        error(action, cause);
    }

    @Override
    public void close() {
        network.removeListener(this);
        retry.shutdownNow();
        io.shutdownNow();
    }
}
