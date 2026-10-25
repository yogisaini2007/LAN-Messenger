package com.lanmessenger.frontend.screens;

import com.lanmessenger.app.MessengerEngine;
import com.lanmessenger.core.MessengerApi.Profile;
import com.lanmessenger.frontend.components.GlassButton;
import com.lanmessenger.frontend.components.GlassPanel;
import com.lanmessenger.frontend.theme.GlassTheme;
import com.lanmessenger.util.AvatarImages;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public final class AuthScreen extends JFrame {

    private final MessengerEngine engine;
    private boolean registerMode = false;
    private boolean busy = false;
    private Path selectedAvatar;

    private JTextField usernameField;
    private JPasswordField passwordField;
    private JPasswordField confirmField;
    private JLabel errorLabel;
    private GlassButton actionButton;

    public AuthScreen(MessengerEngine engine) {
        super("LAN Messenger");
        this.engine = engine;

        setSize(1120, 720);
        setMinimumSize(new Dimension(920, 620));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                engine.close();
            }
        });

        buildUI();
    }

    private void buildUI() {
        GlassTheme.Backdrop root = new GlassTheme.Backdrop();
        root.setLayout(new GridBagLayout());

        JPanel wrapper = new JPanel(new GridLayout(1, 2, 18, 0));
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(26, 26, 26, 26));
        wrapper.setPreferredSize(new Dimension(980, 600));

        wrapper.add(buildLeftCard());
        wrapper.add(buildRightCard());

        root.add(wrapper);
        setContentPane(root);
        revalidate();
        repaint();
    }

    private JComponent buildLeftCard() {
        GlassPanel left = new GlassPanel();
        left.setRadius(34);
        left.setStrongBorder(true);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(new EmptyBorder(34, 34, 34, 34));

        JLabel small = GlassTheme.label("LAN MESSENGER", 13, true, GlassTheme.TEXT_FAINT);
        left.add(small);
        left.add(Box.createVerticalStrut(24));

        JLabel heading = GlassTheme.label("Private communication,\nreimagined for LAN.", 30, true, GlassTheme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(heading);
        left.add(Box.createVerticalStrut(16));

        JTextArea desc = paragraph(
                "A modern peer-to-peer desktop messenger with login, profile picture sync, chat history, file transfer and clean private conversations."
        );
        left.add(desc);
        left.add(Box.createVerticalStrut(28));

        left.add(feature("• Glass-inspired premium interface"));
        left.add(Box.createVerticalStrut(12));
        left.add(feature("• Peer-to-peer LAN communication"));
        left.add(Box.createVerticalStrut(12));
        left.add(feature("• Delete for me / Delete for everyone"));
        left.add(Box.createVerticalStrut(12));
        left.add(feature("• Persistent local chat history"));

        left.add(Box.createVerticalGlue());

        JLabel foot = GlassTheme.label(
                "No internet required • Works on multiple PCs",
                12, false, GlassTheme.TEXT_FAINT);
        left.add(foot);

        return left;
    }

    private JComponent buildRightCard() {
        GlassPanel card = new GlassPanel();
        card.setRadius(34);
        card.setStrongBorder(true);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(30, 30, 30, 30));

        JLabel title = GlassTheme.label(
                registerMode ? "Create account" : "Welcome back",
                28, true, GlassTheme.TEXT
        );
        card.add(title);
        card.add(Box.createVerticalStrut(8));

        card.add(GlassTheme.label(
                registerMode ? "Register your local identity on this PC" : "Login to continue",
                13, false, GlassTheme.TEXT_FAINT
        ));
        card.add(Box.createVerticalStrut(26));

        card.add(fieldTitle("Username"));
        card.add(Box.createVerticalStrut(6));
        usernameField = field();
        card.add(wrapField(usernameField));

        card.add(Box.createVerticalStrut(14));
        card.add(fieldTitle("Password"));
        card.add(Box.createVerticalStrut(6));
        passwordField = passwordField();
        card.add(wrapField(passwordField));

        if (registerMode) {
            card.add(Box.createVerticalStrut(14));
            card.add(fieldTitle("Confirm password"));
            card.add(Box.createVerticalStrut(6));
            confirmField = passwordField();
            card.add(wrapField(confirmField));

            card.add(Box.createVerticalStrut(14));
            GlassButton photo = new GlassButton(
                    selectedAvatar == null ? "Choose profile picture" : "Change profile picture",
                    false
            );
            photo.setAlignmentX(Component.LEFT_ALIGNMENT);
            photo.addActionListener(e -> chooseAvatar());
            card.add(photo);
        } else {
            confirmField = null;
        }

        card.add(Box.createVerticalStrut(14));

        errorLabel = GlassTheme.label(" ", 12, false, GlassTheme.DANGER);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(errorLabel);

        card.add(Box.createVerticalStrut(10));

        actionButton = new GlassButton(
                registerMode ? "Create account" : "Sign in",
                true
        );
        actionButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionButton.addActionListener(e -> authenticate());
        card.add(actionButton);

        card.add(Box.createVerticalStrut(12));

        GlassButton toggle = new GlassButton(
                registerMode ? "Already have an account? Sign in" : "New user? Create account",
                false
        );
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        toggle.addActionListener(e -> {
            if (!busy) {
                registerMode = !registerMode;
                buildUI();
            }
        });
        card.add(toggle);

        card.add(Box.createVerticalGlue());

        return card;
    }

    private JLabel fieldTitle(String text) {
        JLabel label = GlassTheme.label(text, 12, true, GlassTheme.TEXT_SOFT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(GlassTheme.font(15, false));
        area.setForeground(GlassTheme.TEXT_SOFT);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        return area;
    }

    private JLabel feature(String text) {
        JLabel label = GlassTheme.label(text, 14, false, GlassTheme.TEXT_SOFT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JTextField field() {
        JTextField f = new JTextField();
        GlassTheme.styleInput(f);
        return f;
    }

    private JPasswordField passwordField() {
        JPasswordField f = new JPasswordField();
        GlassTheme.styleInput(f);
        return f;
    }

    private JComponent wrapField(JComponent field) {
        GlassPanel shell = new GlassPanel(new BorderLayout());
        shell.setRadius(18);
        shell.setGlassColors(new Color(255,255,255,28), new Color(255,255,255,12));
        shell.setBorder(new EmptyBorder(0, 0, 0, 0));
        shell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        shell.setPreferredSize(new Dimension(300, 46));
        shell.add(field, BorderLayout.CENTER);
        field.setPreferredSize(new Dimension(300, 46));
        return shell;
    }

    private void chooseAvatar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose profile picture");

        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (PNG, JPG, JPEG)", "png", "jpg", "jpeg"
        ));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path path = chooser.getSelectedFile().toPath();
                AvatarImages.process(path);
                selectedAvatar = path;
                errorLabel.setForeground(GlassTheme.ONLINE);
                errorLabel.setText("Profile picture selected");
            } catch (Exception e) {
                errorLabel.setForeground(GlassTheme.DANGER);
                errorLabel.setText(messageOf(e));
            }
        }
    }

    private void authenticate() {
        if (busy) return;

        String username = usernameField.getText().trim();
        char[] password = passwordField.getPassword();

        if (username.isBlank() || password.length == 0) {
            Arrays.fill(password, '\0');
            showError("Enter username and password.");
            return;
        }

        if (registerMode) {
            char[] confirm = confirmField.getPassword();
            boolean ok = Arrays.equals(password, confirm);
            Arrays.fill(confirm, '\0');

            if (!ok) {
                Arrays.fill(password, '\0');
                showError("Passwords do not match.");
                return;
            }
        }

        busy = true;
        actionButton.setEnabled(false);
        errorLabel.setForeground(GlassTheme.TEXT_FAINT);
        errorLabel.setText("Please wait...");

        CompletableFuture<Profile> flow = registerMode
                ? engine.register(username, password, selectedAvatar)
                : engine.login(username, password);

        flow.thenCompose(profile -> engine.startNetworking().thenApply(v -> profile))
                .whenComplete((profile, error) -> SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        busy = false;
                        actionButton.setEnabled(true);
                        showError(messageOf(error));
                        return;
                    }

                    MessengerScreen screen = new MessengerScreen(engine, profile);
                    screen.setVisible(true);
                    dispose();
                }));
    }

    private void showError(String msg) {
        errorLabel.setForeground(GlassTheme.DANGER);
        errorLabel.setText(msg == null ? "Unexpected error" : msg);
    }

    private String messageOf(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}