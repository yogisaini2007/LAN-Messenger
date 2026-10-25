
package com.lanmessenger.backend.service.profile;

import com.lanmessenger.backend.auth.AuthService;
import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.core.MessengerApi.Contact;
import com.lanmessenger.core.MessengerApi.Profile;
import com.lanmessenger.database.DatabaseManager;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class ProfileSyncService
        implements AutoCloseable, PeerNetwork.Listener {

    private final Profile self;
    private final AuthService auth;
    private final PeerNetwork network;
    private final int port;
    private final Consumer<List<Contact>> changed;
    private final Consumer<Throwable> errors;

    private final Set<String> active =
            ConcurrentHashMap.newKeySet();

    private final Map<String, Long> lastRefresh =
            new ConcurrentHashMap<>();

    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lan-profile");
                t.setDaemon(true);
                return t;
            });

    private ServerSocket server;
    private volatile boolean running;

    public ProfileSyncService(
            Profile self,
            AuthService auth,
            PeerNetwork network,
            int tcpPort,
            Consumer<List<Contact>> changed,
            Consumer<Throwable> errors) {

        this.self = Objects.requireNonNull(self);
        this.auth = Objects.requireNonNull(auth);
        this.network = Objects.requireNonNull(network);
        this.port = tcpPort + 1;
        this.changed = Objects.requireNonNull(changed);
        this.errors = Objects.requireNonNull(errors);
    }

    public synchronized void start() throws IOException {
        if (running) return;

        server = new ServerSocket();
        server.setReuseAddress(true);

        try {
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            server.close();
            throw e;
        }

        running = true;
        network.addListener(this);
        workers.execute(this::acceptLoop);

        for (PeerInfo peer : network.getPeers()) {
            onPeerOnline(peer);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> serve(socket));
            } catch (IOException e) {
                if (running) report(e);
            } catch (RejectedExecutionException ignored) {
                break;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);

            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            socket.getOutputStream()));

            Profile current = auth.getProfile(self.id());
            byte[] avatar = current.avatar();

            out.writeUTF(current.id());
            out.writeUTF(current.username());
            out.writeInt(avatar.length);
            out.write(avatar);
            out.flush();

        } catch (Exception e) {
            if (running) report(e);
        }
    }

    @Override
    public void onPeerOnline(PeerInfo peer) {
        long now = System.currentTimeMillis();
        Long previous = lastRefresh.get(peer.id());

        if (active.add(peer.id())) {
            refreshPeer(peer);
        } else if (previous == null || now - previous > 30000) {
            refreshPeer(peer);
        }
    }

    private void refreshPeer(PeerInfo peer) {
        lastRefresh.put(peer.id(), System.currentTimeMillis());

        workers.execute(() -> {
            try {
                saveContact(peer.id(), peer.username(),
                        peer.lastSeen(), null);
                notifyChanged();

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(
                            peer.address(), peer.tcpPort() + 1), 2500);
                    socket.setSoTimeout(5000);

                    DataInputStream in = new DataInputStream(
                            new BufferedInputStream(
                                    socket.getInputStream()));

                    String id = in.readUTF();
                    String name = in.readUTF();
                    int length = in.readInt();

                    if (!id.equals(peer.id()) ||
                            name.isBlank() ||
                            name.length() > 100 ||
                            length < 0 || length > 1048576) {
                        throw new IOException(
                                "Invalid remote profile");
                    }

                    byte[] avatar = new byte[length];
                    in.readFully(avatar);

                    saveContact(id, name,
                            System.currentTimeMillis(), avatar);
                    notifyChanged();
                }

            } catch (Exception e) {
                report(e);
            }
        });
    }

    private void saveContact(
            String id, String name,
            long seen, byte[] avatar) throws SQLException {

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                    INSERT INTO contacts
                    (owner_id, peer_id, username, avatar, last_seen)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(owner_id, peer_id)
                    DO UPDATE SET
                        username=excluded.username,
                        last_seen=excluded.last_seen,
                        avatar=COALESCE(excluded.avatar, contacts.avatar)
                    """)) {

            p.setString(1, self.id());
            p.setString(2, id);
            p.setString(3, name);
            p.setBytes(4, avatar);
            p.setLong(5, seen);
            p.executeUpdate();
        }
    }

    @Override
    public void onPeerOffline(String peerId) {
        active.remove(peerId);
        lastRefresh.remove(peerId);
        workers.execute(this::notifyChanged);
    }

    public List<Contact> getContacts() throws SQLException {
        Set<String> online = new HashSet<>();

        for (PeerInfo peer : network.getPeers()) {
            online.add(peer.id());
        }

        List<Contact> contacts = new ArrayList<>();

        try (Connection c = DatabaseManager.open();
             PreparedStatement p = c.prepareStatement("""
                    SELECT peer_id, username, avatar, last_seen
                    FROM contacts WHERE owner_id=?
                    ORDER BY username COLLATE NOCASE
                    """)) {

            p.setString(1, self.id());

            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    byte[] avatar = rs.getBytes("avatar");

                    contacts.add(new Contact(
                            rs.getString("peer_id"),
                            rs.getString("username"),
                            avatar == null ? new byte[0] : avatar,
                            online.contains(rs.getString("peer_id")),
                            rs.getLong("last_seen")
                    ));
                }
            }
        }

        contacts.sort(
                Comparator.comparing(Contact::online).reversed()
                        .thenComparing(Contact::username,
                                String.CASE_INSENSITIVE_ORDER));

        return contacts;
    }

    public void refreshAll() {
        for (PeerInfo peer : network.getPeers()) {
            refreshPeer(peer);
        }
    }

    private void notifyChanged() {
        try {
            changed.accept(getContacts());
        } catch (Exception e) {
            report(e);
        }
    }

    private void report(Throwable e) {
        try {
            errors.accept(e);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public synchronized void close() {
        running = false;
        network.removeListener(this);

        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {}
        }

        workers.shutdownNow();
    }
}
