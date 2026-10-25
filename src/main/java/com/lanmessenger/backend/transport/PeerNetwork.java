
package com.lanmessenger.backend.transport;

import com.lanmessenger.backend.discovery.PeerDiscovery;
import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.protocol.WirePacket;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class PeerNetwork implements AutoCloseable {

    public static final int DEFAULT_TCP_PORT = 39200;

    public interface Listener {
        default void onPeerOnline(PeerInfo peer) {}

        default void onPeerOffline(String peerId) {}

        default void onNetworkError(
                String operation, Throwable error) {}
    }

    @FunctionalInterface
    public interface PacketHandler {
        void handle(
                WirePacket packet,
                InetAddress remoteAddress
        ) throws Exception;
    }

    private final String localId;
    private final String username;
    private final int tcpPort;

    private final Map<String, PeerInfo> peers =
            new ConcurrentHashMap<>();

    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(
                        r, "lan-peer-worker");
                t.setDaemon(true);
                return t;
            });

    private volatile PacketHandler packetHandler;
    private volatile boolean running;

    private ServerSocket server;
    private PeerDiscovery discovery;

    public PeerNetwork(
            String localId,
            String username,
            int tcpPort) {

        if (localId == null ||
                localId.isBlank() ||
                username == null ||
                username.isBlank() ||
                tcpPort < 1 ||
                tcpPort > 65535) {

            throw new IllegalArgumentException(
                    "Invalid local peer identity");
        }

        this.localId = localId;
        this.username = username;
        this.tcpPort = tcpPort;
    }

    public synchronized void start()
            throws IOException {

        if (running) return;

        ServerSocket tcp = new ServerSocket();
        tcp.setReuseAddress(true);

        try {
            tcp.bind(new InetSocketAddress(tcpPort));
        } catch (IOException e) {
            tcp.close();
            throw e;
        }

        server = tcp;
        running = true;

        workers.execute(this::acceptLoop);

        discovery = new PeerDiscovery(
                localId,
                username,
                tcpPort,
                this::peerFound,
                this::peerLost,
                e -> notifyError("discovery", e));

        try {
            discovery.start();
        } catch (SocketException e) {
            notifyError(
                    "discovery unavailable; TCP still running",
                    e);
        }
    }

    public void setPacketHandler(PacketHandler handler) {
        packetHandler = Objects.requireNonNull(handler);
    }

    public void addListener(Listener listener) {
        listeners.add(
                Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public List<PeerInfo> getPeers() {
        return List.copyOf(peers.values());
    }

    public void addManualPeer(
            String id,
            String name,
            String ipAddress,
            int port) throws UnknownHostException {

        PeerInfo peer = new PeerInfo(
                id,
                name,
                InetAddress.getByName(ipAddress),
                port,
                System.currentTimeMillis());

        if (!id.equals(localId)) {
            peerFound(peer);
        }
    }

    public CompletableFuture<Boolean> sendTo(
            String peerId,
            WirePacket packet) {

        return CompletableFuture.supplyAsync(() -> {
            PeerInfo peer = peers.get(peerId);

            if (peer == null) {
                throw new CompletionException(
                        new IOException(
                                "Peer not found: " + peerId));
            }

            if (!localId.equals(packet.senderId()) ||
                    !peerId.equals(packet.recipientId())) {

                throw new CompletionException(
                        new IOException(
                                "Packet identity mismatch"));
            }

            try (Socket socket = new Socket()) {

                socket.connect(
                        new InetSocketAddress(
                                peer.address(),
                                peer.tcpPort()),
                        3500);

                socket.setSoTimeout(7000);

                DataOutputStream out =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        socket.getOutputStream()));

                DataInputStream in =
                        new DataInputStream(
                                new BufferedInputStream(
                                        socket.getInputStream()));

                packet.write(out);
                out.flush();

                boolean accepted = in.readBoolean();
                String acknowledgedId = in.readUTF();

                return accepted &&
                        packet.id().equals(acknowledgedId);

            } catch (IOException e) {
                notifyError(
                        "sending to " + peer.username(), e);

                throw new CompletionException(e);
            }
        }, workers);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();

                workers.execute(
                        () -> receive(socket));

            } catch (IOException e) {
                if (running) {
                    notifyError(
                            "accepting connection", e);
                }

            } catch (RejectedExecutionException ignored) {
                // Shutdown in progress.
            }
        }
    }

    private void receive(Socket socket) {
        try (socket) {

            socket.setSoTimeout(10_000);

            DataInputStream in =
                    new DataInputStream(
                            new BufferedInputStream(
                                    socket.getInputStream()));

            DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(
                                    socket.getOutputStream()));

            WirePacket packet = WirePacket.read(in);

            boolean accepted = false;

            try {
                if (!localId.equals(
                        packet.recipientId()) ||
                        packet.senderId().equals(localId)) {

                    throw new IOException(
                            "Packet addressed to wrong peer");
                }

                PacketHandler handler = packetHandler;

                if (handler == null) {
                    throw new IOException(
                            "Packet service not ready");
                }

                handler.handle(
                        packet, socket.getInetAddress());

                accepted = true;

            } catch (Exception e) {
                notifyError(
                        "processing " + packet.type(), e);
            }

            out.writeBoolean(accepted);
            out.writeUTF(packet.id());
            out.flush();

        } catch (IOException e) {
            if (running) {
                notifyError("receiving packet", e);
            }
        }
    }

    private void peerFound(PeerInfo peer) {
        PeerInfo previous =
                peers.put(peer.id(), peer);

        if (previous == null ||
                !previous.equals(peer)) {

            for (Listener listener : listeners) {
                safe(() ->
                        listener.onPeerOnline(peer));
            }
        }
    }

    private void peerLost(String id) {
        if (peers.remove(id) != null) {
            for (Listener listener : listeners) {
                safe(() ->
                        listener.onPeerOffline(id));
            }
        }
    }

    private void notifyError(
            String action, Throwable error) {

        for (Listener listener : listeners) {
            safe(() -> listener.onNetworkError(
                    action, error));
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running = false;

        if (discovery != null) {
            discovery.close();
        }

        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        workers.shutdownNow();
        peers.clear();
    }
}
