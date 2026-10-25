
package com.lanmessenger.app;

import com.lanmessenger.backend.auth.AuthService;
import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.service.files.FileTransferService;
import com.lanmessenger.backend.service.messaging.MessagingService;
import com.lanmessenger.backend.service.profile.ProfileSyncService;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.backend.service.attachments.AttachmentRepository;
import com.lanmessenger.backend.service.attachments.AttachmentRepository.Attachment;
import com.lanmessenger.core.MessengerApi;
import com.lanmessenger.core.MessengerEvents;
import com.lanmessenger.database.DatabaseManager;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiPredicate;

public final class MessengerEngine implements MessengerApi {

    private final AuthService auth = new AuthService();
    private final int tcpPort;
    private final Object stateLock = new Object();

    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    // Independent attachment events.
    // A failure here cannot stop private messages.
    private final List<Consumer<Attachment>> attachmentListeners =
            new CopyOnWriteArrayList<>();

    // Deny group attachments until the group module
    // registers a real membership validator.
    private volatile BiPredicate<String, String>
            groupAttachmentAuthorizer =
            (groupId, senderId) -> false;

    private final ExecutorService tasks =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "lan-app-service");
                t.setDaemon(true);
                return t;
            });

    private volatile Profile currentProfile;
    private volatile Session session;
    private volatile boolean closed;

    private record Session(
            PeerNetwork network,
            MessagingService messaging,
            ProfileSyncService profiles,
            FileTransferService files,
            boolean filesAvailable
    ) {
        void stop() {
            messaging.close();
            profiles.close();
            files.close();
            network.close();
        }
    }

    public MessengerEngine() throws SQLException {
        this(PeerNetwork.DEFAULT_TCP_PORT);
    }

    public MessengerEngine(int port) throws SQLException {
        if (port < 1 || port > 65532) {
            throw new IllegalArgumentException(
                    "Invalid network port");
        }

        tcpPort = port;
        DatabaseManager.initialize();
    }

    public Profile currentProfile() {
        return currentProfile;
    }

    public boolean isNetworkingStarted() {
        return session != null;
    }

    // Existing group bridge uses this shared peer network.
    // It does not start another private-chat server.
    public PeerNetwork networkForGroups() {
        Session active = session;

        return closed || active == null
                ? null
                : active.network();
    }

    // --------------------------------------------------
    // INDEPENDENT ATTACHMENT MODULE
    // --------------------------------------------------

    public void addAttachmentListener(
            Consumer<Attachment> listener
    ) {
        attachmentListeners.add(
                Objects.requireNonNull(listener));
    }

    public void removeAttachmentListener(
            Consumer<Attachment> listener
    ) {
        attachmentListeners.remove(listener);
    }

    private void informAttachment(Attachment item) {
        for (Consumer<Attachment> listener :
                attachmentListeners) {
            try {
                listener.accept(item);
            } catch (RuntimeException e) {
                report("attachment UI event", e);
            }
        }
    }

    /**
     * Register group authorization independently.
     *
     * The group service must verify that both the
     * sender and current user belong to the group.
     */
    public void setGroupAttachmentAuthorizer(
            BiPredicate<String, String> authorizer
    ) {
        groupAttachmentAuthorizer =
                Objects.requireNonNull(authorizer);

        Session active = session;

        if (active != null && active.filesAvailable()) {
            active.files().setGroupAuthorizer(authorizer);
        }
    }

    /**
     * Retrieve persistent file cards for a conversation.
     */
    public CompletableFuture<List<Attachment>>
            getAttachmentHistory(
                    String chatType,
                    String chatId
            ) {

        return withSession(s ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        requireFiles(s);

                        return s.files()
                                .repository()
                                .forChat(chatType, chatId);

                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, tasks));
    }

    /**
     * Queue a private attachment.
     * Offline users receive it after reconnecting.
     */
    public CompletableFuture<Attachment>
            sendPrivateAttachment(
                    String contactId,
                    Path file
            ) {

        return withSession(s -> {
            requireFiles(s);

            return s.files().sendAttachment(
                    "PRIVATE",
                    contactId,
                    List.of(contactId),
                    file
            );
        });
    }

    /**
     * Queue a group attachment for the supplied members.
     * GroupWebBridge must provide the verified roster.
     */
    public CompletableFuture<Attachment>
            sendGroupAttachment(
                    String groupId,
                    Collection<String> recipientIds,
                    Path file
            ) {

        return withSession(s -> {
            requireFiles(s);

            if (recipientIds == null ||
                    recipientIds.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Group has no recipients"));
            }

            return s.files().sendAttachment(
                    "GROUP",
                    groupId,
                    recipientIds,
                    file
            );
        });
    }

    /**
     * Open a downloaded or cached file after user click.
     */
    public CompletableFuture<Void> openAttachment(
            String attachmentId
    ) {

        return withSession(s -> {
            requireFiles(s);

            return s.files().openAttachment(
                    attachmentId);
        });
    }

    /**
     * Hide one attachment card for the current user.
     * The outgoing delivery queue is not erased.
     */
    public CompletableFuture<Void> deleteAttachmentForMe(
            String attachmentId
    ) {

        return withSession(s ->
                CompletableFuture.runAsync(() -> {
                    try {
                        requireFiles(s);

                        s.files().repository()
                                .hideForMe(attachmentId);

                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, tasks));
    }

    /**
     * Clear attachment cards in a private or group chat.
     * This does not stop queued transfers.
     */
    public CompletableFuture<Void> clearAttachments(
            String chatType,
            String chatId
    ) {

        return withSession(s ->
                CompletableFuture.runAsync(() -> {
                    try {
                        requireFiles(s);

                        s.files().repository()
                                .clearChat(chatType, chatId);

                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, tasks));
    }

    private static void requireFiles(Session s) {
        if (!s.filesAvailable()) {
            throw new IllegalStateException(
                    "File sharing is unavailable");
        }
    }

    // --------------------------------------------------
    // AUTHENTICATION
    // --------------------------------------------------

    @Override
    public CompletableFuture<Profile> register(
            String username,
            char[] password,
            Path avatar
    ) {

        return CompletableFuture.supplyAsync(() -> {
            synchronized (stateLock) {
                ensureCanAuthenticate();

                try {
                    Profile profile = auth.register(
                            username, password, avatar);

                    currentProfile = profile;
                    informProfile(profile);

                    return profile;

                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        }, tasks);
    }

    @Override
    public CompletableFuture<Profile> login(
            String username,
            char[] password
    ) {

        return CompletableFuture.supplyAsync(() -> {
            synchronized (stateLock) {
                ensureCanAuthenticate();

                try {
                    Profile profile = auth.login(
                            username, password);

                    currentProfile = profile;
                    informProfile(profile);

                    return profile;

                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        }, tasks);
    }

    // --------------------------------------------------
    // NETWORK STARTUP
    // --------------------------------------------------

    @Override
    public CompletableFuture<Void> startNetworking() {

        return CompletableFuture.runAsync(() -> {
            synchronized (stateLock) {

                ensureOpen();

                if (currentProfile == null) {
                    throw new IllegalStateException(
                            "Login or register first");
                }

                if (session != null) return;

                Profile me = currentProfile;

                PeerNetwork network = new PeerNetwork(
                        me.id(), me.username(), tcpPort);

                MessagingService messaging =
                        new MessagingService(
                                me.id(), network);

                messaging.addListener(
                        new MessagingService.Listener() {

                    private final Set<String> observedOnline =
                            ConcurrentHashMap.newKeySet();

                    @Override
                    public void onMessageChanged(
                            ChatMessage message) {

                        inform(l ->
                                l.onMessageChanged(message));
                    }

                    @Override
                    public void onMessageHidden(String id) {
                        inform(l ->
                                l.onMessageDeleted(id));
                    }

                    @Override
                    public void onConversationCleared(
                            String peerId) {

                        informExtended(l ->
                                l.onConversationCleared(peerId));
                    }

                    @Override
                    public void onPeerOnline(PeerInfo peer) {

                        if (observedOnline.add(peer.id())) {
                            refreshContacts();
                        }
                    }

                    @Override
                    public void onPeerOffline(String peerId) {
                        observedOnline.remove(peerId);
                        refreshContacts();
                    }

                    @Override
                    public void onError(
                            String action,
                            Throwable error
                    ) {
                        report(action, error);
                    }
                });

                ProfileSyncService profiles =
                        new ProfileSyncService(
                                me,
                                auth,
                                network,
                                tcpPort,
                                contacts -> refreshContacts(),
                                e -> report(
                                        "profile synchronization",
                                        e)
                        );

                FileTransferService files =
                        new FileTransferService(
                                me.id(),
                                network,
                                tcpPort,
                                file -> informExtended(
                                        l -> l.onFileReceived(
                                                file.senderId(),
                                                file.filename(),
                                                file.savedPath())),
                                e -> report("file transfer", e)
                        );

                // Connect the independent attachment events.
                files.addAttachmentListener(
                        this::informAttachment);

                // Group module controls its own permissions.
                files.setGroupAuthorizer(
                        groupAttachmentAuthorizer);

                boolean fileReady = false;

                try {
                    // Optional services start independently.

                    try {
                        profiles.start();
                    } catch (Exception e) {
                        report(
                                "profile service unavailable",
                                e);
                    }

                    try {
                        files.start();
                        fileReady = true;
                    } catch (Exception e) {
                        report(
                                "file service unavailable",
                                e);
                    }

                    session = new Session(
                            network,
                            messaging,
                            profiles,
                            files,
                            fileReady
                    );

                    network.start();
                    refreshContacts();

                } catch (Exception e) {

                    Session failed = session;
                    session = null;

                    if (failed != null) {
                        failed.stop();
                    } else {
                        messaging.close();
                        profiles.close();
                        files.close();
                        network.close();
                    }

                    throw new CompletionException(e);
                }
            }
        }, tasks);
    }

    // --------------------------------------------------
    // EXISTING PRIVATE MESSAGING
    // --------------------------------------------------

    @Override
    public void addListener(Listener listener) {
        listeners.add(
                Objects.requireNonNull(listener));
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public CompletableFuture<List<Contact>> getContacts() {

        return withSession(s ->
                CompletableFuture.supplyAsync(
                        () -> snapshotContacts(s),
                        tasks));
    }

    @Override
    public CompletableFuture<List<ChatMessage>> getHistory(
            String contactId
    ) {
        return withSession(s ->
                s.messaging().getHistory(contactId));
    }

    @Override
    public CompletableFuture<String> sendMessage(
            String contactId,
            String text
    ) {
        return withSession(s ->
                s.messaging().sendMessage(contactId, text));
    }

    @Override
    public CompletableFuture<Void> markAsRead(
            String messageId
    ) {
        return withSession(s ->
                s.messaging().markAsRead(messageId));
    }

    @Override
    public CompletableFuture<Void> deleteForMe(
            String messageId
    ) {
        return withSession(s ->
                s.messaging().deleteForMe(messageId));
    }

    @Override
    public CompletableFuture<Void> deleteForEveryone(
            String messageId
    ) {
        return withSession(s ->
                s.messaging().deleteForEveryone(messageId));
    }

    /**
     * Clear private messages and their attachment cards.
     *
     * Message clearing remains the primary operation.
     * Attachment cleanup failure cannot interrupt
     * private messaging or prevent chat clearing.
     */
    @Override
    public CompletableFuture<Void> clearConversation(
            String contactId
    ) {

        return withSession(s ->
                s.messaging()
                        .clearConversation(contactId)
                        .thenRunAsync(() -> {
                            try {
                                if (s.filesAvailable()) {
                                    s.files().repository()
                                            .clearChat(
                                                    "PRIVATE",
                                                    contactId);
                                }

                            } catch (Exception e) {
                                report(
                                        "clearing attachment cards",
                                        e);
                            }
                        }, tasks));
    }

    /**
     * Existing method remains compatible with WebBridge.
     *
     * The updated transfer service queues the file;
     * it may not be delivered immediately.
     */
    @Override
    public CompletableFuture<Void> sendFile(
            String contactId,
            Path file
    ) {

        return withSession(s -> {

            if (!s.filesAvailable()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "File sharing unavailable"));
            }

            return s.files()
                    .sendFile(contactId, file)
                    .thenApply(path -> null);
        });
    }

    @Override
    public CompletableFuture<Void> updateAvatar(
            Path image
    ) {

        return CompletableFuture.runAsync(() -> {
            synchronized (stateLock) {

                ensureOpen();

                if (currentProfile == null) {
                    throw new IllegalStateException(
                            "Login first");
                }

                try {
                    Profile updated =
                            auth.updateAvatar(
                                    currentProfile.id(),
                                    image);

                    currentProfile = updated;
                    informProfile(updated);

                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        }, tasks);
    }

    // --------------------------------------------------
    // CONTACTS
    // --------------------------------------------------

    private List<Contact> snapshotContacts(
            Session s
    ) {

        Map<String, Contact> byId = new HashMap<>();

        try {
            for (Contact contact :
                    s.profiles().getContacts()) {

                byId.put(
                        contact.id(),
                        contact);
            }

        } catch (SQLException e) {
            report("loading contacts", e);
        }

        for (PeerInfo peer :
                s.network().getPeers()) {

            Contact previous =
                    byId.get(peer.id());

            byte[] avatar =
                    previous == null
                            ? new byte[0]
                            : previous.avatar();

            byId.put(
                    peer.id(),
                    new Contact(
                            peer.id(),
                            peer.username(),
                            avatar,
                            true,
                            peer.lastSeen()
                    )
            );
        }

        ArrayList<Contact> result =
                new ArrayList<>(byId.values());

        result.sort(
                Comparator.comparing(
                                Contact::online)
                        .reversed()
                        .thenComparing(
                                Contact::username,
                                String.CASE_INSENSITIVE_ORDER)
        );

        return List.copyOf(result);
    }

    private void refreshContacts() {

        Session active = session;

        if (active == null || closed) return;

        try {
            tasks.execute(() ->
                    inform(l ->
                            l.onContactsChanged(
                                    snapshotContacts(active))));

        } catch (RejectedExecutionException ignored) {
        }
    }

    // --------------------------------------------------
    // SAFETY / EVENT HELPERS
    // --------------------------------------------------

    private <T> CompletableFuture<T> withSession(
            Function<Session, CompletableFuture<T>>
                    operation
    ) {

        Session active = session;

        if (closed || active == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "LAN Messenger is not connected"));
        }

        try {
            return operation.apply(active);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Application is closed");
        }
    }

    private void ensureCanAuthenticate() {
        ensureOpen();

        if (session != null) {
            throw new IllegalStateException(
                    "Close the current session first");
        }
    }

    private void informProfile(Profile profile) {
        informExtended(l ->
                l.onMyProfileChanged(profile));
    }

    private void report(
            String action,
            Throwable error
    ) {
        inform(l ->
                l.onError(action, error));
    }

    private void inform(
            Consumer<Listener> action
    ) {

        for (Listener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException ignored) {
                // UI failure cannot stop networking.
            }
        }
    }

    private void informExtended(
            Consumer<MessengerEvents> action
    ) {

        for (Listener listener : listeners) {
            if (listener instanceof
                    MessengerEvents extended) {

                try {
                    action.accept(extended);

                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    // --------------------------------------------------
    // SHUTDOWN
    // --------------------------------------------------

    @Override
    public void close() {

        synchronized (stateLock) {

            if (closed) return;

            closed = true;

            Session active = session;
            session = null;

            if (active != null) {
                active.stop();
            }

            tasks.shutdownNow();
        }
    }
}
