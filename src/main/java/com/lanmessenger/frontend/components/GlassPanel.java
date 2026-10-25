package com.lanmessenger.frontend.components;

import com.lanmessenger.frontend.theme.GlassTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class GlassPanel extends JPanel {

    private int radius = 26;
    private Color top = GlassTheme.PANEL_LIGHT;
    private Color bottom = GlassTheme.PANEL_DARK;
    private boolean strongBorder = false;

    public GlassPanel() {
        setOpaque(false);
    }

    public GlassPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    public void setRadius(int radius) {
        this.radius = Math.max(0, radius);
        repaint();
    }

    public void setStrongBorder(boolean strongBorder) {
        this.strongBorder = strongBorder;
        repaint();
    }

    public void setGlassColors(Color top, Color bottom) {
        this.top = top;
        this.bottom = bottom;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics original) {
        Graphics2D g = (Graphics2D) original.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        Shape shape = new RoundRectangle2D.Float(1, 1, w - 2, h - 2, radius, radius);

        g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
        g.fill(shape);

        g.setColor(new Color(255, 255, 255, 24));
        g.fillRoundRect(6, 4, w - 12, Math.max(12, h / 3), radius - 4, radius - 4);

        g.setColor(strongBorder ? new Color(255, 255, 255, 74) : GlassTheme.BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);

        g.dispose();
        super.paintComponent(original);
    }
}