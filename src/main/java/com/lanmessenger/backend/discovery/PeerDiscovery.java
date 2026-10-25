
package com.lanmessenger.backend.discovery;

import com.lanmessenger.backend.peer.PeerInfo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class PeerDiscovery implements AutoCloseable {

    public static final int DISCOVERY_PORT = 39201;
    private static final int MAGIC = 0x4C4D4453;
    private static final int VERSION = 1;
    private static final long EXPIRE_MS = 12_000;

    private final String localId;
    private final String username;
    private final int tcpPort;

    private final Consumer<PeerInfo> found;
    private final Consumer<String> lost;
    private final Consumer<Exception> errors;

    private final Map<String, PeerInfo> peers =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(
                        r, "lan-discovery-timer");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService receiver =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(
                        r, "lan-discovery-receiver");
                t.setDaemon(true);
                return t;
            });

    private DatagramSocket socket;
    private volatile boolean running;

    public PeerDiscovery(
            String localId,
            String username,
            int tcpPort,
            Consumer<PeerInfo> found,
            Consumer<String> lost,
            Consumer<Exception> errors) {

        this.localId = Objects.requireNonNull(localId);
        this.username = Objects.requireNonNull(username);
        this.tcpPort = tcpPort;
        this.found = Objects.requireNonNull(found);
        this.lost = Objects.requireNonNull(lost);
        this.errors = Objects.requireNonNull(errors);
    }

    public synchronized void start()
            throws SocketException {
        if (running) return;

        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);

        try {
            udp.bind(new InetSocketAddress(
                    DISCOVERY_PORT));
            udp.setBroadcast(true);
            udp.setSoTimeout(1000);
        } catch (SocketException e) {
            udp.close();
            throw e;
        }

        socket = udp;
        running = true;

        receiver.execute(this::receiveLoop);

        timer.scheduleWithFixedDelay(
                this::announceSafely,
                0, 3, TimeUnit.SECONDS);

        timer.scheduleWithFixedDelay(
                this::expirePeers,
                4, 4, TimeUnit.SECONDS);
    }

    private void announceSafely() {
        if (!running) return;

        try {
            ByteArrayOutputStream buffer =
                    new ByteArrayOutputStream();

            DataOutputStream out =
                    new DataOutputStream(buffer);

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(localId);
            out.writeUTF(username);
            out.writeInt(tcpPort);
            out.flush();

            byte[] bytes = buffer.toByteArray();

            Set<InetAddress> broadcasts = new HashSet<>();
            broadcasts.add(
                    InetAddress.getByName("255.255.255.255"));

            Enumeration<NetworkInterface> networks =
                    NetworkInterface.getNetworkInterfaces();

            while (networks != null &&
                    networks.hasMoreElements()) {

                NetworkInterface network =
                        networks.nextElement();

                if (!network.isUp() ||
                        network.isLoopback()) {
                    continue;
                }

                for (InterfaceAddress a :
                        network.getInterfaceAddresses()) {

                    if (a.getBroadcast() != null) {
                        broadcasts.add(a.getBroadcast());
                    }
                }
            }

            for (InetAddress target : broadcasts) {
                try {
                    socket.send(new DatagramPacket(
                            bytes, bytes.length,
                            target, DISCOVERY_PORT));
                } catch (IOException ignored) {
                    // Other adapters remain operational.
                }
            }

        } catch (Exception e) {
            report(e);
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];

        while (running) {
            DatagramPacket datagram =
                    new DatagramPacket(
                            buffer, buffer.length);

            try {
                socket.receive(datagram);

                DataInputStream in = new DataInputStream(
                        new ByteArrayInputStream(
                                datagram.getData(),
                                datagram.getOffset(),
                                datagram.getLength()));

                if (in.readInt() != MAGIC ||
                        in.readInt() != VERSION) {
                    continue;
                }

                String id = in.readUTF();
                String name = in.readUTF();
                int port = in.readInt();

                if (id.isBlank() ||
                        id.equals(localId) ||
                        name.isBlank() ||
                        name.length() > 100 ||
                        port < 1 || port > 65535) {
                    continue;
                }

                PeerInfo current = new PeerInfo(
                        id, name,
                        datagram.getAddress(),
                        port,
                        System.currentTimeMillis());

                PeerInfo previous = peers.put(
                        id, current);

                if (previous == null ||
                        !previous.address().equals(
                                current.address()) ||
                        previous.tcpPort() !=
                                current.tcpPort() ||
                        !previous.username().equals(
                                current.username())) {

                    safely(() -> found.accept(current));
                }

            } catch (SocketTimeoutException ignored) {
                // Periodic shutdown check.

            } catch (SocketException e) {
                if (running) report(e);

            } catch (IOException |
                     IllegalArgumentException ignored) {
                // Ignore malformed network packets.
            }
        }
    }

    private void expirePeers() {
        long now = System.currentTimeMillis();

        for (PeerInfo peer : peers.values()) {
            if (now - peer.lastSeen() > EXPIRE_MS &&
                    peers.remove(
                            peer.id(), peer)) {

                safely(() ->
                        lost.accept(peer.id()));
            }
        }
    }

    private void report(Exception error) {
        safely(() -> errors.accept(error));
    }

    private void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running = false;

        if (socket != null) {
            socket.close();
        }

        timer.shutdownNow();
        receiver.shutdownNow();
        peers.clear();
    }
}
