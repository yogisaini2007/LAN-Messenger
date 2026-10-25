package com.lanmessenger.frontend.components;

import com.lanmessenger.core.MessengerApi.ChatMessage;
import com.lanmessenger.frontend.theme.GlassTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class MessageBubble extends GlassPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("hh:mm a");

    public MessageBubble(
            ChatMessage message,
            boolean mine,
            Runnable deleteForMe,
            Runnable deleteForEveryone
    ) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setRadius(20);
        setBorder(new EmptyBorder(10, 12, 8, 12));

        if (mine) {
            setGlassColors(
                    new Color(116, 128, 255, 170),
                    new Color(73, 90, 205, 150)
            );
        } else {
            setGlassColors(
                    new Color(255, 255, 255, 34),
                    new Color(255, 255, 255, 18)
            );
        }

        String body = message.deletedForEveryone()
                ? "This message was deleted"
                : message.text();

        JTextArea text = new JTextArea(body);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setBorder(null);
        text.setFont(GlassTheme.font(14, false));
        text.setForeground(message.deletedForEveryone()
                ? GlassTheme.TEXT_FAINT
                : GlassTheme.TEXT);

        int width = Math.min(280, Math.max(100, body.length() * 7 + 30));
        text.setSize(width, Short.MAX_VALUE);

        Dimension pref = text.getPreferredSize();
        text.setPreferredSize(new Dimension(width, pref.height));
        text.setMaximumSize(new Dimension(width, pref.height + 4));

        add(text);
        add(Box.createVerticalStrut(6));

        String time = Instant.ofEpochMilli(message.timestamp())
                .atZone(ZoneId.systemDefault())
                .format(TIME);

        String status = mine ? "  ·  " + prettyStatus(message.status().name()) : "";

        JLabel meta = GlassTheme.label(
                time + status,
                10,
                false,
                new Color(220, 228, 242, 185)
        );
        add(meta);

        JPopupMenu menu = new JPopupMenu();

        JMenuItem meItem = new JMenuItem("Delete for me");
        meItem.addActionListener(e -> deleteForMe.run());
        menu.add(meItem);

        if (mine && !message.deletedForEveryone()) {
            JMenuItem everyoneItem = new JMenuItem("Delete for everyone");
            everyoneItem.addActionListener(e -> deleteForEveryone.run());
            menu.add(everyoneItem);
        }

        MouseAdapter popup = new MouseAdapter() {
            private void maybe(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) { maybe(e); }

            @Override
            public void mouseReleased(MouseEvent e) { maybe(e); }
        };

        addMouseListener(popup);
        text.addMouseListener(popup);
        meta.addMouseListener(popup);
    }

    private String prettyStatus(String status) {
        return switch (status) {
            case "PENDING" -> "Pending";
            case "SENT" -> "Sent";
            case "DELIVERED" -> "Delivered";
            case "READ" -> "Read";
            default -> status;
        };
    }
}