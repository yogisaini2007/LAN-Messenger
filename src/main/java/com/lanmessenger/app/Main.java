
package com.lanmessenger.app;

import com.lanmessenger.frontend.screens.AuthScreen;

import javax.swing.*;
import java.awt.*;

public final class Main {

    private Main() {}

    public static void main(String[] args) {

        System.setProperty(
                "awt.useSystemAAFontSettings", "on");
        System.setProperty(
                "swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {

            try {
                UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName());

                UIManager.put(
                        "OptionPane.messageForeground",
                        Color.WHITE);

                MessengerEngine engine = new MessengerEngine();

                AuthScreen screen = new AuthScreen(engine);
                screen.setVisible(true);

            } catch (Exception e) {

                JOptionPane.showMessageDialog(
                        null,
                        "LAN Messenger failed to start:\n"
                                + e.getMessage(),
                        "Startup Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
