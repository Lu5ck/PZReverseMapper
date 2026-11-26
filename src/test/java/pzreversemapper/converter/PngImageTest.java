package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PngImageTest {
    @TempDir
    Path temp;

    @Test
    void encodedImagesDecodeIdenticallyWithImageIo() throws Exception {
        PngImage image = new PngImage(301, 77);
        Random random = new Random(1);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                image.setPixel(x, y, new Rgba(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            }
        }

        Path file = temp.resolve("random.png");
        image.save(file);
        BufferedImage decoded = ImageIO.read(file.toFile());
        assertEquals(301, decoded.getWidth());
        assertEquals(77, decoded.getHeight());
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                assertEquals(argb(image, x, y), decoded.getRGB(x, y), "pixel " + x + "," + y);
            }
        }

        assertArrayEquals(image.pixels(), PngImage.load(file).pixels());
    }

    @Test
    void decodesFilteredImagesOfEveryColourType() throws Exception {
        Random random = new Random(2);
        int[] types = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY};
        for (int type : types) {
            BufferedImage source = new BufferedImage(53, 41, type);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int value = (x * 5 + y * 3) & 0xff;
                    source.setRGB(x, y, ((random.nextInt(2) == 0 ? 255 : 90) << 24) | (value << 16) | ((255 - value) << 8) | random.nextInt(256));
                }
            }

            Path file = temp.resolve("type" + type + ".png");
            ImageIO.write(source, "png", file.toFile());
            BufferedImage reference = ImageIO.read(file.toFile());
            PngImage decoded = PngImage.load(file);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // getRGB gamma-converts linear grey images, so compare grey PNGs on raw samples.
                    int expected = type == BufferedImage.TYPE_BYTE_GRAY
                        ? 0xff000000 | (reference.getRaster().getSample(x, y, 0) * 0x010101)
                        : reference.getRGB(x, y);
                    assertEquals(expected, argb(decoded, x, y), "type " + type + " pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void decodesIndexedImagesWithTransparency() throws Exception {
        byte[] r = {0, (byte) 255, 10, 40};
        byte[] g = {0, 0, (byte) 200, 50};
        byte[] b = {0, 0, 30, (byte) 250};
        byte[] a = {0, (byte) 255, (byte) 128, (byte) 255};
        BufferedImage source = new BufferedImage(20, 9, BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(8, 4, r, g, b, a));
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 20; x++) {
                source.getRaster().setSample(x, y, 0, (x + y) % 4);
            }
        }

        Path file = temp.resolve("indexed.png");
        ImageIO.write(source, "png", file.toFile());
        PngImage decoded = PngImage.load(file);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 20; x++) {
                int index = (x + y) % 4;
                int expected = ((a[index] & 0xff) << 24) | ((r[index] & 0xff) << 16) | ((g[index] & 0xff) << 8) | (b[index] & 0xff);
                assertEquals(expected, argb(decoded, x, y));
            }
        }
    }

    @Test
    void streamedRowsProduceTallImagesWithMultipleIdatChunks() throws Exception {
        Path file = temp.resolve("tall.png");
        PngImage.saveRows(file, 64, 4000, (y, row) -> {
            for (int x = 0; x < 64; x++) {
                row[x * 4] = (byte) y;
                row[x * 4 + 1] = (byte) (y >> 8);
                row[x * 4 + 2] = (byte) (x * 17 + y);
                row[x * 4 + 3] = (byte) 255;
            }
        });

        PngImage decoded = PngImage.load(file);
        assertEquals(4000, decoded.height());
        int offset = (5 + 3999 * 64) * 4;
        assertEquals((byte) 3999, decoded.pixels()[offset]);
        assertEquals((byte) (3999 >> 8), decoded.pixels()[offset + 1]);
        assertEquals((byte) (5 * 17 + 3999), decoded.pixels()[offset + 2]);
    }

    @Test
    void drawImageClipsSourceAndTarget() {
        PngImage source = new PngImage(4, 4);
        source.clear(new Rgba(9, 8, 7, 6));
        PngImage target = new PngImage(5, 5);
        target.drawImage(source, -1, 1, 4, 2, 3, -1);
        assertEquals(0, target.pixels()[(3 + 0 * 5) * 4 + 3]);
        assertEquals(6, target.pixels()[(4 + 0 * 5) * 4 + 3]);
        assertEquals(0, target.pixels()[(4 + 1 * 5) * 4 + 3]);

        PngImage whole = new PngImage(3, 3);
        whole.drawImage(source, 1, 1);
        assertEquals(6, whole.pixels()[(2 + 2 * 3) * 4 + 3]);
        assertEquals(0, whole.pixels()[3]);
    }

    private static int argb(PngImage image, int x, int y) {
        byte[] p = image.pixels();
        int offset = (x + y * image.width()) * 4;
        return ((p[offset + 3] & 0xff) << 24) | ((p[offset] & 0xff) << 16) | ((p[offset + 1] & 0xff) << 8) | (p[offset + 2] & 0xff);
    }
}
