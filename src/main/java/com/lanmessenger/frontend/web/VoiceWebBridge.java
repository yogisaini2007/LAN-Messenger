
package com.lanmessenger.frontend.web;

import com.lanmessenger.app.MessengerEngine;
import com.lanmessenger.backend.service.attachments
        .AttachmentRepository.Attachment;
import com.lanmessenger.backend.service.groups.GroupModels.Member;
import com.lanmessenger.backend.service.voice.VoiceMessageService;
import com.lanmessenger.core.MessengerApi.Profile;
import com.lanmessenger.database.DatabaseManager;
import com.lanmessenger.database.repository.groups.GroupRepository;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Independent JavaFX bridge for private and group voice notes.
 *
 * Uses:
 * - VoiceMessageService for microphone/audio
 * - MessengerEngine for persistent file sharing
 * - GroupRepository for group member validation
 *
 * JavaScript interface:
 *   window.lanVoice.beginRecording(type, chatId)
 *   window.lanVoice.finishAndSend()
 *   window.lanVoice.cancelRecording()
 *   window.lanVoice.playAttachment(type, chatId, fileId)
 *   window.lanVoice.stopPlayback()
 */
public final class VoiceWebBridge implements AutoCloseable {

    private record RecordingTarget(
            String accountId,
            String type,
            String chatId
    ) {}

    private final MessengerEngine engine;
    private final WebEngine web;
    private final VoiceMessageService voice;

    private RecordingTarget active;
    private volatile boolean closed;

    public VoiceWebBridge(
            MessengerEngine engine,
            WebEngine web
    ) {
        this.engine = Objects.requireNonNull(engine);
        this.web = Objects.requireNonNull(web);

        Path directory = DatabaseManager
                .getDataDirectory()
                .resolve("voice-recordings");

        this.voice = new VoiceMessageService(directory);
    }

    // --------------------------------------------------
    // RECORDING
    // --------------------------------------------------

    public synchronized void beginRecording(
            String chatType,
            String chatId
    ) {
        if (closed) return;

        Profile profile = engine.currentProfile();

        if (profile == null || !engine.isNetworkingStarted()) {
            emit("error", "Sign in before recording");
            return;
        }

        if (!validType(chatType) ||
                chatId == null ||
                chatId.isBlank()) {
            emit("error", "Select a conversation first");
            return;
        }

        if (active != null || voice.isRecording()) {
            emit("error", "A recording is already active");
            return;
        }

        RecordingTarget target = new RecordingTarget(
                profile.id(),
                chatType,
                chatId
        );

        active = target;

        voice.startRecording()
                .whenComplete((unused, error) -> {

                    if (error != null) {
                        synchronized (VoiceWebBridge.this) {
                            if (active == target) {
                                active = null;
                            }
                        }

                        fail(error);
                        return;
                    }

                    emit("recordingStarted", Map.of(
                            "type", target.type(),
                            "chatId", target.chatId()
                    ));
                });
    }

    /**
     * Stops microphone recording and queues the WAV
     * through the existing attachment transport.
     */
    public void finishAndSend() {

        RecordingTarget target;

        synchronized (this) {
            target = active;
            active = null;
        }

        if (target == null) {
            emit("error", "No active recording");
            return;
        }

        emit("processing", "Preparing voice message...");

        voice.stopRecording()
                .thenCompose(file -> {

                    Profile profile = engine.currentProfile();

                    if (profile == null ||
                            !target.accountId().equals(profile.id())) {
                        return CompletableFuture.failedFuture(
                                new SecurityException(
                                        "Account changed during recording")
                        );
                    }

                    return sendFile(target, file)
                            .whenComplete((item, error) -> {

                                if (error == null) {
                                    try {
                                        // The attachment repository keeps
                                        // its own persistent file copy.
                                        Files.deleteIfExists(file);
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                })
                .whenComplete((attachment, error) -> {

                    if (error != null) {
                        fail(error);
                        return;
                    }

                    emit("sent", Map.of(
                            "type", target.type(),
                            "chatId", target.chatId(),
                            "id", attachment.id(),
                            "filename", attachment.filename()
                    ));
                });
    }

    private CompletableFuture<Attachment> sendFile(
            RecordingTarget target,
            Path file
    ) {
        if ("PRIVATE".equals(target.type())) {
            return engine.sendPrivateAttachment(
                    target.chatId(),
                    file
            );
        }

        // Group recipients come from the local group
        // repository, not an unverified JavaScript list.
        return CompletableFuture.supplyAsync(() -> {

            try {
                GroupRepository repository =
                        new GroupRepository(target.accountId());

                if (repository.findGroup(target.chatId()) == null) {
                    throw new IllegalArgumentException(
                            "Group not found");
                }

                List<Member> members =
                        repository.members(target.chatId());

                boolean amMember = members.stream()
                        .anyMatch(member ->
                                target.accountId()
                                        .equals(member.userId()));

                if (!amMember) {
                    throw new SecurityException(
                            "You are not a group member");
                }

                List<String> recipients = members.stream()
                        .map(Member::userId)
                        .filter(id ->
                                !id.equals(target.accountId()))
                        .distinct()
                        .toList();

                if (recipients.isEmpty()) {
                    throw new IllegalStateException(
                            "No other group members");
                }

                return recipients;

            } catch (Exception error) {
                throw new CompletionException(error);
            }

        }).thenCompose(recipients ->
                engine.sendGroupAttachment(
                        target.chatId(),
                        recipients,
                        file
                ));
    }

    public synchronized void cancelRecording() {

        if (active == null && !voice.isRecording()) {
            return;
        }

        active = null;
        voice.cancelRecording();

        emit("cancelled", "Voice recording cancelled");
    }

    public synchronized boolean isRecording() {
        return active != null && voice.isRecording();
    }

    // --------------------------------------------------
    // PLAYBACK
    // --------------------------------------------------

    /**
     * Play a recorded voice attachment from the
     * current user's local attachment history.
     */
    public void playAttachment(
            String chatType,
            String chatId,
            String attachmentId
    ) {
        if (closed || !validType(chatType) ||
                chatId == null || chatId.isBlank() ||
                attachmentId == null ||
                attachmentId.isBlank()) {

            emit("error", "Invalid voice message");
            return;
        }

        engine.getAttachmentHistory(chatType, chatId)
                .thenCompose(items -> {

                    Attachment attachment = items.stream()
                            .filter(item ->
                                    attachmentId.equals(item.id()))
                            .findFirst()
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Voice message not found"));

                    String name = attachment.filename();

                    if (name == null ||
                            !name.startsWith("voice-") ||
                            !name.toLowerCase(Locale.ROOT)
                                    .endsWith(".wav")) {

                        throw new IllegalArgumentException(
                                "Not a voice message");
                    }

                    String storedPath = attachment.localPath();

                    if (storedPath == null ||
                            storedPath.isBlank()) {
                        throw new IllegalStateException(
                                "Voice message not downloaded");
                    }

                    Path path = Path.of(storedPath);

                    if (!Files.isRegularFile(path)) {
                        throw new IllegalStateException(
                                "Local voice file is missing");
                    }

                    return voice.play(path);
                })
                .whenComplete((unused, error) -> {

                    if (error != null) {
                        fail(error);

                    } else {
                        emit("playbackStarted", Map.of(
                                "id", attachmentId,
                                "chatId", chatId
                        ));
                    }
                });
    }

    public void stopPlayback() {
        voice.stopPlayback();
        emit("playbackStopped", "");
    }

    // --------------------------------------------------
    // UI EVENTS
    // --------------------------------------------------

    private static boolean validType(String type) {
        return "PRIVATE".equals(type) ||
                "GROUP".equals(type);
    }

    private void fail(Throwable error) {

        Throwable root = error;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        emit("error",
                root.getMessage() == null
                        ? "Voice operation failed"
                        : root.getMessage());
    }

    private void emit(String type, Object payload) {

        if (closed) return;

        Platform.runLater(() -> {

            if (closed) return;

            try {
                String js =
                        "if(window.LANVoice" +
                        "&&typeof window.LANVoice.receive" +
                        "==='function')" +
                        "window.LANVoice.receive(" +
                        json(type) + "," +
                        json(payload) + ");";

                web.executeScript(js);

            } catch (Exception error) {
                System.err.println(
                        "Voice UI event failed: " +
                        error.getMessage());
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
            StringJoiner parts =
                    new StringJoiner(",", "{", "}");

            map.forEach((key, item) ->
                    parts.add(
                            json(String.valueOf(key)) +
                            ":" + json(item)));

            return parts.toString();
        }

        if (value instanceof Collection<?> values) {
            StringJoiner parts =
                    new StringJoiner(",", "[", "]");

            for (Object item : values) {
                parts.add(json(item));
            }

            return parts.toString();
        }

        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder("\"");

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");

                default -> {
                    if (c < 32 ||
                            c == 0x2028 ||
                            c == 0x2029) {

                        out.append(String.format(
                                "\\u%04x", (int) c));

                    } else {
                        out.append(c);
                    }
                }
            }
        }

        return out.append('"').toString();
    }

    @Override
    public synchronized void close() {

        if (closed) return;

        cancelRecording();
        closed = true;

        voice.close();
        active = null;
    }
}
