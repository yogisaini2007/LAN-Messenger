
package com.lanmessenger.frontend.components;

import com.lanmessenger.frontend.theme.GlassTheme;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

public final class AvatarView extends JComponent {

    private BufferedImage image;
    private String name = "?";
    private boolean online;

    public AvatarView(int size) {
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setOpaque(false);
    }

    public void setProfile(
            String name,
            byte[] avatar,
            boolean online) {

        this.name = name == null || name.isBlank()
                ? "?" : name;

        this.online = online;
        image = null;

        if (avatar != null && avatar.length > 0) {
            try {
                image = ImageIO.read(
                        new ByteArrayInputStream(avatar));
            } catch (Exception ignored) {
                image = null;
            }
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics original) {

        Graphics2D g = (Graphics2D) original.create();

        g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        int side = Math.min(
                getWidth(), getHeight()) - 5;

        if (side <= 0) {
            g.dispose();
            return;
        }

        Ellipse2D circle =
                new Ellipse2D.Float(1, 1, side, side);

        g.setPaint(new GradientPaint(
                0, 0, new Color(101, 127, 248),
                side, side, new Color(58, 70, 162)));

        g.fill(circle);

        if (image != null) {
            Shape old = g.getClip();
            g.setClip(circle);

            int w = image.getWidth();
            int h = image.getHeight();

            double scale = Math.max(
                    side / (double) w,
                    side / (double) h);

            int dw = (int) Math.ceil(w * scale);
            int dh = (int) Math.ceil(h * scale);

            g.drawImage(
                    image,
                    1 + (side - dw) / 2,
                    1 + (side - dh) / 2,
                    dw, dh, null);

            g.setClip(old);

        } else {
            String initial = name.substring(0, 1)
                    .toUpperCase();

            g.setColor(GlassTheme.TEXT);
            g.setFont(GlassTheme.font(
                    Math.max(14, side / 2), true));

            FontMetrics fm = g.getFontMetrics();

            g.drawString(
                    initial,
                    1 + (side - fm.stringWidth(initial)) / 2,
                    1 + (side - fm.getHeight()) / 2
                            + fm.getAscent());
        }

        g.setColor(new Color(237, 245, 255, 85));
        g.setStroke(new BasicStroke(1f));
        g.draw(circle);

        if (online) {
            int d = Math.max(9, side / 5);
            int x = side - d + 2;
            int y = side - d + 2;

            g.setColor(new Color(15, 24, 48));
            g.fillOval(x - 2, y - 2, d + 4, d + 4);

            g.setColor(GlassTheme.ONLINE);
            g.fillOval(x, y, d, d);
        }

        g.dispose();
    }
}

