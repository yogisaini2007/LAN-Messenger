
package com.lanmessenger.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class AvatarImages {

    private static final int SIZE = 256;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private AvatarImages() {}

    public static byte[] process(Path path) throws IOException {
        if (path == null) {
            return new byte[0];
        }

        if (!Files.isRegularFile(path)) {
            throw new IOException("Profile image not found");
        }

        if (Files.size(path) > MAX_FILE_SIZE) {
            throw new IOException("Profile image exceeds 5 MB");
        }

        BufferedImage source = ImageIO.read(path.toFile());

        if (source == null) {
            throw new IOException("Unsupported profile image");
        }

        int width = source.getWidth();
        int height = source.getHeight();

        int crop = Math.min(width, height);
        int x = (width - crop) / 2;
        int y = (height - crop) / 2;

        BufferedImage result = new BufferedImage(
                SIZE,
                SIZE,
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = result.createGraphics();

        try {
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );

            g.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );

            g.drawImage(
                    source,
                    0, 0, SIZE, SIZE,
                    x, y, x + crop, y + crop,
                    null
            );

        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            ImageIO.write(result, "png", output);
            return output.toByteArray();
        }
    }
}
