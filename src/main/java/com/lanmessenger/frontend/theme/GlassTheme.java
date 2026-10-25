package com.lanmessenger.frontend.theme;

import java.awt.*;
import java.awt.geom.Point2D;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;

public final class GlassTheme {

    public static final Color BG_1 = new Color(8, 14, 31);
    public static final Color BG_2 = new Color(20, 26, 56);

    public static final Color TEXT = new Color(245, 248, 255);
    public static final Color TEXT_SOFT = new Color(195, 206, 230);
    public static final Color TEXT_FAINT = new Color(143, 158, 188);

    public static final Color GLASS = new Color(255, 255, 255, 26);
    public static final Color GLASS_STRONG = new Color(255, 255, 255, 36);
    public static final Color BORDER = new Color(255, 255, 255, 52);

    public static final Color PRIMARY_1 = new Color(120, 132, 255);
    public static final Color PRIMARY_2 = new Color(86, 103, 236);
    public static final Color PRIMARY_3 = new Color(69, 81, 194);

    public static final Color PANEL_DARK = new Color(18, 28, 58, 160);
    public static final Color PANEL_LIGHT = new Color(40, 56, 102, 135);

    public static final Color ONLINE = new Color(74, 233, 178);
    public static final Color DANGER = new Color(255, 120, 144);

    private GlassTheme() {}

    public static Font font(int size, boolean bold) {
        return new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size);
    }

    public static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font(size, bold));
        label.setForeground(color);
        return label;
    }

    public static void styleInput(JTextComponent field) {
        field.setFont(font(14, false));
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setOpaque(false);
        field.setBorder(new EmptyBorder(12, 14, 12, 14));
    }

    public static JScrollPane makeScroll(Component content) {
        JScrollPane pane = new JScrollPane(content);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        return pane;
    }

    public static class Backdrop extends JPanel {

        public Backdrop() {
            setOpaque(true);
            setBackground(BG_1);
        }

        @Override
        protected void paintComponent(Graphics original) {
            super.paintComponent(original);

            Graphics2D g = (Graphics2D) original.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g.setPaint(new GradientPaint(0, 0, BG_1, w, h, BG_2));
            g.fillRect(0, 0, w, h);

            drawGlow(g, w * 0.82f, h * 0.10f, Math.max(220, w * 0.28f), new Color(112, 78, 255, 95));
            drawGlow(g, w * 0.14f, h * 0.90f, Math.max(240, w * 0.32f), new Color(42, 145, 255, 70));
            drawGlow(g, w * 0.52f, h * 0.42f, Math.max(180, w * 0.18f), new Color(255, 255, 255, 18));

            g.dispose();
        }

        private void drawGlow(Graphics2D g, float cx, float cy, float radius, Color color) {
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(cx, cy),
                    radius,
                    new float[]{0f, 1f},
                    new Color[]{
                            color,
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)
                    }
            );
            g.setPaint(paint);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}