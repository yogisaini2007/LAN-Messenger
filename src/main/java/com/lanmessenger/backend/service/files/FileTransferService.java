
package com.lanmessenger.backend.service.files;

import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.backend.service.attachments.AttachmentRepository;
import com.lanmessenger.backend.service.attachments.AttachmentRepository.Attachment;
import com.lanmessenger.database.DatabaseManager;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Independent LAN attachment transport.
 * Uses TCP base port + 2.
 *
 * Supports:
 * - Private and group attachment metadata
 * - Persistent outgoing file cache
 * - Offline recipient queues
 * - Automatic retry after reconnection
 * - Incoming attachment storage
 * - Opening downloaded files
 * - Compatibility with legacy incoming files
 */
public final class FileTransferService implements AutoCloseable {

    private static final int LEGACY_MAGIC = 0x4C4D4654;
    private static final int ATTACHMENT_MAGIC = 0x4C4D4632;
    private static final long MAX_SIZE = 100L * 1024 * 1024;

    // Existing MessengerEngine callback remains compatible.
    public record ReceivedFile(
            String senderId,
            String filename,
            Path savedPath
    ) {}

    private final String selfId;
    private final PeerNetwork network;
    private final int port;

    private final Consumer<ReceivedFile> received;
    private final Consumer<Throwable> errors;

    private final List<Consumer<Attachment>> attachmentListeners =
            new CopyOnWriteArrayList<>();

    private final ExecutorService workers =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "lan-file-transfer");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService retries =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lan-file-retry");
                t.setDaemon(true);
                return t;
            });

    // The Group module will provide its own membership check.
    // By default, unauthorized group attachments are rejected.
    private volatile BiPredicate<String, String> groupAuthorizer =
            (groupId, senderId) -> false;

    private volatile AttachmentRepository attachments;
    private volatile boolean running;

    private ServerSocket server;

    public FileTransferService(
            String selfId,
            PeerNetwork network,
            int tcpPort,
            Consumer<ReceivedFile> received,
            Consumer<Throwable> errors
    ) {
        this.selfId = Objects.requireNonNull(selfId);
        this.network = Objects.requireNonNull(network);

        if (tcpPort < 1 || tcpPort > 65533) {
            throw new IllegalArgumentException("Invalid TCP port");
        }

        this.port = tcpPort + 2;
        this.received = Objects.requireNonNull(received);
        this.errors = Objects.requireNonNull(errors);
    }

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

    public void setGroupAuthorizer(
            BiPredicate<String, String> authorizer
    ) {
        groupAuthorizer = Objects.requireNonNull(authorizer);
    }

    public AttachmentRepository repository() {
        AttachmentRepository store = attachments;

        if (store == null) {
            throw new IllegalStateException(
                    "Attachment database unavailable");
        }

        return store;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        // Optional attachment database.
        // Legacy file transfers remain independent.
        try {
            attachments = new AttachmentRepository(selfId);
        } catch (SQLException e) {
            attachments = null;
            report(e);
        }

        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);

        try {
            listener.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            listener.close();
            throw e;
        }

        server = listener;
        running = true;

        workers.execute(this::acceptLoop);

        retries.scheduleWithFixedDelay(
                this::retryQueuedSafely,
                2,
                8,
                TimeUnit.SECONDS
        );
    }

    /**
     * Existing MessengerEngine method.
     *
     * Files are saved to the queue and delivered
     * when the recipient becomes reachable.
     *
     * Completion means queued, not necessarily delivered.
     */
    public CompletableFuture<Path> sendFile(
            String peerId,
            Path file
    ) {
        return sendAttachment(
                "PRIVATE",
                peerId,
                List.of(peerId),
                file
        ).thenApply(item -> file);
    }

    /**
     * New modular method for private/group attachments.
     *
     * Recipients may be offline.
     * Their file remains in the persistent queue.
     */
    public CompletableFuture<Attachment> sendAttachment(
            String type,
            String chatId,
            Collection<String> recipients,
            Path source
    ) {
        if (!running) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "File transfer not started"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!"PRIVATE".equals(type)
                        && !"GROUP".equals(type)) {
                    throw new IllegalArgumentException(
                            "Unknown chat type");
                }

                if ("PRIVATE".equals(type)
                        && (recipients == null
                        || recipients.size() != 1
                        || !recipients.contains(chatId))) {
                    throw new IllegalArgumentException(
                            "Private chat recipient mismatch");
                }

                if ("GROUP".equals(type)
                        && (recipients == null
                        || recipients.isEmpty())) {
                    throw new IllegalArgumentException(
                            "Choose group recipients");
                }

                if (!Files.isRegularFile(source)
                        || Files.size(source) > MAX_SIZE) {
                    throw new IOException(
                            "File missing or larger than 100 MB");
                }

                Attachment item = repository().prepareOutgoing(
                        source,
                        type,
                        chatId,
                        selfId,
                        recipients
                );

                fireAttachment(item);
                triggerRetry();

                return item;

            } catch (Exception e) {
                report(e);
                throw new CompletionException(e);
            }
        }, workers);
    }

    /**
     * Opens a locally available attachment.
     * Call only after an explicit user click.
     */
    public CompletableFuture<Void> openAttachment(String id) {
        return CompletableFuture.runAsync(() -> {
            try {
                Attachment item = repository()
                        .find(id)
                        .orElseThrow(() ->
                                new IOException(
                                        "Attachment not found"));

                if (item.localPath() == null
                        || item.localPath().isBlank()) {
                    throw new IOException(
                            "File has not been downloaded");
                }

                Path path = Path.of(item.localPath())
                        .toAbsolutePath()
                        .normalize();

                if (!Files.isRegularFile(path)) {
                    throw new IOException(
                            "Downloaded file is missing");
                }

                if (!Desktop.isDesktopSupported()
                        || !Desktop.getDesktop().isSupported(
                                Desktop.Action.OPEN)) {
                    throw new IOException(
                            "Opening files is unsupported here");
                }

                Desktop.getDesktop().open(path.toFile());

            } catch (Exception e) {
                report(e);
                throw new CompletionException(e);
            }
        }, workers);
    }

    private void triggerRetry() {
        if (!running) return;

        try {
            retries.execute(this::retryQueuedSafely);
        } catch (RejectedExecutionException ignored) {
        }
    }

    /**
     * Sends queued attachments to currently online peers.
     * Unreachable recipients remain in SQLite.
     */
    private void retryQueuedSafely() {
        try {
            AttachmentRepository store = attachments;

            if (!running || store == null) return;

            for (PeerInfo peer : network.getPeers()) {
                for (Attachment item :
                        store.pendingFor(peer.id())) {

                    try {
                        sendQueued(peer, item);

                        store.markDelivered(
                                item.id(),
                                peer.id()
                        );

                        fireAttachment(item);

                    } catch (Exception e) {
                        store.recordAttempt(
                                item.id(),
                                peer.id(),
                                e.getMessage()
                        );

                        // Avoid flooding an unreachable peer.
                        break;
                    }
                }
            }

        } catch (Exception e) {
            report(e);
        }
    }

    /**
     * New attachment transfer protocol.
     * Includes stable file ID and originating chat.
     */
    private void sendQueued(
            PeerInfo peer,
            Attachment item
    ) throws Exception {

        if (item.localPath() == null) {
            throw new IOException("Cached file not found");
        }

        Path file = Path.of(item.localPath());

        if (!Files.isRegularFile(file)
                || Files.size(file) != item.size()) {
            throw new IOException(
                    "Cached file missing or changed");
        }

        try (Socket socket = new Socket()) {

            socket.connect(
                    new InetSocketAddress(
                            peer.address(),
                            peer.tcpPort() + 2
                    ),
                    5000
            );

            socket.setSoTimeout(30000);

            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            socket.getOutputStream()));

            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(
                            socket.getInputStream()));

            out.writeInt(ATTACHMENT_MAGIC);
            out.writeUTF(selfId);
            out.writeUTF(peer.id());
            out.writeUTF(item.id());
            out.writeUTF(item.chatType());
            out.writeUTF(item.chatId());
            out.writeUTF(item.filename());
            out.writeUTF(item.mimeType());
            out.writeLong(item.createdAt());
            out.writeLong(item.size());

            try (InputStream source =
                         Files.newInputStream(file)) {
                source.transferTo(out);
            }

            out.flush();

            boolean accepted = in.readBoolean();
            String acknowledgedId = in.readUTF();

            if (!accepted
                    || !item.id().equals(acknowledgedId)) {
                throw new IOException(
                        "Recipient did not acknowledge attachment");
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();

                workers.execute(() -> receive(socket));

            } catch (IOException e) {
                if (running) report(e);

            } catch (RejectedExecutionException e) {
                break;
            }
        }
    }

    /**
     * Accepts both:
     * - Original raw file protocol
     * - New attachment-aware protocol
     */
    private void receive(Socket socket) {
        Path temporary = null;

        try (socket) {
            socket.setSoTimeout(30000);

            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(
                            socket.getInputStream()));

            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            socket.getOutputStream()));

            int magic = in.readInt();

            if (magic != LEGACY_MAGIC
                    && magic != ATTACHMENT_MAGIC) {
                throw new IOException(
                        "Unrecognized attachment protocol");
            }

            String sender = in.readUTF();
            String recipient = in.readUTF();

            String id = magic == ATTACHMENT_MAGIC
                    ? in.readUTF()
                    : UUID.randomUUID().toString();

            String type = magic == ATTACHMENT_MAGIC
                    ? in.readUTF()
                    : "PRIVATE";

            String senderChatId = magic == ATTACHMENT_MAGIC
                    ? in.readUTF()
                    : selfId;

            String filename = in.readUTF();

            String mime = magic == ATTACHMENT_MAGIC
                    ? in.readUTF()
                    : "application/octet-stream";

            long timestamp = magic == ATTACHMENT_MAGIC
                    ? in.readLong()
                    : System.currentTimeMillis();

            long size = in.readLong();

            boolean knownPeer = network.getPeers()
                    .stream()
                    .anyMatch(p ->
                            p.id().equals(sender)
                            && p.address().equals(
                                    socket.getInetAddress()));

            if (!recipient.equals(selfId)
                    || !knownPeer
                    || size < 0
                    || size > MAX_SIZE
                    || id.isBlank()
                    || id.length() > 100
                    || filename.isBlank()
                    || filename.length() > 180
                    || senderChatId.isBlank()
                    || senderChatId.length() > 200
                    || mime.length() > 180
                    || (!"PRIVATE".equals(type)
                    && !"GROUP".equals(type))) {

                throw new IOException(
                        "Attachment rejected");
            }

            if ("PRIVATE".equals(type)
                    && !senderChatId.equals(selfId)) {
                throw new IOException(
                        "Invalid private conversation");
            }

            if ("GROUP".equals(type)
                    && !groupAuthorizer.test(
                            senderChatId,
                            sender)) {
                throw new IOException(
                        "Sender not authorized for this group");
            }

            // Remove unsafe Windows filename characters.
            filename = filename.replaceAll(
                    "[\\\\/:*?\"<>|\\p{Cntrl}]",
                    "_"
            );

            filename = filename.replaceAll(
                    "[. ]+$",
                    "_"
            );

            Path downloads = DatabaseManager
                    .getDataDirectory()
                    .resolve("downloads");

            Files.createDirectories(downloads);

            Path destination = downloads.resolve(
                    id + "-" + filename
            );

            temporary = downloads.resolve(
                    UUID.randomUUID() + ".part"
            );

            try (OutputStream target =
                         Files.newOutputStream(
                                 temporary,
                                 StandardOpenOption.CREATE_NEW)) {

                byte[] buffer = new byte[65536];
                long remaining = size;

                while (remaining > 0) {

                    int n = in.read(
                            buffer,
                            0,
                            (int) Math.min(
                                    remaining,
                                    buffer.length));

                    if (n < 0) {
                        throw new EOFException(
                                "Incomplete attachment");
                    }

                    target.write(buffer, 0, n);
                    remaining -= n;
                }
            }

            boolean alreadyReceived =
                    Files.isRegularFile(destination);

            if (alreadyReceived) {
                Files.deleteIfExists(temporary);
            } else {
                Files.move(temporary, destination);
            }

            temporary = null;

            // On a receiving PC, a private conversation
            // is indexed by the sender's ID.
            String localChatId =
                    "PRIVATE".equals(type)
                            ? sender
                            : senderChatId;

            Attachment item = new Attachment(
                    id,
                    type,
                    localChatId,
                    sender,
                    filename,
                    mime,
                    size,
                    destination.toString(),
                    timestamp,
                    "RECEIVED"
            );

            AttachmentRepository store = attachments;

            if (store != null) {
                store.saveIncoming(item);

            } else if (magic == ATTACHMENT_MAGIC) {
                throw new IOException(
                        "Attachment storage unavailable; retry later");
            }

            // Acknowledge after file storage.
            out.writeBoolean(true);

            if (magic == ATTACHMENT_MAGIC) {
                out.writeUTF(id);
            }

            out.flush();

            if (!alreadyReceived) {
                try {
                    received.accept(new ReceivedFile(
                            sender,
                            filename,
                            destination
                    ));
                } catch (RuntimeException e) {
                    report(e);
                }

                fireAttachment(item);
            }

        } catch (Exception e) {
            if (running) report(e);

        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Notify optional UI integrations.
     * UI failures cannot interrupt file transfers.
     */
    private void fireAttachment(Attachment item) {
        for (Consumer<Attachment> listener :
                attachmentListeners) {

            try {
                listener.accept(item);

            } catch (RuntimeException e) {
                report(e);
            }
        }
    }

    private void report(Throwable e) {
        try {
            errors.accept(e);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running = false;

        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        retries.shutdownNow();
        workers.shutdownNow();
    }
}
