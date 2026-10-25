
package com.lanmessenger.frontend.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * LAN Messenger native notification sounds.
 * No external audio files, internet, or WebView audio.
 */
public final class NativeSounds implements AutoCloseable {

    private static final String PREF_KEY = "sound.enabled";
    private static final int RATE = 22050;

    private final Preferences preferences =
            Preferences.userNodeForPackage(NativeSounds.class);

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(
                        r, "lan-native-sounds");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean enabled;
    private volatile boolean closed;

    public NativeSounds() {
        enabled = preferences.getBoolean(PREF_KEY, true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        preferences.putBoolean(PREF_KEY, value);
    }

    /**
     * Called directly from JavaScript:
     * window.lanSounds.play("sent")
     * window.lanSounds.play("received")
     * window.lanSounds.play("notification")
     */
    public void play(String type) {

        if (closed || !enabled || type == null) {
            return;
        }

        if (!type.equals("sent") &&
                !type.equals("received") &&
                !type.equals("notification")) {
            return;
        }

        try {
            worker.execute(() -> playNative(type));
        } catch (RuntimeException ignored) {
            // A sound failure must never stop messaging.
        }
    }

    private static void playNative(String type) {

        // Each note: frequency, starting time, duration.
        double[][] notes;

        switch (type) {
            case "sent" -> notes = new double[][]{
                    {740, 0, 80},
                    {988, 85, 85}
            };

            case "received" -> notes = new double[][]{
                    {587, 0, 120},
                    {784, 135, 140}
            };

            default -> notes = new double[][]{
                    {523, 0, 85},
                    {659, 105, 85},
                    {880, 210, 145}
            };
        }

        int totalMs = type.equals("notification")
                ? 380 : 300;

        int samples = RATE * totalMs / 1000;
        byte[] pcm = new byte[samples * 2];

        for (int i = 0; i < samples; i++) {

            double timeMs = i * 1000.0 / RATE;
            double sample = 0;

            for (double[] note : notes) {

                double start = note[1];
                double duration = note[2];
                double elapsed = timeMs - start;

                if (elapsed < 0 || elapsed >= duration) {
                    continue;
                }

                double attack = Math.min(1.0,
                        elapsed / 12.0);

                double release = Math.min(1.0,
                        (duration - elapsed) / 25.0);

                double envelope = Math.max(
                        0,
                        Math.min(attack, release)
                );

                double phase = 2.0 * Math.PI
                        * note[0]
                        * elapsed / 1000.0;

                sample += Math.sin(phase)
                        * envelope * 0.20;
            }

            sample = Math.max(-1.0,
                    Math.min(1.0, sample));

            short value =
                    (short) (sample * 32767);

            pcm[i * 2] = (byte) (value & 0xff);
            pcm[i * 2 + 1] =
                    (byte) ((value >>> 8) & 0xff);
        }

        AudioFormat format = new AudioFormat(
                RATE, 16, 1, true, false);

        SourceDataLine line = null;

        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format);
            line.start();

            int offset = 0;

            while (offset < pcm.length) {
                int written = line.write(
                        pcm,
                        offset,
                        pcm.length - offset
                );

                if (written <= 0) break;
                offset += written;
            }

            line.drain();

        } catch (Exception error) {
            System.err.println(
                    "Sound playback unavailable: "
                            + error.getMessage());

        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
