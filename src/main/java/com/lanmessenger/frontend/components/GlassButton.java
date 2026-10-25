package com.lanmessenger.frontend.components;

import com.lanmessenger.frontend.theme.GlassTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class GlassButton extends JButton {

    private final boolean primary;
    private boolean hovered;

    public GlassButton(String text, boolean primary) {
        super(text);
        this.primary = primary;

        setFont(GlassTheme.font(14, true));
        setForeground(GlassTheme.TEXT);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new Insets(11, 16, 11, 16));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width + 18, Math.max(40, d.height + 10));
    }

    @Override
    protected void paintComponent(Graphics original) {
        Graphics2D g = (Graphics2D) original.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        Color c1;
        Color c2;

        if (primary) {
            c1 = hovered ? new Color(136, 146, 255) : GlassTheme.PRIMARY_1;
            c2 = hovered ? new Color(92, 112, 246) : GlassTheme.PRIMARY_2;
        } else {
            c1 = hovered ? new Color(255, 255, 255, 42) : new Color(255, 255, 255, 28);
            c2 = hovered ? new Color(255, 255, 255, 18) : new Color(255, 255, 255, 12);
        }

        if (!isEnabled()) {
            c1 = new Color(130, 138, 156, 60);
            c2 = new Color(100, 109, 130, 40);
        }

        g.setPaint(new GradientPaint(0, 0, c1, 0, h, c2));
        g.fillRoundRect(1, 1, w - 2, h - 2, 18, 18);

        g.setColor(new Color(255, 255, 255, primary ? 66 : 42));
        g.drawRoundRect(1, 1, w - 2, h - 2, 18, 18);

        g.dispose();
        super.paintComponent(original);
    }
}