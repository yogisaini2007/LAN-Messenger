package com.lanmessenger.frontend.screens;

import com.lanmessenger.core.MessengerApi;
import com.lanmessenger.core.MessengerApi.*;
import com.lanmessenger.core.MessengerEvents;
import com.lanmessenger.frontend.components.*;
import com.lanmessenger.frontend.theme.GlassTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MessengerScreen extends JFrame implements MessengerEvents {

    private final MessengerApi api;
    private Profile me;

    private final JPanel contactsPanel = new JPanel();
    private final JPanel messagesPanel = new JPanel();

    private final AvatarView myAvatar = new AvatarView(42);
    private final AvatarView peerAvatar = new AvatarView(40);

    private final JLabel peerName = GlassTheme.label("Select a conversation", 18, true, GlassTheme.TEXT);
    private final JLabel peerStatus = GlassTheme.label("Choose a contact to begin", 12, false, GlassTheme.TEXT_FAINT);
    private final JLabel bottomInfo = GlassTheme.label("Connected to your local network", 11, false, GlassTheme.TEXT_FAINT);

    private JTextField searchField;
    private JTextArea composer;
    private JScrollPane messageScroll;

    private List<Contact> contacts = new ArrayList<>();
    private Contact selected;
    private long historyVersion = 0;

    public MessengerScreen(MessengerApi api, Profile me) {
        super("LAN Messenger");
        this.api = api;
        this.me = me;

        setSize(1280, 800);
        setMinimumSize(new Dimension(980, 650));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                api.removeListener(MessengerScreen.this);
                api.close();
                dispose();
            }
        });

        buildUI();
        api.addListener(this);
        loadContacts();
    }

    private void buildUI() {
        GlassTheme.Backdrop root = new GlassTheme.Backdrop();
        root.setLayout(new BorderLayout(14, 0));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        root.add(buildSidebar(), BorderLayout.WEST);
        root.add(buildChatArea(), BorderLayout.CENTER);

        setContentPane(root);
    }

    private JComponent buildSidebar() {
        GlassPanel side = new GlassPanel(new BorderLayout(0, 14));
        side.setRadius(30);
        side.setStrongBorder(true);
        side.setPreferredSize(new Dimension(285, 600));
        side.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel brand = GlassTheme.label("LAN Messenger", 19, true, GlassTheme.TEXT);
        top.add(brand);
        top.add(Box.createVerticalStrut(16));

        GlassPanel meCard = new GlassPanel(new BorderLayout(10, 0));
        meCard.setRadius(24);
        meCard.setGlassColors(new Color(255,255,255,28), new Color(255,255,255,12));
        meCard.setBorder(new EmptyBorder(10, 10, 10, 10));

        myAvatar.setProfile(me.username(), me.avatar(), true);
        meCard.add(myAvatar, BorderLayout.WEST);

        JPanel meText = new JPanel();
        meText.setOpaque(false);
        meText.setLayout(new BoxLayout(meText, BoxLayout.Y_AXIS));
        meText.add(GlassTheme.label(me.username(), 15, true, GlassTheme.TEXT));
        meText.add(Box.createVerticalStrut(4));
        meText.add(GlassTheme.label("My profile", 11, false, GlassTheme.TEXT_FAINT));
        meCard.add(meText, BorderLayout.CENTER);

        top.add(meCard);
        top.add(Box.createVerticalStrut(12));

        JPanel actionRow = new JPanel(new GridLayout(1, 2, 8, 0));
        actionRow.setOpaque(false);

        GlassButton photoBtn = new GlassButton("Avatar", false);
        photoBtn.addActionListener(e -> changeAvatar());
        actionRow.add(photoBtn);

        GlassButton refreshBtn = new GlassButton("Refresh", false);
        refreshBtn.addActionListener(e -> loadContacts());
        actionRow.add(refreshBtn);

        top.add(actionRow);
        top.add(Box.createVerticalStrut(18));

        JLabel msgLabel = GlassTheme.label("Chats", 16, true, GlassTheme.TEXT);
        top.add(msgLabel);
        top.add(Box.createVerticalStrut(10));

        searchField = new JTextField();
        GlassTheme.styleInput(searchField);

        GlassPanel searchWrap = new GlassPanel(new BorderLayout());
        searchWrap.setRadius(18);
        searchWrap.setGlassColors(new Color(255,255,255,22), new Color(255,255,255,10));
        searchWrap.setBorder(new EmptyBorder(0, 0, 0, 0));
        searchWrap.setPreferredSize(new Dimension(200, 42));
        searchWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        searchWrap.add(searchField, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { renderContacts(); }
            public void removeUpdate(DocumentEvent e) { renderContacts(); }
            public void changedUpdate(DocumentEvent e) { renderContacts(); }
        });

        top.add(searchWrap);

        side.add(top, BorderLayout.NORTH);

        contactsPanel.setOpaque(false);
        contactsPanel.setLayout(new BoxLayout(contactsPanel, BoxLayout.Y_AXIS));
        side.add(GlassTheme.makeScroll(contactsPanel), BorderLayout.CENTER);

        return side;
    }

    private JComponent buildChatArea() {
        GlassPanel area = new GlassPanel(new BorderLayout(0, 10));
        area.setRadius(30);
        area.setStrongBorder(true);
        area.setBorder(new EmptyBorder(14, 14, 14, 14));

        area.add(buildHeader(), BorderLayout.NORTH);

        messagesPanel.setOpaque(false);
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messageScroll = GlassTheme.makeScroll(messagesPanel);
        area.add(messageScroll, BorderLayout.CENTER);

        area.add(buildComposer(), BorderLayout.SOUTH);

        showEmptyState();
        return area;
    }

    private JComponent buildHeader() {
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);

        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setOpaque(false);

        left.add(peerAvatar, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(peerName);
        text.add(Box.createVerticalStrut(4));
        text.add(peerStatus);

        left.add(text, BorderLayout.CENTER);

        top.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);

        GlassButton clear = new GlassButton("Clear", false);
        clear.addActionListener(e -> clearConversation());
        right.add(clear);

        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildComposer() {
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);

        GlassPanel bar = new GlassPanel(new BorderLayout(10, 0));
        bar.setRadius(24);
        bar.setGlassColors(new Color(255,255,255,24), new Color(255,255,255,12));
        bar.setBorder(new EmptyBorder(8, 10, 8, 10));

        GlassButton attach = new GlassButton("Attach", false);
        attach.addActionListener(e -> attachFile());
        bar.add(attach, BorderLayout.WEST);

        composer = new JTextArea(2, 10);
        composer.setOpaque(false);
        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setForeground(GlassTheme.TEXT);
        composer.setCaretColor(GlassTheme.TEXT);
        composer.setFont(GlassTheme.font(14, false));
        composer.setBorder(new EmptyBorder(7, 8, 7, 8));

        JScrollPane textScroll = GlassTheme.makeScroll(composer);
        textScroll.setPreferredSize(new Dimension(200, 58));
        bar.add(textScroll, BorderLayout.CENTER);

        GlassButton send = new GlassButton("Send", true);
        send.addActionListener(e -> sendMessage());
        bar.add(send, BorderLayout.EAST);

        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        composer.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        composer.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                "insert-break"
        );

        bottom.add(bar, BorderLayout.CENTER);
        bottom.add(bottomInfo, BorderLayout.SOUTH);
        return bottom;
    }

    private void loadContacts() {
        api.getContacts().whenComplete((list, error) -> onUI(() -> {
            if (error != null) {
                showError(error);
                return;
            }
            contacts = list;
            renderContacts();
        }));
    }

    private void renderContacts() {
        contactsPanel.removeAll();

        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase();
        int count = 0;

        for (Contact c : contacts) {
            if (!c.username().toLowerCase().contains(q)) continue;
            count++;

            GlassPanel tile = new GlassPanel(new BorderLayout(10, 0));
            tile.setRadius(20);
            tile.setGlassColors(
                    selected != null && selected.id().equals(c.id())
                            ? new Color(118, 132, 255, 85)
                            : new Color(255,255,255,20),
                    selected != null && selected.id().equals(c.id())
                            ? new Color(78, 92, 210, 60)
                            : new Color(255,255,255,10)
            );
            tile.setBorder(new EmptyBorder(9, 9, 9, 9));
            tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

            AvatarView avatar = new AvatarView(40);
            avatar.setProfile(c.username(), c.avatar(), c.online());
            tile.add(avatar, BorderLayout.WEST);

            JPanel details = new JPanel();
            details.setOpaque(false);
            details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
            details.add(GlassTheme.label(c.username(), 13, true, GlassTheme.TEXT));
            details.add(Box.createVerticalStrut(3));
            details.add(GlassTheme.label(
                    c.online() ? "Online" : "Offline",
                    11, false,
                    c.online() ? GlassTheme.ONLINE : GlassTheme.TEXT_FAINT
            ));
            tile.add(details, BorderLayout.CENTER);

            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selected = c;
                    updateHeader();
                    refreshHistory();
                    renderContacts();
                }
            };

            bindClicks(tile, click);
            contactsPanel.add(tile);
            contactsPanel.add(Box.createVerticalStrut(8));
        }

        if (count == 0) {
            JLabel empty = GlassTheme.label("No contacts discovered yet", 12, false, GlassTheme.TEXT_FAINT);
            contactsPanel.add(empty);
        }

        contactsPanel.revalidate();
        contactsPanel.repaint();
    }

    private void bindClicks(Component c, MouseAdapter listener) {
        c.addMouseListener(listener);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                bindClicks(child, listener);
            }
        }
    }

    private void updateHeader() {
        if (selected == null) return;
        peerAvatar.setProfile(selected.username(), selected.avatar(), selected.online());
        peerName.setText(selected.username());
        peerStatus.setText(selected.online() ? "Online on LAN" : "Offline · queued delivery");
        peerStatus.setForeground(selected.online() ? GlassTheme.ONLINE : GlassTheme.TEXT_FAINT);
    }

    private void refreshHistory() {
        if (selected == null) return;

        String peerId = selected.id();
        long current = ++historyVersion;

        api.getHistory(peerId).whenComplete((messages, error) -> onUI(() -> {
            if (error != null) {
                showError(error);
                return;
            }
            if (selected == null || !selected.id().equals(peerId) || current != historyVersion) return;
            renderMessages(messages);
        }));
    }

    private void renderMessages(List<ChatMessage> messages) {
        messagesPanel.removeAll();

        if (messages.isEmpty()) {
            showEmptyState();
        } else {
            for (ChatMessage m : messages) {
                boolean mine = m.senderId().equals(me.id());

                MessageBubble bubble = new MessageBubble(
                        m,
                        mine,
                        () -> deleteMessage(m.id(), false),
                        () -> deleteMessage(m.id(), true)
                );

                JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 6, 5));
                row.setOpaque(false);
                row.add(bubble);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

                messagesPanel.add(row);

                if (!mine && !m.deletedForEveryone() && m.status() != MessageStatus.READ) {
                    api.markAsRead(m.id());
                }
            }
        }

        messagesPanel.revalidate();
        messagesPanel.repaint();

        SwingUtilities.invokeLater(() ->
                messageScroll.getVerticalScrollBar().setValue(
                        messageScroll.getVerticalScrollBar().getMaximum()
                )
        );
    }

    private void showEmptyState() {
        messagesPanel.removeAll();
        JLabel label = GlassTheme.label(
                selected == null ? "Select a contact to start chatting" : "No messages yet",
                15, false, GlassTheme.TEXT_FAINT
        );
        label.setBorder(new EmptyBorder(30, 16, 16, 16));
        messagesPanel.add(label);
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private void sendMessage() {
        if (selected == null) {
            bottomInfo.setText("Select a contact first.");
            return;
        }

        String text = composer.getText().trim();
        if (text.isEmpty()) return;

        composer.setText("");

        api.sendMessage(selected.id(), text).whenComplete((id, error) -> onUI(() -> {
            if (error != null) showError(error);
            else bottomInfo.setText("Message sent");
        }));
    }

    private void attachFile() {
        if (selected == null) {
            bottomInfo.setText("Select a contact first.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path file = chooser.getSelectedFile().toPath();
        bottomInfo.setText("Sending file...");

        api.sendFile(selected.id(), file).whenComplete((v, error) -> onUI(() -> {
            if (error != null) showError(error);
            else bottomInfo.setText("File sent: " + file.getFileName());
        }));
    }

    private void changeAvatar() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path file = chooser.getSelectedFile().toPath();
        api.updateAvatar(file).whenComplete((v, error) -> onUI(() -> {
            if (error != null) showError(error);
            else bottomInfo.setText("Profile photo updated");
        }));
    }

    private void clearConversation() {
        if (selected == null) return;

        int ok = JOptionPane.showConfirmDialog(
                this,
                "Clear this conversation on this PC?",
                "Clear conversation",
                JOptionPane.YES_NO_OPTION
        );

        if (ok != JOptionPane.YES_OPTION) return;

        api.clearConversation(selected.id()).whenComplete((v, error) -> onUI(() -> {
            if (error != null) showError(error);
            else refreshHistory();
        }));
    }

    private void deleteMessage(String id, boolean everyone) {
        CompletableFuture<Void> job = everyone
                ? api.deleteForEveryone(id)
                : api.deleteForMe(id);

        job.whenComplete((v, error) -> onUI(() -> {
            if (error != null) showError(error);
            else refreshHistory();
        }));
    }

    private void showError(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        bottomInfo.setText("Error: " + (error.getMessage() == null ? "Operation failed" : error.getMessage()));
    }

    private static void onUI(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    @Override
    public void onContactsChanged(List<Contact> updated) {
        onUI(() -> {
            contacts = updated;
            renderContacts();
        });
    }

    @Override
    public void onMessageChanged(ChatMessage message) {
        onUI(() -> {
            if (selected != null &&
                    (message.senderId().equals(selected.id()) || message.receiverId().equals(selected.id()))) {
                refreshHistory();
            }
        });
    }

    @Override
    public void onMessageDeleted(String messageId) {
        onUI(this::refreshHistory);
    }

    @Override
    public void onConversationCleared(String contactId) {
        onUI(() -> {
            if (selected != null && selected.id().equals(contactId)) refreshHistory();
        });
    }

    @Override
    public void onMyProfileChanged(Profile profile) {
        onUI(() -> {
            me = profile;
            myAvatar.setProfile(profile.username(), profile.avatar(), true);
        });
    }

    @Override
    public void onFileReceived(String senderId, String filename, Path file) {
        onUI(() -> bottomInfo.setText("Received: " + filename));
    }

    @Override
    public void onError(String operation, Throwable error) {
        onUI(() -> showError(error));
    }
}