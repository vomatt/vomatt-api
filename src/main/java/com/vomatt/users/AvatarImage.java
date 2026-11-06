package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Turns an uploaded image into the stored avatar: center-cropped square, {@value #SIZE}x{@value #SIZE} JPEG.
 * Re-encoding from decoded pixels drops EXIF (GPS included) and anything else the upload carried besides the image.
 */
final class AvatarImage {

    static final int SIZE = 512;

    /** Decompression bomb guard: a small file can declare a huge canvas, checked before the pixels are decoded. */
    static final long MAX_PIXELS = 40_000_000L;

    private AvatarImage() {}

    /** @throws ApiException 400 {@code user.avatar.unsupported_image} when the bytes are not a decodable image within limits */
    static byte[] toJpeg(InputStream upload) {
        BufferedImage image = decodeCenterSquare(upload);
        int side = Math.min(image.getWidth(), image.getHeight());
        // Halving steps before the final scale keep bilinear downscaling from aliasing on large photos
        while (side / 2 >= SIZE) {
            side /= 2;
            image = scale(image, side);
        }
        image = scale(image, SIZE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // In-memory stream: ImageIO.write to a plain OutputStream would cache through a temp file
        try (ImageOutputStream jpeg = new MemoryCacheImageOutputStream(out)) {
            ImageIO.write(image, "jpg", jpeg);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        }
        return out.toByteArray();
    }

    /** Decodes only the centered square, subsampled when far above SIZE, so memory stays bounded for any accepted shape. */
    private static BufferedImage decodeCenterSquare(InputStream upload) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(upload)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    throw unsupported();
                }
                int side = Math.min(width, height);
                int factor = Math.max(1, side / (SIZE * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle((width - side) / 2, (height - side) / 2, side, side));
                param.setSourceSubsampling(factor, factor, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unsupported();
        }
    }

    /** Draws onto an opaque canvas: transparent pixels become white, as JPEG has no alpha. */
    private static BufferedImage scale(BufferedImage source, int side) {
        BufferedImage target = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, side, side, Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static ApiException unsupported() {
        return ApiException.badRequest(MessageKey.USER_AVATAR_UNSUPPORTED_IMAGE);
    }
}
