
package com.lanmessenger.backend.service.voice;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;

/**
 * Native microphone recording and WAV playback.
 * Works independently of JavaFX WebView.
 */
public final class VoiceMessageService
        implements AutoCloseable {

    private static final int MAX_SECONDS = 120;

    private static final AudioFormat[] FORMATS = {
            new AudioFormat(16000f, 16, 1, true, false),
            new AudioFormat(44100f, 16, 1, true, false)
    };

    private final Path voiceDirectory;

    private final ExecutorService recorder =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(
                        r, "lan-voice-recorder");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService playerWorker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(
                        r, "lan-voice-player");
                t.setDaemon(true);
                return t;
            });

    private Recording activeRecording;
    private Clip activePlayer;
    private boolean closed;

    private static final class Recording {
        final AtomicBoolean stop =
                new AtomicBoolean();

        final CompletableFuture<Void> started =
                new CompletableFuture<>();

        final CompletableFuture<Path> finished =
                new CompletableFuture<>();

        volatile TargetDataLine line;
        volatile boolean cancelled;
    }

    public VoiceMessageService(Path directory) {

        if (directory == null) {
            throw new IllegalArgumentException(
                    "Voice directory required");
        }

        voiceDirectory =
                directory.toAbsolutePath().normalize();
    }

    // Start recording using the computer microphone.
    public synchronized CompletableFuture<Void>
    startRecording() {

        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Voice service is closed"));
        }

        if (activeRecording != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Already recording"));
        }

        Recording session = new Recording();
        activeRecording = session;

        try {
            recorder.execute(() -> capture(session));

        } catch (RejectedExecutionException error) {
            activeRecording = null;
            session.started.completeExceptionally(error);
        }

        return session.started;
    }

    // Stop recording and return the completed WAV.
    public CompletableFuture<Path> stopRecording() {

        Recording session;

        synchronized (this) {
            session = activeRecording;
        }

        if (session == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "No recording in progress"));
        }

        session.stop.set(true);
        closeMicrophone(session.line);

        return session.finished;
    }

    // Cancel without sending or keeping the file.
    public void cancelRecording() {

        Recording session;

        synchronized (this) {
            session = activeRecording;
        }

        if (session != null) {
            session.cancelled = true;
            session.stop.set(true);
            closeMicrophone(session.line);
        }
    }

    public synchronized boolean isRecording() {
        return activeRecording != null;
    }

    private void capture(Recording session) {

        TargetDataLine mic = null;
        Path output = null;

        try {
            Files.createDirectories(voiceDirectory);

            AudioFormat format = null;
            Exception lastError = null;

            // Try microphone-compatible recording formats.
            for (AudioFormat candidate : FORMATS) {

                TargetDataLine candidateLine = null;

                try {
                    DataLine.Info info =
                            new DataLine.Info(
                                    TargetDataLine.class,
                                    candidate);

                    candidateLine =
                            (TargetDataLine)
                                    AudioSystem.getLine(info);

                    candidateLine.open(candidate);

                    mic = candidateLine;
                    format = candidate;
                    break;

                } catch (
                        LineUnavailableException |
                        IllegalArgumentException |
                        SecurityException error) {

                    lastError = error;
                    closeMicrophone(candidateLine);
                }
            }

            if (mic == null || format == null) {
                throw new IOException(
                        "Microphone unavailable or permission denied",
                        lastError);
            }

            output = voiceDirectory.resolve(
                    "voice-" +
                    System.currentTimeMillis() +
                    "-" +
                    UUID.randomUUID() +
                    ".wav");

            session.line = mic;
            mic.start();

            final int maximumBytes =
                    (int) format.getSampleRate()
                    * format.getFrameSize()
                    * MAX_SECONDS;

            int bytesWritten = 0;

            try (RandomAccessFile file =
                         new RandomAccessFile(
                                 output.toFile(), "rw")) {

                file.setLength(0);

                writeHeader(file, format, 0);

                // Signal successful microphone startup.
                session.started.complete(null);

                byte[] buffer = new byte[4096];

                while (!session.stop.get() &&
                        bytesWritten < maximumBytes) {

                    int count;

                    try {
                        count = mic.read(
                                buffer, 0, buffer.length);

                    } catch (RuntimeException error) {
                        if (session.stop.get()) break;
                        throw error;
                    }

                    if (count <= 0) {
                        if (session.stop.get()) break;
                        continue;
                    }

                    count = Math.min(
                            count,
                            maximumBytes - bytesWritten);

                    count -= count % format.getFrameSize();

                    if (count <= 0) break;

                    file.write(buffer, 0, count);
                    bytesWritten += count;
                }

                // Finalize the correct WAV header.
                writeHeader(
                        file, format, bytesWritten);
            }

            if (session.cancelled) {

                Files.deleteIfExists(output);

                session.finished.completeExceptionally(
                        new CancellationException(
                                "Recording cancelled"));

            } else if (bytesWritten == 0) {

                Files.deleteIfExists(output);

                session.finished.completeExceptionally(
                        new IOException(
                                "No audio recorded"));

            } else {
                session.finished.complete(output);
            }

        } catch (Exception error) {

            session.started.completeExceptionally(error);
            session.finished.completeExceptionally(error);

            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }

        } finally {

            closeMicrophone(mic);

            synchronized (this) {
                if (activeRecording == session) {
                    activeRecording = null;
                }
            }
        }
    }

    // Play an existing voice note.
    public CompletableFuture<Void> play(Path wavFile) {

        if (wavFile == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Voice file required"));
        }

        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Voice service is closed"));
            }
        }

        return CompletableFuture.runAsync(() -> {

            Clip clip = null;

            try {
                if (!Files.isRegularFile(wavFile)) {
                    throw new IOException(
                            "Voice file does not exist");
                }

                clip = AudioSystem.getClip();

                try (AudioInputStream audio =
                             AudioSystem.getAudioInputStream(
                                     wavFile.toFile())) {

                    clip.open(audio);
                }

                final Clip playing = clip;

                playing.addLineListener(event -> {

                    if (event.getType() ==
                            LineEvent.Type.STOP) {

                        synchronized (
                                VoiceMessageService.this) {

                            if (activePlayer == playing) {
                                activePlayer = null;
                            }
                        }

                        playing.close();
                    }
                });

                synchronized (this) {

                    if (closed) {
                        clip.close();
                        throw new IllegalStateException(
                                "Voice service is closed");
                    }

                    stopPlayback();

                    activePlayer = clip;
                    clip.start();
                }

            } catch (Exception error) {

                if (clip != null) {
                    clip.close();
                }

                throw new CompletionException(error);
            }

        }, playerWorker);
    }

    public synchronized void stopPlayback() {

        Clip playing = activePlayer;
        activePlayer = null;

        if (playing != null) {
            playing.stop();
            playing.close();
        }
    }

    private static void closeMicrophone(
            TargetDataLine line) {

        if (line == null) return;

        try {
            line.stop();
        } catch (Exception ignored) {
        }

        try {
            line.close();
        } catch (Exception ignored) {
        }
    }

    // Standard PCM WAV header.
    private static void writeHeader(
            RandomAccessFile file,
            AudioFormat format,
            int dataBytes
    ) throws IOException {

        file.seek(0);

        file.writeBytes("RIFF");
        writeIntLE(file, 36 + dataBytes);

        file.writeBytes("WAVEfmt ");
        writeIntLE(file, 16);

        writeShortLE(file, 1);
        writeShortLE(file, format.getChannels());

        writeIntLE(
                file,
                (int) format.getSampleRate());

        writeIntLE(
                file,
                (int) format.getSampleRate()
                        * format.getFrameSize());

        writeShortLE(file, format.getFrameSize());
        writeShortLE(file, format.getSampleSizeInBits());

        file.writeBytes("data");
        writeIntLE(file, dataBytes);

        file.seek(44L + dataBytes);
    }

    private static void writeIntLE(
            RandomAccessFile file,
            int value
    ) throws IOException {

        file.write(value & 255);
        file.write((value >>> 8) & 255);
        file.write((value >>> 16) & 255);
        file.write((value >>> 24) & 255);
    }

    private static void writeShortLE(
            RandomAccessFile file,
            int value
    ) throws IOException {

        file.write(value & 255);
        file.write((value >>> 8) & 255);
    }

    @Override
    public synchronized void close() {

        if (closed) return;

        closed = true;

        cancelRecording();
        stopPlayback();

        recorder.shutdown();
        playerWorker.shutdownNow();
    }
}
