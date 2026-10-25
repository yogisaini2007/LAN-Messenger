
package com.lanmessenger.frontend.web;

import com.lanmessenger.app.MessengerEngine;
import com.lanmessenger.backend.service.attachments.AttachmentRepository.Attachment;
import com.lanmessenger.core.MessengerEvents;
import com.lanmessenger.core.MessengerApi.*;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

public final class WebBridge implements MessengerEvents {

    private final MessengerEngine app;
    private final WebEngine web;
    private final Stage stage;

    private final Preferences prefs =
            Preferences.userNodeForPackage(WebBridge.class);

    private Path newAvatar;
    private volatile Profile myProfile;
    private volatile String currentPeer;

    public WebBridge(
            MessengerEngine app,
            WebEngine web,
            Stage stage) {

        this.app = app;
        this.web = web;
        this.stage = stage;

        app.addListener(this);
        app.addAttachmentListener(this::onAttachmentChanged);
    }

    public String getTheme() {
        return prefs.get("theme", "midnight");
    }

    public void saveTheme(String name) {
        if (Set.of("midnight", "aurora", "frost", "pearl", "sunrise")
                .contains(name)) {
            prefs.put("theme", name);
        }
    }

    public void chooseRegistrationPhoto() {
        File file = chooseImage();

        if (file != null) {
            newAvatar = file.toPath();

            emit("avatarSelected",
                    Map.of("name", file.getName()));
        }
    }

    public void authenticate(
            String mode,
            String username,
            String password) {

        if (username == null || password == null) {
            return;
        }

        char[] secret = password.toCharArray();

        CompletableFuture<Profile> request =
                "register".equals(mode)
                        ? app.register(username, secret, newAvatar)
                        : app.login(username, secret);

        request.thenCompose(profile ->
                app.startNetworking()
                        .thenApply(ignored -> profile)
        ).whenComplete((profile, error) -> {

            if (error != null) {
                report(error);
                return;
            }

            myProfile = profile;

            emit("signedIn", profileJson(profile));

            loadContacts();
        });
    }

    public void loadContacts() {
        app.getContacts().whenComplete((list, error) -> {

            if (error != null) {
                report(error);
                return;
            }

            List<Object> data = new ArrayList<>();

            for (Contact contact : list) {
                data.add(contactJson(contact));
            }

            emit("contacts", data);
        });
    }

    public void openConversation(String id) {
        if (id == null || id.isBlank()) return;

        currentPeer = id;

        app.getHistory(id).whenComplete((list, error) -> {

            if (error != null) {
                report(error);
                return;
            }

            List<Object> data = new ArrayList<>();

            for (ChatMessage message : list) {
                data.add(messageJson(message));

                if (id.equals(message.senderId())
                        && !message.deletedForEveryone()
                        && message.status() != MessageStatus.READ) {

                    app.markAsRead(message.id());
                }
            }

            emit("history", Map.of(
                    "peerId", id,
                    "messages", data
            ));

            loadAttachments(id);
        });
    }

    /**
     * Loads the file history for a private conversation.
     * The frontend will render these as chat file cards.
     */
    public void loadAttachments(String peerId) {
        if (peerId == null || peerId.isBlank()) return;

        app.getAttachmentHistory("PRIVATE", peerId)
                .whenComplete((list, error) -> {

                    if (error != null) {
                        report(error);
                        return;
                    }

                    List<Object> data = new ArrayList<>();

                    for (Attachment item : list) {
                        data.add(attachmentJson(item));
                    }

                    emit("attachmentHistory", Map.of(
                            "peerId", peerId,
                            "items", data
                    ));
                });
    }

    /**
     * Receives events from the independent file module.
     * It cannot interrupt normal text messaging.
     */
    private void onAttachmentChanged(Attachment item) {
        if (!"PRIVATE".equals(item.chatType())) return;

        emit("attachmentChanged", attachmentJson(item));

        String active = currentPeer;

        if (active != null && active.equals(item.chatId())) {
            loadAttachments(active);
        }
    }

    public void sendMessage(
            String peerId,
            String text) {

        if (peerId == null ||
                text == null ||
                text.isBlank()) {
            return;
        }

        app.sendMessage(peerId, text)
                .whenComplete((id, error) -> {

                    if (error != null) {
                        report(error);
                    }
                });
    }

    public void deleteMessage(
            String id,
            boolean everyone) {

        CompletableFuture<Void> operation =
                everyone
                        ? app.deleteForEveryone(id)
                        : app.deleteForMe(id);

        operation.whenComplete((result, error) -> {

            if (error != null) {
                report(error);

            } else if (currentPeer != null) {
                openConversation(currentPeer);
            }
        });
    }

    /**
     * Clears a private conversation and reloads the UI.
     * The engine also clears this conversation's
     * attachment cards.
     */
    public void clearChat(String peerId) {
        if (peerId == null || peerId.isBlank()) return;

        app.clearConversation(peerId)
                .whenComplete((result, error) -> {

                    if (error != null) {
                        report(error);

                    } else if (peerId.equals(currentPeer)) {
                        openConversation(peerId);
                    }
                });
    }

    /**
     * Queues an outgoing file.
     * Offline recipients receive it after reconnecting.
     */
    public void attachFile(String peerId) {
        if (peerId == null || peerId.isBlank()) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Share a file");

        File chosen = chooser.showOpenDialog(stage);

        if (chosen == null) return;

        emit("notice",
                "Preparing " + chosen.getName() + "...");

        app.sendPrivateAttachment(peerId, chosen.toPath())
                .whenComplete((item, error) -> {

                    if (error != null) {
                        report(error);

                    } else {
                        emit("notice",
                                "File queued for delivery: "
                                        + item.filename());
                    }
                });
    }

    /**
     * Open a locally available file.
     * Called only by an explicit Open File button.
     */
    public void openAttachment(String attachmentId) {
        if (attachmentId == null ||
                attachmentId.isBlank()) {
            return;
        }

        app.openAttachment(attachmentId)
                .whenComplete((ignored, error) -> {

                    if (error != null) {
                        report(error);
                    }
                });
    }

    /**
     * Delete an attachment card for the current user.
     * Does not delete another user's saved file.
     */
    public void deleteAttachment(String attachmentId) {
        if (attachmentId == null ||
                attachmentId.isBlank()) {
            return;
        }

        String peerAtClick = currentPeer;

        app.deleteAttachmentForMe(attachmentId)
                .whenComplete((ignored, error) -> {

                    if (error != null) {
                        report(error);

                    } else if (peerAtClick != null) {
                        loadAttachments(peerAtClick);
                    }
                });
    }

    /**
     * Forward a saved private attachment to another peer.
     * A fresh attachment ID and recipient queue are created.
     */
    public void forwardAttachment(
            String attachmentId,
            String targetPeerId
    ) {
        String sourcePeer = currentPeer;

        if (sourcePeer == null ||
                attachmentId == null ||
                attachmentId.isBlank() ||
                targetPeerId == null ||
                targetPeerId.isBlank()) {

            emit("error",
                    "Select an attachment and recipient first");
            return;
        }

        app.getAttachmentHistory("PRIVATE", sourcePeer)
                .thenCompose(history -> {

                    Attachment original = history.stream()
                            .filter(a ->
                                    attachmentId.equals(a.id()))
                            .findFirst()
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Attachment not found in this chat"
                                    ));

                    if (original.localPath() == null ||
                            original.localPath().isBlank()) {

                        throw new IllegalStateException(
                                "Attachment is not available locally");
                    }

                    Path source = Path.of(original.localPath());

                    if (!Files.isRegularFile(source)) {
                        throw new IllegalStateException(
                                "Attachment file was moved or deleted");
                    }

                    return app.sendPrivateAttachment(
                            targetPeerId,
                            source
                    );
                })
                .whenComplete((item, error) -> {

                    if (error != null) {
                        report(error);

                    } else {
                        emit("notice",
                                "Forwarded file queued for delivery: "
                                        + item.filename());
                    }
                });
    }

    public void changeAvatar() {
        File file = chooseImage();

        if (file == null) {
            return;
        }

        app.updateAvatar(file.toPath())
                .whenComplete((result, error) -> {

                    if (error != null) {
                        report(error);

                    } else {
                        emit("notice",
                                "Profile picture updated");
                    }
                });
    }

    private File chooseImage() {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose profile photo");

        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Images",
                        "*.png",
                        "*.jpg",
                        "*.jpeg"
                )
        );

        return chooser.showOpenDialog(stage);
    }

    private static Map<String, Object> profileJson(Profile p) {

        return Map.of(
                "id", p.id(),
                "username", p.username(),
                "avatar", avatar(p.avatar())
        );
    }

    private static Map<String, Object> contactJson(Contact p) {

        return Map.of(
                "id", p.id(),
                "username", p.username(),
                "avatar", avatar(p.avatar()),
                "online", p.online(),
                "lastSeen", p.lastSeen()
        );
    }

    private static Map<String, Object> messageJson(ChatMessage m) {

        return Map.of(
                "id", m.id(),
                "senderId", m.senderId(),
                "receiverId", m.receiverId(),
                "text", m.text(),
                "timestamp", m.timestamp(),
                "status", m.status().name(),
                "deleted", m.deletedForEveryone()
        );
    }

    /**
     * Attachment payload sent to JavaScript.
     * Does not expose local filesystem paths.
     */
    private static Map<String, Object> attachmentJson(
            Attachment a
    ) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("id", a.id());
        data.put("chatType", a.chatType());
        data.put("chatId", a.chatId());
        data.put("senderId", a.senderId());
        data.put("filename", a.filename());
        data.put("mimeType", a.mimeType());
        data.put("size", a.size());
        data.put("timestamp", a.createdAt());
        data.put("status", a.status());

        boolean available = false;

        if (a.localPath() != null &&
                !a.localPath().isBlank()) {

            try {
                available = Files.isRegularFile(
                        Path.of(a.localPath()));

            } catch (RuntimeException ignored) {
                // Invalid local paths must not break the chat.
            }
        }

        data.put("available", available);

        return data;
    }

    private static String avatar(byte[] bytes) {

        if (bytes == null || bytes.length == 0) {
            return "";
        }

        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    private void report(Throwable error) {

        while (error.getCause() != null) {
            error = error.getCause();
        }

        emit("error",
                error.getMessage() == null
                        ? "Operation failed"
                        : error.getMessage());
    }

    /**
     * Send Java events safely to the WebView UI thread.
     */
    private void emit(String type, Object data) {

        Platform.runLater(() -> {

            try {
                web.executeScript(
                        "window.LAN.receive("
                                + json(type)
                                + ","
                                + json(data)
                                + ")"
                );

            } catch (Exception e) {
                System.err.println(
                        "Frontend event: " + e.getMessage());
            }
        });
    }

    /**
     * JSON serializer retained for WebView compatibility.
     */
    private static String json(Object value) {

        if (value == null) {
            return "null";
        }

        if (value instanceof Boolean ||
                value instanceof Number) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {

            StringJoiner parts =
                    new StringJoiner(",", "{", "}");

            map.forEach((key, val) ->
                    parts.add(
                            json(String.valueOf(key))
                                    + ":"
                                    + json(val)
                    )
            );

            return parts.toString();
        }

        if (value instanceof Iterable<?> list) {

            StringJoiner parts =
                    new StringJoiner(",", "[", "]");

            for (Object item : list) {
                parts.add(json(item));
            }

            return parts.toString();
        }

        String str = String.valueOf(value);

        StringBuilder output = new StringBuilder("\"");

        for (int i = 0; i < str.length(); i++) {

            char c = str.charAt(i);

            switch (c) {

                case '"' -> output.append("\\\"");

                case '\\' -> output.append("\\\\");

                case '\n' -> output.append("\\n");

                case '\r' -> output.append("\\r");

                case '\t' -> output.append("\\t");

                default -> {

                    if (c < 32 ||
                            c == '\u2028' ||
                            c == '\u2029') {

                        output.append(
                                String.format("\\u%04x", (int) c)
                        );

                    } else {
                        output.append(c);
                    }
                }
            }
        }

        return output.append('"').toString();
    }

    @Override
    public void onContactsChanged(List<Contact> contacts) {
        loadContacts();
    }

    @Override
    public void onMessageChanged(ChatMessage message) {

        emit("message", messageJson(message));

        if (myProfile != null &&
                !message.senderId().equals(myProfile.id()) &&
                !message.deletedForEveryone() &&
                (!stage.isFocused() ||
                        !message.senderId().equals(currentPeer))) {

            DesktopNotifier.showOnce(
                    message.id(),
                    "LAN Messenger - New message",
                    message.text()
            );
        }

        if (currentPeer != null &&
                currentPeer.equals(message.senderId()) &&
                !message.deletedForEveryone() &&
                message.status() != MessageStatus.READ) {

            app.markAsRead(message.id());
        }
    }

    @Override
    public void onMessageDeleted(String id) {
        emit("messageDeleted", id);
    }

    @Override
    public void onConversationCleared(String id) {

        if (id.equals(currentPeer)) {
            openConversation(id);
        }
    }

    @Override
    public void onMyProfileChanged(Profile profile) {

        myProfile = profile;

        emit("profile", profileJson(profile));
    }

    @Override
    public void onFileReceived(
            String senderId,
            String filename,
            Path file) {

        emit("notice",
                "Received " + filename
                        + " - saved in LAN Messenger downloads");

        if (senderId != null &&
                senderId.equals(currentPeer)) {

            loadAttachments(senderId);
        }
    }

    @Override
    public void onError(
            String operation,
            Throwable error
    ) {
        report(error);
    }
}
