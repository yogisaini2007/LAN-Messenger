
package com.lanmessenger.backend.service.groups;

import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.backend.service.groups.GroupModels.*;
import com.lanmessenger.database.repository.groups.GroupRepository;
import com.lanmessenger.util.AvatarImages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class GroupService
        implements AutoCloseable, PeerNetwork.Listener {

    public interface Listener {
        default void onGroupsChanged() {}
        default void onGroupMessage(Message message) {}
        default void onError(String operation, Throwable error) {}
    }

    private final String selfId;
    private final String selfName;
    private final PeerNetwork peers;
    private final GroupRepository store;
    private final GroupTransport transport;

    private final Object rosterLock = new Object();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private final ExecutorService work =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lan-group-service");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService retry =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lan-group-retry");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean started;

    public GroupService(
            String selfId,
            String selfName,
            PeerNetwork peers,
            int tcpBasePort) throws SQLException {

        this.selfId = Objects.requireNonNull(selfId);
        this.selfName = Objects.requireNonNull(selfName);
        this.peers = Objects.requireNonNull(peers);

        store = new GroupRepository(selfId);
        transport = new GroupTransport(peers, selfId, tcpBasePort);

        transport.setReceiver((packet, address) -> {
            try {
                receive(packet);
            } catch (Exception e) {
                error("receiving group packet", e);
                throw new IllegalStateException(e);
            }
        });
    }

    public synchronized void start() throws IOException {
        if (started) return;

        transport.start();
        started = true;
        peers.addListener(this);

        retry.scheduleWithFixedDelay(
                this::flushAll, 2, 5, TimeUnit.SECONDS);
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<List<Group>> getGroups() {
        return task(store::groups);
    }

    public CompletableFuture<List<Member>> getMembers(String groupId) {
        return task(() -> {
            List<Member> members = store.members(groupId);
            requireMember(members, selfId);
            return members;
        });
    }

    public CompletableFuture<List<Message>> getHistory(String groupId) {
        return task(() -> {
            requireMember(store.members(groupId), selfId);
            return store.history(groupId);
        });
    }

    public CompletableFuture<Group> createGroup(
            String title,
            List<PeerInfo> invitees,
            Path picture) {

        return task(() -> {
            synchronized (rosterLock) {
                String name = checkName(title);

                byte[] avatar = picture == null
                        ? new byte[0]
                        : AvatarImages.process(picture);

                if (invitees == null || invitees.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Choose at least one member");
                }

                String id = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();

                Group group = new Group(
                        id, name, selfId, 1, now, avatar);

                List<Member> roster = new ArrayList<>();
                roster.add(new Member(
                        id, selfId, selfName, Role.OWNER, now));

                Set<String> unique = new HashSet<>();
                unique.add(selfId);

                for (PeerInfo peer : invitees) {
                    if (unique.add(peer.id())) {
                        roster.add(new Member(
                                id, peer.id(), peer.username(),
                                Role.MEMBER, now));
                    }
                }

                if (roster.size() < 2 || roster.size() > 100) {
                    throw new IllegalArgumentException(
                            "Group requires 2 to 100 members");
                }

                store.saveRoster(group, roster);
                queueRoster(group, roster, List.of());
                changed();

                return group;
            }
        });
    }

    public CompletableFuture<Void> renameGroup(
            String id, String title) {

        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> members = store.members(id);

                requireManager(members);

                Group updated = new Group(
                        g.id(),
                        checkName(title),
                        g.creatorId(),
                        g.revision() + 1,
                        g.createdAt(),
                        g.avatar());

                persistRoster(updated, members, members);
                return null;
            }
        });
    }

    public CompletableFuture<Void> changeGroupAvatar(
            String id, Path image) {

        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> members = store.members(id);

                requireManager(members);

                Group updated = new Group(
                        g.id(),
                        g.name(),
                        g.creatorId(),
                        g.revision() + 1,
                        g.createdAt(),
                        AvatarImages.process(image));

                persistRoster(updated, members, members);
                return null;
            }
        });
    }

    public CompletableFuture<Void> addMember(
            String id, PeerInfo newcomer) {

        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> members = store.members(id);

                requireOwner(members);

                boolean present = members.stream()
                        .anyMatch(m ->
                                m.userId().equals(newcomer.id()));

                if (present) return null;

                if (members.size() >= 100) {
                    throw new IllegalArgumentException("Group is full");
                }

                List<Member> updated = new ArrayList<>(members);

                updated.add(new Member(
                        id,
                        newcomer.id(),
                        newcomer.username(),
                        Role.MEMBER,
                        System.currentTimeMillis()));

                persistRoster(nextRevision(g), updated, members);
                return null;
            }
        });
    }

    public CompletableFuture<Void> removeMember(
            String id, String userId) {

        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> members = store.members(id);

                requireOwner(members);

                if (userId.equals(g.creatorId())) {
                    throw new IllegalArgumentException(
                            "Group owner cannot be removed");
                }

                List<Member> updated = new ArrayList<>(members);

                if (!updated.removeIf(m ->
                        m.userId().equals(userId))) {
                    return null;
                }

                persistRoster(nextRevision(g), updated, members);
                return null;
            }
        });
    }

    public CompletableFuture<Void> setAdmin(
            String id, String userId, boolean admin) {

        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> members = store.members(id);

                requireOwner(members);

                if (userId.equals(g.creatorId())) {
                    throw new IllegalArgumentException(
                            "Owner role cannot be changed");
                }

                List<Member> updated = new ArrayList<>();
                boolean exists = false;

                for (Member m : members) {
                    if (m.userId().equals(userId)) {
                        updated.add(new Member(
                                id,
                                userId,
                                m.username(),
                                admin ? Role.ADMIN : Role.MEMBER,
                                m.joinedAt()));
                        exists = true;
                    } else {
                        updated.add(m);
                    }
                }

                if (!exists) {
                    throw new IllegalArgumentException(
                            "Member not found");
                }

                persistRoster(nextRevision(g), updated, members);
                return null;
            }
        });
    }

    public CompletableFuture<Void> leaveGroup(String id) {
        return task(() -> {
            synchronized (rosterLock) {
                Group g = requiredGroup(id);
                List<Member> previous = store.members(id);

                requireMember(previous, selfId);

                if (selfId.equals(g.creatorId())) {
                    throw new IllegalArgumentException(
                            "Owner cannot leave without transferring ownership");
                }

                List<Member> updated = new ArrayList<>(previous);
                updated.removeIf(m -> m.userId().equals(selfId));

                store.saveRoster(nextRevision(g), updated);

                queueToMembers(
                        UUID.randomUUID().toString(),
                        "LEAVE",
                        id,
                        System.currentTimeMillis(),
                        new byte[0],
                        previous,
                        List.of());

                changed();
                return null;
            }
        });
    }

    public CompletableFuture<Message> sendMessage(
            String groupId, String text) {

        return task(() -> {
            requiredGroup(groupId);

            List<Member> members = store.members(groupId);
            requireMember(members, selfId);

            if (text == null || text.isBlank() ||
                    text.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException(
                        "Message must contain 1 to 16384 bytes");
            }

            Message message = new Message(
                    UUID.randomUUID().toString(),
                    groupId,
                    selfId,
                    text,
                    System.currentTimeMillis(),
                    false);

            store.saveMessage(message);

            queueToMembers(
                    message.id(),
                    "CHAT",
                    groupId,
                    message.timestamp(),
                    text.getBytes(StandardCharsets.UTF_8),
                    members,
                    List.of());

            fire(l -> l.onGroupMessage(message));
            return message;
        });
    }

    public CompletableFuture<Void> deleteForMe(String messageId) {
        return task(() -> {
            store.deleteForMe(messageId);
            changed();
            return null;
        });
    }

    public CompletableFuture<Void> deleteForEveryone(
            String messageId) {

        return task(() -> {
            Message message = store.findMessage(messageId);

            if (message == null ||
                    !selfId.equals(message.senderId())) {
                throw new SecurityException(
                        "You may only delete your own group message");
            }

            List<Member> members = store.members(message.groupId());
            requireMember(members, selfId);

            store.deleteForEveryone(
                    message.groupId(), message.id(), selfId);

            // Cancel unsent copies of the original message.
            for (Member member : members) {
                if (!member.userId().equals(selfId)) {
                    store.acknowledge(message.id(), member.userId());
                }
            }

            queueToMembers(
                    UUID.randomUUID().toString(),
                    "DELETE_GLOBAL",
                    message.groupId(),
                    System.currentTimeMillis(),
                    message.id().getBytes(StandardCharsets.UTF_8),
                    members,
                    List.of());

            changed();
            return null;
        });
    }

    private Group requiredGroup(String id) throws SQLException {
        Group group = store.findGroup(id);

        if (group == null) {
            throw new IllegalArgumentException("Group not found");
        }

        return group;
    }

    private static String checkName(String value) {
        if (value == null || value.isBlank() ||
                value.trim().length() > 80) {
            throw new IllegalArgumentException(
                    "Group name must contain 1 to 80 characters");
        }
        return value.trim();
    }

    private static Group nextRevision(Group g) {
        return new Group(
                g.id(), g.name(), g.creatorId(),
                g.revision() + 1, g.createdAt(), g.avatar());
    }

    private static void requireMember(
            List<Member> members, String userId) {

        if (members.stream().noneMatch(m ->
                m.userId().equals(userId))) {
            throw new SecurityException("Not a group member");
        }
    }

    private void requireOwner(List<Member> members) {
        if (members.stream().noneMatch(m ->
                m.userId().equals(selfId) &&
                        m.role() == Role.OWNER)) {
            throw new SecurityException(
                    "Only the group owner may manage members");
        }
    }

    private void requireManager(List<Member> members) {
        if (members.stream().noneMatch(m ->
                m.userId().equals(selfId) &&
                        m.role() != Role.MEMBER)) {
            throw new SecurityException(
                    "Only an admin can edit group details");
        }
    }

    private void persistRoster(
            Group group,
            List<Member> updated,
            List<Member> previous) throws Exception {

        store.saveRoster(group, updated);
        queueRoster(group, updated, previous);
        changed();
    }

    private void queueRoster(
            Group group,
            List<Member> members,
            List<Member> previous) throws Exception {

        queueToMembers(
                UUID.randomUUID().toString(),
                "ROSTER",
                group.id(),
                System.currentTimeMillis(),
                encodeRoster(group, members),
                members,
                previous);
    }

    private void queueToMembers(
            String eventId,
            String type,
            String groupId,
            long timestamp,
            byte[] payload,
            List<Member> current,
            List<Member> previous) throws Exception {

        Set<String> recipients = new LinkedHashSet<>();

        for (Member m : current) recipients.add(m.userId());
        for (Member m : previous) recipients.add(m.userId());

        recipients.remove(selfId);

        for (String peerId : recipients) {
            GroupPacket packet = GroupPacket.create(
                    eventId, type, selfId, peerId,
                    groupId, timestamp, payload);

            store.queue(eventId, peerId, packet.encode());
            flushLater(peerId);
        }
    }

    private static byte[] encodeRoster(
            Group group, List<Member> members) throws IOException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (DataOutputStream out =
                     new DataOutputStream(buffer)) {

            out.writeUTF(group.id());
            out.writeUTF(group.name());
            out.writeUTF(group.creatorId());
            out.writeLong(group.revision());
            out.writeLong(group.createdAt());

            byte[] avatar = group.avatar();
            out.writeInt(avatar.length);
            out.write(avatar);

            out.writeInt(members.size());

            for (Member m : members) {
                out.writeUTF(m.userId());
                out.writeUTF(m.username());
                out.writeUTF(m.role().name());
                out.writeLong(m.joinedAt());
            }
        }

        return buffer.toByteArray();
    }

    private record Roster(Group group, List<Member> members) {}

    private static Roster decodeRoster(byte[] bytes) throws Exception {
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes));

        String id = in.readUTF();
        String name = in.readUTF();
        String creator = in.readUTF();

        long revision = in.readLong();
        long created = in.readLong();

        int size = in.readInt();

        if (size < 0 || size > 1024 * 1024) {
            throw new IOException("Invalid group avatar size");
        }

        byte[] avatar = new byte[size];
        in.readFully(avatar);

        Group group = new Group(
                id, name, creator, revision, created, avatar);

        int count = in.readInt();

        if (count < 1 || count > 100) {
            throw new IOException("Invalid group size");
        }

        List<Member> members = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int owners = 0;

        for (int i = 0; i < count; i++) {
            String person = in.readUTF();
            String username = in.readUTF();
            Role role = Role.valueOf(in.readUTF());
            long joined = in.readLong();

            if (!ids.add(person)) {
                throw new IOException("Duplicate group member");
            }

            if (role == Role.OWNER) {
                owners++;

                if (!person.equals(creator)) {
                    throw new IOException("Invalid group owner");
                }
            }

            members.add(new Member(
                    id, person, username, role, joined));
        }

        if (owners != 1 || in.available() != 0) {
            throw new IOException("Invalid group roster");
        }

        return new Roster(group, members);
    }

    private void receive(GroupPacket packet) throws Exception {
        switch (packet.type()) {

            case "ROSTER" -> {
                synchronized (rosterLock) {
                    Roster incoming = decodeRoster(packet.payload());
                    Group group = incoming.group();

                    if (!group.id().equals(packet.groupId())) {
                        throw new SecurityException("Wrong group");
                    }

                    Group existing = store.findGroup(group.id());

                    if (existing == null) {
                        if (!group.creatorId().equals(packet.from())) {
                            throw new SecurityException(
                                    "New group must come from its creator");
                        }

                        requireMember(incoming.members(), selfId);

                    } else {
                        if (!existing.creatorId().equals(
                                group.creatorId()) ||
                                existing.createdAt() != group.createdAt()) {
                            throw new SecurityException(
                                    "Group identity changed");
                        }

                        List<Member> old = store.members(group.id());

                        boolean owner = old.stream().anyMatch(m ->
                                m.userId().equals(packet.from()) &&
                                        m.role() == Role.OWNER);

                        boolean admin = old.stream().anyMatch(m ->
                                m.userId().equals(packet.from()) &&
                                        m.role() == Role.ADMIN);

                        if (!owner && !admin) {
                            throw new SecurityException(
                                    "Sender is not a group administrator");
                        }

                        if (!owner) {
                            if (old.size() != incoming.members().size()) {
                                throw new SecurityException(
                                        "Owner required for member changes");
                            }

                            for (Member m : old) {
                                boolean unchanged =
                                        incoming.members().stream()
                                                .anyMatch(x ->
                                                        x.userId().equals(
                                                                m.userId()) &&
                                                        x.role() == m.role());

                                if (!unchanged) {
                                    throw new SecurityException(
                                            "Owner required for role changes");
                                }
                            }
                        }
                    }

                    if (store.saveRoster(
                            group, incoming.members())) {
                        changed();
                    }
                }
            }

            case "CHAT" -> {
                requiredGroup(packet.groupId());

                List<Member> members =
                        store.members(packet.groupId());

                requireMember(members, selfId);
                requireMember(members, packet.from());

                byte[] bytes = packet.payload();

                if (bytes.length == 0 || bytes.length > 16384) {
                    throw new IOException("Invalid group message size");
                }

                Message message = new Message(
                        packet.id(),
                        packet.groupId(),
                        packet.from(),
                        new String(bytes, StandardCharsets.UTF_8),
                        packet.timestamp(),
                        false);

                store.saveMessage(message);

                Message saved = store.findMessage(packet.id());

                if (saved != null) {
                    fire(l -> l.onGroupMessage(saved));
                }
            }

            case "LEAVE" -> {
                synchronized (rosterLock) {
                    Group group = requiredGroup(packet.groupId());
                    List<Member> old = store.members(group.id());

                    if (packet.from().equals(group.creatorId())) {
                        throw new SecurityException(
                                "Group owner cannot leave directly");
                    }

                    boolean member = old.stream().anyMatch(m ->
                            m.userId().equals(packet.from()));

                    if (!member) return;

                    List<Member> updated = new ArrayList<>(old);
                    updated.removeIf(m ->
                            m.userId().equals(packet.from()));

                    if (store.saveRoster(
                            nextRevision(group), updated)) {
                        changed();
                    }
                }
            }

            case "DELETE_GLOBAL" -> {
                requiredGroup(packet.groupId());

                requireMember(
                        store.members(packet.groupId()),
                        packet.from());

                String messageId = new String(
                        packet.payload(),
                        StandardCharsets.UTF_8);

                if (messageId.isBlank() ||
                        messageId.length() > 100) {
                    throw new IOException("Invalid delete event");
                }

                store.deleteForEveryone(
                        packet.groupId(),
                        messageId,
                        packet.from());

                changed();
            }

            default -> throw new IOException(
                    "Unsupported group packet");
        }
    }

    private void flushAll() {
        if (!started) return;

        try {
            for (PeerInfo peer : peers.getPeers()) {
                flushLater(peer.id());
            }
        } catch (RuntimeException e) {
            error("retrying group events", e);
        }
    }

    private void flushLater(String peerId) {
        if (!started || !busy.add(peerId)) return;

        try {
            work.execute(() -> {
                try {
                    PeerInfo peer = peers.getPeers().stream()
                            .filter(p -> p.id().equals(peerId))
                            .findFirst()
                            .orElse(null);

                    if (peer == null) return;

                    List<GroupRepository.Pending> pending =
                            new ArrayList<>(store.pending(peerId));

                    // Roster changes are delivered before chat
                    // events so newly invited peers know the group.
                    // Deliver group events in their original queue order.

                    for (GroupRepository.Pending item : pending) {
                        GroupPacket packet = GroupPacket.read(
                                new DataInputStream(
                                        new ByteArrayInputStream(
                                                item.payload())));

                        boolean accepted = transport.send(
                                peer, packet).get(10, TimeUnit.SECONDS);

                        if (!accepted) break;

                        store.acknowledge(
                                item.eventId(), peerId);
                    }

                } catch (Exception e) {
                    error("group outbox", e);

                } finally {
                    busy.remove(peerId);
                }
            });

        } catch (RejectedExecutionException e) {
            busy.remove(peerId);
        }
    }

    private static boolean isRoster(
            GroupRepository.Pending event) {

        try {
            GroupPacket packet = GroupPacket.read(
                    new DataInputStream(
                            new ByteArrayInputStream(
                                    event.payload())));

            return packet.type().equals("ROSTER");

        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void onPeerOnline(PeerInfo peer) {
        flushLater(peer.id());
    }

    private void changed() {
        fire(Listener::onGroupsChanged);
    }

    private void error(String operation, Throwable cause) {
        fire(l -> l.onError(operation, cause));
    }

    private void fire(Consumer<Listener> event) {
        for (Listener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException ignored) {
                // UI failure cannot stop group networking.
            }
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> task(Operation<T> operation) {
        if (!started) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Group service is not running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();

            } catch (Exception e) {
                error("group operation", e);
                throw new CompletionException(e);
            }
        }, work);
    }

    @Override
    public synchronized void close() {
        started = false;

        peers.removeListener(this);
        retry.shutdownNow();
        transport.close();
        work.shutdownNow();
    }
}
