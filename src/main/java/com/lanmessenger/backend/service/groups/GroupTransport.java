
package com.lanmessenger.backend.service.groups;

import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.transport.PeerNetwork;

import java.io.*;
import java.net.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public final class GroupTransport
        implements AutoCloseable {

    public static final int GROUP_PORT_OFFSET = 3;

    private final PeerNetwork discovery;
    private final String selfId;
    private final int port;

    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(
                        r, "lan-group-transport");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;

    private ServerSocket server;

    private BiConsumer<GroupPacket, InetAddress> receiver;

    public GroupTransport(
            PeerNetwork discovery,
            String selfId,
            int basePort) {

        this.discovery =
                Objects.requireNonNull(discovery);

        this.selfId =
                Objects.requireNonNull(selfId);

        if (basePort < 1 || basePort > 65532) {
            throw new IllegalArgumentException(
                    "Invalid base port");
        }

        this.port = basePort + GROUP_PORT_OFFSET;
    }

    public void setReceiver(
            BiConsumer<GroupPacket, InetAddress> receiver) {

        this.receiver =
                Objects.requireNonNull(receiver);
    }

    public synchronized void start()
            throws IOException {

        if (running) return;

        if (receiver == null) {
            throw new IllegalStateException(
                    "Group packet receiver not configured");
        }

        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);

        try {
            listener.bind(
                    new InetSocketAddress(port));

        } catch (IOException e) {
            listener.close();
            throw e;
        }

        server = listener;
        running = true;

        workers.execute(() -> {

            while (running) {
                try {
                    Socket socket = server.accept();

                    workers.execute(
                            () -> handle(socket));

                } catch (IOException e) {
                    if (running) {
                        System.err.println(
                                "Group accept: " +
                                        e.getMessage());
                    }

                } catch (RejectedExecutionException e) {
                    break;
                }
            }
        });
    }

    private void handle(Socket socket) {

        try (socket) {
            socket.setSoTimeout(10000);

            DataInputStream in =
                    new DataInputStream(
                            new BufferedInputStream(
                                    socket.getInputStream()));

            DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(
                                    socket.getOutputStream()));

            GroupPacket packet =
                    GroupPacket.read(in);

            boolean accepted = false;

            try {
                if (!packet.to().equals(selfId)) {
                    throw new SecurityException(
                            "Incorrect group recipient");
                }

                boolean known =
                        discovery.getPeers()
                                .stream()
                                .anyMatch(peer ->
                                        peer.id().equals(
                                                packet.from())
                                        && peer.address().equals(
                                                socket.getInetAddress()));

                if (!known) {
                    throw new SecurityException(
                            "Unknown LAN peer");
                }

                receiver.accept(
                        packet,
                        socket.getInetAddress());

                accepted = true;

            } catch (Exception e) {
                System.err.println(
                        "Group packet rejected: " +
                                e.getMessage());
            }

            out.writeBoolean(accepted);
            out.writeUTF(packet.id());
            out.flush();

        } catch (IOException e) {
            if (running) {
                System.err.println(
                        "Group transport error: " +
                                e.getMessage());
            }
        }
    }

    public CompletableFuture<Boolean> send(
            PeerInfo peer,
            GroupPacket packet) {

        if (!running) {
            return CompletableFuture.failedFuture(
                    new IOException(
                            "Group transport is offline"));
        }

        if (!packet.from().equals(selfId) ||
                !packet.to().equals(peer.id())) {

            return CompletableFuture.failedFuture(
                    new SecurityException(
                            "Group packet identity mismatch"));
        }

        return CompletableFuture.supplyAsync(() -> {

            try (Socket socket = new Socket()) {

                socket.connect(
                        new InetSocketAddress(
                                peer.address(),
                                peer.tcpPort()
                                        + GROUP_PORT_OFFSET),
                        3500);

                socket.setSoTimeout(8000);

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

                boolean acknowledged =
                        in.readBoolean();

                String acknowledgedId =
                        in.readUTF();

                return acknowledged &&
                        packet.id().equals(acknowledgedId);

            } catch (IOException e) {
                throw new CompletionException(e);
            }

        }, workers);
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

        workers.shutdownNow();
    }
}
