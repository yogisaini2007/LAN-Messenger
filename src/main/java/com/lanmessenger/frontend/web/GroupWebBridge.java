
package com.lanmessenger.frontend.web;

import com.lanmessenger.app.MessengerEngine;
import com.lanmessenger.backend.peer.PeerInfo;
import com.lanmessenger.backend.service.attachments.AttachmentRepository.Attachment;
import com.lanmessenger.backend.service.groups.GroupModels.*;
import com.lanmessenger.backend.service.groups.GroupService;
import com.lanmessenger.backend.transport.PeerNetwork;
import com.lanmessenger.core.MessengerApi.Profile;
import com.lanmessenger.database.repository.groups.GroupConversationClearStore;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Independent adapter for group chats.
 *
 * Group messaging, attachments and local conversation
 * clearing are isolated from private chat services.
 */
public final class GroupWebBridge
        implements AutoCloseable, GroupService.Listener {

    private static final long MAX_FILE_SIZE =
            100L * 1024 * 1024;

    private final MessengerEngine engine;
    private final WebEngine web;
    private final Stage stage;
    private final Object lock = new Object();

    private final Consumer<Attachment> attachmentListener;

    private volatile GroupService groups;
    private volatile GroupConversationClearStore clearStore;

    private volatile boolean starting;
    private volatile boolean closed;
    private volatile String currentGroupId;

    public GroupWebBridge(
            MessengerEngine engine,
            WebEngine web,
            Stage stage
    ) {
        this.engine = Objects.requireNonNull(engine);
        this.web = Objects.requireNonNull(web);
        this.stage = Objects.requireNonNull(stage);

        attachmentListener = this::onAttachmentChanged;
        engine.addAttachmentListener(attachmentListener);
    }

    // --------------------------------------------------
    // GROUP STARTUP
    // --------------------------------------------------

    public void start() {

        synchronized (lock) {
            if (closed) return;

            if (groups != null) {
                Profile account = engine.currentProfile();

                if (account != null) {
                    emit("ready", Map.of(
                            "selfId", account.id()));
                }

                refresh();
                return;
            }

            if (starting) return;
            starting = true;
        }

        CompletableFuture.runAsync(() -> {

            GroupService candidate = null;

            try {
                Profile account = engine.currentProfile();
                PeerNetwork network =
                        engine.networkForGroups();

                if (account == null || network == null) {
                    throw new IllegalStateException(
                            "Sign in before opening Groups");
                }

                candidate = new GroupService(
                        account.id(),
                        account.username(),
                        network,
                        PeerNetwork.DEFAULT_TCP_PORT
                );

                candidate.addListener(this);
                candidate.start();

                // Clearing is optional.
                // Failure must never stop group messaging.
                try {
                    clearStore =
                            new GroupConversationClearStore(
                                    account.id());

                } catch (SQLException error) {
                    System.err.println(
                            "[Groups] Clear module unavailable: "
                                    + error.getMessage());
                }

                synchronized (lock) {
                    if (closed) {
                        candidate.close();
                        return;
                    }

                    groups = candidate;
                }

                engine.setGroupAttachmentAuthorizer(
                        this::authorizeIncomingAttachment);

                emit("ready", Map.of(
                        "selfId", account.id()));

                refresh();

            } catch (Exception error) {
                if (candidate != null) {
                    candidate.close();
                }

                fail(error);

            } finally {
                synchronized (lock) {
                    starting = false;
                }
            }
        });
    }

    private boolean authorizeIncomingAttachment(
            String groupId,
            String senderId
    ) {
        GroupService service = groups;
        Profile account = engine.currentProfile();

        if (closed ||
                service == null ||
                account == null ||
                groupId == null ||
                senderId == null ||
                groupId.isBlank() ||
                senderId.isBlank()) {

            return false;
        }

        try {
            List<Member> members =
                    service.getMembers(groupId)
                            .get(5, TimeUnit.SECONDS);

            boolean senderMember = false;
            boolean receiverMember = false;

            for (Member member : members) {

                if (member.userId().equals(senderId)) {
                    senderMember = true;
                }

                if (member.userId().equals(account.id())) {
                    receiverMember = true;
                }
            }

            return senderMember && receiverMember;

        } catch (Exception error) {
            return false;
        }
    }

    // --------------------------------------------------
    // GROUP LIST
    // --------------------------------------------------

    public void refresh() {

        GroupService service = groups;

        if (service == null || closed) return;

        service.getGroups().whenComplete((items, error) -> {

            if (error != null) {
                fail(error);
                return;
            }

            List<Object> result = new ArrayList<>();

            for (Group group : items) {
                result.add(groupMap(group));
            }

            emit("groups", result);
        });

        PeerNetwork network =
                engine.networkForGroups();

        if (network != null) {
            List<Object> people = new ArrayList<>();

            for (PeerInfo peer : network.getPeers()) {
                people.add(Map.of(
                        "id", peer.id(),
                        "username", peer.username()
                ));
            }

            emit("peers", people);
        }
    }

    public void createGroup(
            String title,
            String csvIds
    ) {
        GroupService service = required();
        PeerNetwork network =
                engine.networkForGroups();

        if (service == null || network == null) return;

        Set<String> ids = new LinkedHashSet<>();

        if (csvIds != null) {
            for (String id : csvIds.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }

        List<PeerInfo> invitees =
                network.getPeers()
                        .stream()
                        .filter(peer ->
                                ids.contains(peer.id()))
                        .toList();

        if (ids.isEmpty() ||
                invitees.size() != ids.size()) {

            emit("error",
                    "Select at least one currently online member");
            return;
        }

        service.createGroup(title, invitees, null)
                .whenComplete((group, error) -> {

                    if (error != null) {
                        fail(error);

                    } else {
                        emit("created", group.id());
                        refresh();
                    }
                });
    }

    // --------------------------------------------------
    // GROUP HISTORY
    // --------------------------------------------------

    public void openGroup(String groupId) {

        GroupService service = required();

        if (service == null ||
                groupId == null ||
                groupId.isBlank()) {
            return;
        }

        currentGroupId = groupId;

        service.getHistory(groupId)
                .whenComplete((items, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    List<Message> visible = items;

                    GroupConversationClearStore store =
                            clearStore;

                    if (store != null) {
                        try {
                            visible = store.visibleHistory(
                                    groupId,
                                    items
                            );

                        } catch (SQLException e) {
                            fail(e);
                            return;
                        }
                    }

                    List<Object> history =
                            new ArrayList<>();

                    for (Message message : visible) {
                        history.add(messageMap(message));
                    }

                    emit("history", Map.of(
                            "groupId", groupId,
                            "messages", history
                    ));
                });

        loadMembers(groupId);
        loadGroupAttachments(groupId);
    }

    public void loadMembers(String groupId) {

        GroupService service = required();
        if (service == null) return;

        service.getMembers(groupId)
                .whenComplete((items, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    List<Object> result = new ArrayList<>();

                    for (Member member : items) {
                        result.add(memberMap(member));
                    }

                    emit("members", Map.of(
                            "groupId", groupId,
                            "members", result
                    ));
                });
    }

    public void sendMessage(
            String groupId,
            String text
    ) {
        GroupService service = required();
        if (service == null) return;

        service.sendMessage(groupId, text)
                .whenComplete((message, error) -> {

                    if (error != null) {
                        fail(error);
                    }
                });
    }

    // --------------------------------------------------
    // STEP 9.4: CLEAR ENTIRE GROUP CONVERSATION
    // --------------------------------------------------

    /**
     * Clears the full group conversation only for
     * the currently signed-in local account.
     *
     * Messages and attachment cards are cleared
     * in one database transaction.
     *
     * Group membership, settings and saved file bytes
     * are preserved.
     */
    public void clearGroupConversation(String groupId) {

        GroupService service = required();

        if (service == null ||
                groupId == null ||
                groupId.isBlank()) {
            return;
        }

        Profile account = engine.currentProfile();

        if (account == null) {
            emit("error", "Sign in first");
            return;
        }

        GroupConversationClearStore store = clearStore;

        if (store == null) {
            emit("error",
                    "Group conversation clearing is unavailable");
            return;
        }

        // Verify that the local user belongs to this group.
        service.getMembers(groupId)
                .thenCompose(members -> {

                    boolean member = members.stream()
                            .anyMatch(m ->
                                    account.id().equals(m.userId()));

                    if (!member) {
                        return CompletableFuture.failedFuture(
                                new SecurityException(
                                        "You are not a member of this group"));
                    }

                    return CompletableFuture.supplyAsync(() -> {

                        try {
                            return store.clearGroup(groupId);

                        } catch (SQLException e) {
                            throw new CompletionException(e);
                        }
                    });
                })
                .whenComplete((removed, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    emit("conversationCleared", Map.of(
                            "groupId", groupId,
                            "removed", removed
                    ));

                    // Reload filtered group history.
                    if (groupId.equals(currentGroupId)) {
                        openGroup(groupId);
                    }
                });
    }

    // --------------------------------------------------
    // GROUP FILE SHARING
    // --------------------------------------------------

    public void loadGroupAttachments(String groupId) {

        if (groupId == null ||
                groupId.isBlank() ||
                closed) {
            return;
        }

        engine.getAttachmentHistory(
                        "GROUP",
                        groupId)
                .whenComplete((items, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    List<Object> result = new ArrayList<>();

                    for (Attachment item : items) {
                        result.add(attachmentMap(item));
                    }

                    emit("attachmentHistory", Map.of(
                            "groupId", groupId,
                            "items", result
                    ));
                });
    }

    public void shareGroupFile(String groupId) {

        GroupService service = required();

        if (service == null) return;

        if (groupId == null || groupId.isBlank()) {
            emit("error", "Select a group first");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Share a file with your group");

        File selected = chooser.showOpenDialog(stage);

        if (selected == null) return;

        if (!selected.isFile() ||
                selected.length() < 0 ||
                selected.length() > MAX_FILE_SIZE) {

            emit("error",
                    "Choose a file no larger than 100 MB");
            return;
        }

        service.getMembers(groupId)
                .thenCompose(members -> {

                    Profile account =
                            engine.currentProfile();

                    if (account == null) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Sign in first"));
                    }

                    boolean member = members.stream()
                            .anyMatch(m ->
                                    m.userId().equals(account.id()));

                    if (!member) {
                        return CompletableFuture.failedFuture(
                                new SecurityException(
                                        "Not a group member"));
                    }

                    List<String> recipients =
                            members.stream()
                                    .map(Member::userId)
                                    .filter(id ->
                                            !id.equals(account.id()))
                                    .distinct()
                                    .toList();

                    if (recipients.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "No other group members"));
                    }

                    emit("notice",
                            "Preparing file for "
                                    + recipients.size()
                                    + " group member(s)...");

                    return engine.sendGroupAttachment(
                            groupId,
                            recipients,
                            selected.toPath()
                    );
                })
                .whenComplete((item, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    emit("notice",
                            "Group file queued: "
                                    + item.filename());

                    loadGroupAttachments(groupId);
                });
    }

    public void openGroupAttachment(
            String attachmentId
    ) {
        if (attachmentId == null ||
                attachmentId.isBlank()) {
            return;
        }

        engine.openAttachment(attachmentId)
                .whenComplete((unused, error) -> {

                    if (error != null) {
                        fail(error);
                    }
                });
    }

    public void deleteGroupAttachment(
            String groupId,
            String attachmentId
    ) {
        if (groupId == null ||
                attachmentId == null ||
                groupId.isBlank() ||
                attachmentId.isBlank()) {
            return;
        }

        engine.getAttachmentHistory(
                        "GROUP",
                        groupId)
                .thenCompose(items -> {

                    boolean present = items.stream()
                            .anyMatch(item ->
                                    attachmentId.equals(item.id()));

                    if (!present) {
                        return CompletableFuture.failedFuture(
                                new IllegalArgumentException(
                                        "Attachment not found"));
                    }

                    return engine.deleteAttachmentForMe(
                            attachmentId);
                })
                .whenComplete((unused, error) -> {

                    if (error != null) {
                        fail(error);

                    } else {
                        loadGroupAttachments(groupId);
                    }
                });
    }

    public void forwardGroupAttachment(
            String sourceGroupId,
            String attachmentId,
            String targetGroupId
    ) {
        GroupService service = required();

        if (service == null) return;

        if (sourceGroupId == null ||
                attachmentId == null ||
                targetGroupId == null ||
                sourceGroupId.isBlank() ||
                attachmentId.isBlank() ||
                targetGroupId.isBlank()) {

            emit("error",
                    "Select a file and destination group");
            return;
        }

        engine.getAttachmentHistory(
                        "GROUP",
                        sourceGroupId)
                .thenCompose(items -> {

                    Attachment original =
                            items.stream()
                                    .filter(item ->
                                            attachmentId.equals(
                                                    item.id()))
                                    .findFirst()
                                    .orElseThrow(() ->
                                            new IllegalArgumentException(
                                                    "Attachment not found"));

                    if (original.localPath() == null ||
                            original.localPath().isBlank()) {

                        throw new IllegalStateException(
                                "File is not available locally");
                    }

                    Path source =
                            Path.of(original.localPath());

                    if (!Files.isRegularFile(source)) {
                        throw new IllegalStateException(
                                "Local file is missing");
                    }

                    return service.getMembers(targetGroupId)
                            .thenCompose(members -> {

                                Profile account =
                                        engine.currentProfile();

                                if (account == null) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "Sign in first"));
                                }

                                boolean member = members.stream()
                                        .anyMatch(m ->
                                                m.userId().equals(
                                                        account.id()));

                                if (!member) {
                                    return CompletableFuture.failedFuture(
                                            new SecurityException(
                                                    "Not a member of target group"));
                                }

                                List<String> recipients =
                                        members.stream()
                                                .map(Member::userId)
                                                .filter(id ->
                                                        !id.equals(
                                                                account.id()))
                                                .distinct()
                                                .toList();

                                if (recipients.isEmpty()) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "No other group members"));
                                }

                                return engine.sendGroupAttachment(
                                        targetGroupId,
                                        recipients,
                                        source
                                );
                            });
                })
                .whenComplete((item, error) -> {

                    if (error != null) {
                        fail(error);

                    } else {
                        emit("notice",
                                "File queued for forwarding");
                        loadGroupAttachments(targetGroupId);
                    }
                });
    }

    /**
     * Reload visible group attachments.
     * A hidden file must not reappear from a late event.
     */
    private void onAttachmentChanged(Attachment item) {

        if (closed ||
                !"GROUP".equals(item.chatType())) {
            return;
        }

        if (item.chatId().equals(currentGroupId)) {
            loadGroupAttachments(item.chatId());
        }
    }

    private static Map<String, Object> attachmentMap(
            Attachment item
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("id", item.id());
        result.put("groupId", item.chatId());
        result.put("chatType", item.chatType());
        result.put("senderId", item.senderId());
        result.put("filename", item.filename());
        result.put("mimeType", item.mimeType());
        result.put("size", item.size());
        result.put("timestamp", item.createdAt());
        result.put("status", item.status());

        boolean available = false;

        if (item.localPath() != null &&
                !item.localPath().isBlank()) {

            try {
                available = Files.isRegularFile(
                        Path.of(item.localPath()));

            } catch (RuntimeException ignored) {
            }
        }

        result.put("available", available);
        return result;
    }

    // --------------------------------------------------
    // EXISTING GROUP ADMINISTRATION
    // --------------------------------------------------

    public void deleteMessage(
            String messageId,
            boolean everyone
    ) {
        GroupService service = required();
        if (service == null) return;

        CompletableFuture<Void> job =
                everyone
                        ? service.deleteForEveryone(messageId)
                        : service.deleteForMe(messageId);

        job.whenComplete((unused, error) -> {

            if (error != null) {
                fail(error);

            } else {
                emit("deleted", messageId);
            }
        });
    }

    public void renameGroup(
            String groupId,
            String title
    ) {
        GroupService service = required();
        if (service == null) return;

        finish(
                service.renameGroup(groupId, title),
                groupId);
    }

    public void changeGroupAvatar(String groupId) {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose group photo");

        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Images",
                        "*.png",
                        "*.jpg",
                        "*.jpeg"
                )
        );

        File image = chooser.showOpenDialog(stage);

        if (image == null) return;

        GroupService service = required();
        if (service == null) return;

        finish(
                service.changeGroupAvatar(
                        groupId,
                        image.toPath()),
                groupId
        );
    }

    public void addMember(
            String groupId,
            String peerId
    ) {
        GroupService service = required();
        PeerNetwork network =
                engine.networkForGroups();

        if (service == null || network == null) return;

        PeerInfo peer = network.getPeers()
                .stream()
                .filter(p ->
                        p.id().equals(peerId))
                .findFirst()
                .orElse(null);

        if (peer == null) {
            emit("error",
                    "Person must be online to be added");
            return;
        }

        finish(
                service.addMember(groupId, peer),
                groupId);
    }

    public void removeMember(
            String groupId,
            String peerId
    ) {
        GroupService service = required();

        if (service == null) return;

        finish(
                service.removeMember(
                        groupId,
                        peerId),
                groupId);
    }

    public void setAdmin(
            String groupId,
            String peerId,
            boolean admin
    ) {
        GroupService service = required();

        if (service == null) return;

        finish(
                service.setAdmin(
                        groupId,
                        peerId,
                        admin),
                groupId);
    }

    public void leaveGroup(String groupId) {
        GroupService service = required();

        if (service == null) return;

        finish(
                service.leaveGroup(groupId),
                groupId);
    }

    private void finish(
            CompletableFuture<Void> operation,
            String groupId
    ) {
        operation.whenComplete((unused, error) -> {

            if (error != null) {
                fail(error);

            } else {
                refresh();
                loadMembers(groupId);
                emit("updated", groupId);
            }
        });
    }

    private GroupService required() {

        GroupService service = groups;

        if (closed || service == null) {
            emit("error",
                    "Group chat is not available");
            return null;
        }

        return service;
    }

    // --------------------------------------------------
    // GROUP EVENTS
    // --------------------------------------------------

    @Override
    public void onGroupsChanged() {
        refresh();
    }

    @Override
    public void onGroupMessage(Message message) {

        GroupConversationClearStore store = clearStore;

        if (store != null) {
            try {
                if (!store.isVisible(
                        message.groupId(),
                        message.timestamp())) {
                    return;
                }

            } catch (SQLException error) {
                fail(error);
                return;
            }
        }

        emit("message", messageMap(message));

        Profile account = engine.currentProfile();

        if (account != null &&
                !message.senderId().equals(account.id())) {

            DesktopNotifier.showOnce(
                    message.id(),
                    "LAN Messenger - Group message",
                    message.text()
            );
        }
    }

    @Override
    public void onError(
            String operation,
            Throwable error
    ) {
        fail(error);
    }

    // --------------------------------------------------
    // UI SERIALIZATION
    // --------------------------------------------------

    private static Map<String, Object> groupMap(
            Group group
    ) {
        return Map.of(
                "id", group.id(),
                "name", group.name(),
                "creatorId", group.creatorId(),
                "revision", group.revision(),
                "avatar",
                group.avatar().length == 0
                        ? ""
                        : "data:image/png;base64,"
                                + Base64.getEncoder()
                                        .encodeToString(
                                                group.avatar())
        );
    }

    private static Map<String, Object> memberMap(
            Member member
    ) {
        return Map.of(
                "id", member.userId(),
                "name", member.username(),
                "role", member.role().name()
        );
    }

    private static Map<String, Object> messageMap(
            Message message
    ) {
        return Map.of(
                "id", message.id(),
                "groupId", message.groupId(),
                "senderId", message.senderId(),
                "text", message.text(),
                "timestamp", message.timestamp(),
                "deleted",
                message.deletedForEveryone()
        );
    }

    private void fail(Throwable error) {

        Throwable root = error;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        emit("error",
                root.getMessage() == null
                        ? "Group operation failed"
                        : root.getMessage());
    }

    private void emit(
            String type,
            Object payload
    ) {
        if (closed) return;

        Platform.runLater(() -> {

            if (closed) return;

            try {
                web.executeScript(
                        "if(window.LANGroups&&window.LANGroups.receive)"
                                + "window.LANGroups.receive("
                                + json(type)
                                + ","
                                + json(payload)
                                + ")"
                );

            } catch (Exception error) {
                System.err.println(
                        "Group UI event: "
                                + error.getMessage());
            }
        });
    }

    private static String json(Object value) {

        if (value == null) return "null";

        if (value instanceof Boolean ||
                value instanceof Number) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {

            StringJoiner join =
                    new StringJoiner(",", "{", "}");

            map.forEach((key, item) ->
                    join.add(
                            json(String.valueOf(key))
                                    + ":"
                                    + json(item)
                    )
            );

            return join.toString();
        }

        if (value instanceof Collection<?> values) {

            StringJoiner join =
                    new StringJoiner(",", "[", "]");

            for (Object item : values) {
                join.add(json(item));
            }

            return join.toString();
        }

        String str = String.valueOf(value);
        StringBuilder result = new StringBuilder("\"");

        for (int i = 0; i < str.length(); i++) {

            char c = str.charAt(i);

            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");

                default -> {
                    if (c < 32 ||
                            c == 0x2028 ||
                            c == 0x2029) {

                        result.append(
                                String.format(
                                        "\\u%04x",
                                        (int) c)
                        );

                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.append('"').toString();
    }

    @Override
    public void close() {

        closed = true;

        engine.removeAttachmentListener(
                attachmentListener);

        engine.setGroupAttachmentAuthorizer(
                (groupId, senderId) -> false);

        GroupService old;

        synchronized (lock) {
            old = groups;
            groups = null;
            clearStore = null;
        }

        if (old != null) {
            old.close();
        }
    }
}
