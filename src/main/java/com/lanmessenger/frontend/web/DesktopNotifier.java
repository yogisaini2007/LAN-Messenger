package com.lanmessenger.frontend.web;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional Windows tray alerts; any error leaves private chat working. */
public final class DesktopNotifier {
    private static final AtomicBoolean attempted = new AtomicBoolean(false);
    private static volatile TrayIcon tray;
    private static final Map<String, Boolean> recent = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> oldest) {
            return size() > 1000;
        }
    };
    private DesktopNotifier() {}

    public static void showOnce(String messageId, String title, String body) {
        synchronized (recent) {
            if (recent.putIfAbsent(messageId, Boolean.TRUE) != null) return;
        }
        show(title, body);
    }

    public static void show(String title, String body) {
        try {
            if (!attempted.getAndSet(true)) init();
            TrayIcon current = tray;
            if (current != null) {
                EventQueue.invokeLater(() -> current.displayMessage(
                    shorten(title, 60), shorten(body, 170),
                    TrayIcon.MessageType.INFO));
            }
        } catch (Throwable ignored) {}
    }

    private static void init() {
        try {
            if (!SystemTray.isSupported()) return;
            BufferedImage image;
            try (InputStream stream = DesktopNotifier.class.getResourceAsStream("/web/logo.png")) {
                image = stream == null ? null : ImageIO.read(stream);
            }
            if (image == null) {
                image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.setColor(new java.awt.Color(70, 94, 225));
                g.fillRoundRect(0, 0, 32, 32, 10, 10);
                g.dispose();
            }
            Image iconImage = image;
            TrayIcon candidate = new TrayIcon(iconImage, "LAN Messenger");
            candidate.setImageAutoSize(true);
            SystemTray.getSystemTray().add(candidate);
            tray = candidate;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { SystemTray.getSystemTray().remove(candidate); }
                catch (Throwable ignored) {}
            }, "lan-notification-cleanup"));
        } catch (Throwable ignored) {}
    }

    private static String shorten(String text, int max) {
        String s = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "â€¦";
    }
}