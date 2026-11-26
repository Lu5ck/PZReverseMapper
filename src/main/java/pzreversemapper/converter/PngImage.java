package pzreversemapper.converter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Minimal RGBA8 image with a streaming PNG encoder and an 8-bit, non-interlaced PNG decoder,
 * ported from the original converter so images never need to be fully materialised by AWT.
 */
final class PngImage {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private final int width;
    private final int height;
    private final byte[] pixels;

    PngImage(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Raw RGBA pixel data, row-major. */
    byte[] pixels() {
        return pixels;
    }

    @FunctionalInterface
    interface RowWriter {
        /** Fills {@code row} (cleared to zero, {@code width * 4} bytes) with the RGBA pixels of row {@code y}. */
        void writeRow(int y, byte[] row) throws IOException;
    }

    static PngImage load(Path file) throws IOException {
        long fileLength = Files.size(file);
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream stream = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
            byte[] signature = stream.readNBytes(PNG_SIGNATURE.length);
            if (!Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new InvalidDataException("Not a PNG file: " + file);
            }

            long position = PNG_SIGNATURE.length;
            int imageWidth = 0;
            int imageHeight = 0;
            int bitDepth = 0;
            int colorType = 0;
            byte[] palette = null;
            byte[] transparency = null;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();

            while (position < fileLength) {
                int length = stream.readInt();
                String type = new String(stream.readNBytes(4), StandardCharsets.US_ASCII);
                if (length < 0) {
                    throw new InvalidDataException("Invalid PNG chunk length in " + file);
                }

                byte[] data = stream.readNBytes(length);
                stream.readNBytes(4);
                position += 12L + length;

                switch (type) {
                    case "IHDR" -> {
                        if (data.length < 13) {
                            throw new InvalidDataException("Truncated PNG header in " + file);
                        }

                        imageWidth = readBigEndianInt(data, 0);
                        imageHeight = readBigEndianInt(data, 4);
                        bitDepth = data[8] & 0xff;
                        colorType = data[9] & 0xff;
                        if (data[10] != 0 || data[11] != 0 || data[12] != 0) {
                            throw new IOException("Unsupported PNG compression/filter/interlace in " + file);
                        }
                    }
                    case "PLTE" -> palette = data;
                    case "tRNS" -> transparency = data;
                    case "IDAT" -> idat.write(data);
                    case "IEND" -> {
                        return decode(imageWidth, imageHeight, bitDepth, colorType, palette, transparency, idat.toByteArray(), file);
                    }
                    default -> {
                        // Ancillary chunk: ignored.
                    }
                }
            }

            throw new InvalidDataException("PNG is missing IEND: " + file);
        } catch (EOFException ex) {
            throw new InvalidDataException("Unexpected end of PNG file: " + file);
        }
    }

    void clear(Rgba color) {
        if (pixels.length == 0) {
            return;
        }

        if (color.r() == color.g() && color.g() == color.b() && color.b() == color.a()) {
            Arrays.fill(pixels, (byte) color.r());
            return;
        }

        pixels[0] = (byte) color.r();
        pixels[1] = (byte) color.g();
        pixels[2] = (byte) color.b();
        pixels[3] = (byte) color.a();
        for (int filled = 4; filled < pixels.length; filled *= 2) {
            System.arraycopy(pixels, 0, pixels, filled, Math.min(filled, pixels.length - filled));
        }
    }

    void setPixel(int x, int y, Rgba color) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }

        int offset = (x + y * width) * 4;
        pixels[offset] = (byte) color.r();
        pixels[offset + 1] = (byte) color.g();
        pixels[offset + 2] = (byte) color.b();
        pixels[offset + 3] = (byte) color.a();
    }

    void fillRect(int x, int y, int rectWidth, int rectHeight, Rgba color) {
        for (int yy = y; yy < y + rectHeight; yy++) {
            for (int xx = x; xx < x + rectWidth; xx++) {
                setPixel(xx, yy, color);
            }
        }
    }

    /** Copies a whole image to (left, top), clipping pixels that fall outside this image. */
    void drawImage(PngImage image, int left, int top) {
        for (int y = 0; y < image.height; y++) {
            int targetY = top + y;
            if (targetY < 0 || targetY >= height || left < 0 || left + image.width > width) {
                for (int x = 0; x < image.width; x++) {
                    int source = (x + y * image.width) * 4;
                    int targetX = left + x;
                    if (targetX < 0 || targetY < 0 || targetX >= width || targetY >= height) {
                        continue;
                    }

                    System.arraycopy(image.pixels, source, pixels, (targetX + targetY * width) * 4, 4);
                }

                continue;
            }

            System.arraycopy(image.pixels, y * image.width * 4, pixels, (left + targetY * width) * 4, image.width * 4);
        }
    }

    /** Copies a source rectangle of {@code image} to (left, top), clipping on both sides. */
    void drawImage(PngImage image, int sourceX, int sourceY, int rectWidth, int rectHeight, int left, int top) {
        for (int y = 0; y < rectHeight; y++) {
            int sy = sourceY + y;
            int dy = top + y;
            if (sy < 0 || sy >= image.height || dy < 0 || dy >= height) {
                continue;
            }

            int start = Math.max(0, Math.max(-sourceX, -left));
            int end = Math.min(rectWidth, Math.min(image.width - sourceX, width - left));
            if (end <= start) {
                continue;
            }

            System.arraycopy(
                image.pixels,
                ((sourceX + start) + sy * image.width) * 4,
                pixels,
                ((left + start) + dy * width) * 4,
                (end - start) * 4);
        }
    }

    /** Copies row {@code y} into {@code destination} at {@code offset}, truncated to the space available. */
    void copyRowTo(int y, byte[] destination, int offset) {
        if (y < 0 || y >= height || offset >= destination.length) {
            return;
        }

        System.arraycopy(pixels, y * width * 4, destination, offset, Math.min(destination.length - offset, width * 4));
    }

    void save(Path file) throws IOException {
        saveRows(file, width, height, (y, row) -> System.arraycopy(pixels, y * width * 4, row, 0, width * 4));
    }

    /** Streams an RGBA8 PNG row by row, so arbitrarily tall images need only one row in memory. */
    static void saveRows(Path file, int width, int height, RowWriter writeRow) throws IOException {
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            stream.write(PNG_SIGNATURE);
            writeChunk(stream, "IHDR", buildHeader(width, height), 13);
            writeRowsImageData(stream, width, height, writeRow);
            writeChunk(stream, "IEND", new byte[0], 0);
        }
    }

    private static byte[] buildHeader(int width, int height) {
        byte[] header = new byte[13];
        writeBigEndianInt(header, 0, width);
        writeBigEndianInt(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        return header;
    }

    private static void writeRowsImageData(OutputStream stream, int width, int height, RowWriter writeRow) throws IOException {
        PngChunkOutputStream idat = new PngChunkOutputStream(stream, "IDAT");
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            DeflaterOutputStream zlib = new DeflaterOutputStream(idat, deflater, 1 << 16);
            byte[] row = new byte[Math.multiplyExact(width, 4)];
            for (int y = 0; y < height; y++) {
                Arrays.fill(row, (byte) 0);
                writeRow.writeRow(y, row);
                zlib.write(0);
                zlib.write(row);
            }

            zlib.finish();
            idat.flushBuffer();
        } finally {
            deflater.end();
        }
    }

    private static PngImage decode(
        int width,
        int height,
        int bitDepth,
        int colorType,
        byte[] palette,
        byte[] transparency,
        byte[] compressedData,
        Path file) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new InvalidDataException("Invalid PNG dimensions in " + file);
        }

        if (bitDepth != 8) {
            throw new IOException("Only 8-bit PNG files are supported: " + file);
        }

        int bytesPerPixel = switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new IOException("Unsupported PNG color type " + colorType + " in " + file);
        };

        if (colorType == 3 && palette == null) {
            throw new InvalidDataException("Indexed PNG is missing PLTE: " + file);
        }

        int stride = Math.multiplyExact(width, bytesPerPixel);
        int expected = Math.multiplyExact(stride + 1, height);
        byte[] raw = inflate(compressedData, expected, file);

        PngImage image = new PngImage(width, height);
        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        int source = 0;

        for (int y = 0; y < height; y++) {
            int filter = raw[source++] & 0xff;
            System.arraycopy(raw, source, current, 0, stride);
            source += stride;
            unfilter(current, previous, bytesPerPixel, filter);
            writeDecodedRow(image, y, current, colorType, palette, transparency);

            byte[] swap = previous;
            previous = current;
            current = swap;
        }

        return image;
    }

    private static byte[] inflate(byte[] compressedData, int expected, Path file) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressedData);
            byte[] raw = new byte[expected];
            int total = 0;
            while (total < expected && !inflater.finished()) {
                int count = inflater.inflate(raw, total, expected - total);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }

                total += count;
            }

            if (total < expected) {
                throw new InvalidDataException("PNG data is shorter than expected: " + file);
            }

            return raw;
        } catch (DataFormatException ex) {
            throw new InvalidDataException("Corrupt PNG image data in " + file + ": " + Text.messageOf(ex));
        } finally {
            inflater.end();
        }
    }

    private static void unfilter(byte[] current, byte[] previous, int bytesPerPixel, int filter) throws IOException {
        int length = current.length;
        switch (filter) {
            case 0 -> {
            }
            case 1 -> {
                for (int i = bytesPerPixel; i < length; i++) {
                    current[i] = (byte) (current[i] + current[i - bytesPerPixel]);
                }
            }
            case 2 -> {
                for (int i = 0; i < length; i++) {
                    current[i] = (byte) (current[i] + previous[i]);
                }
            }
            case 3 -> {
                for (int i = 0; i < length; i++) {
                    int left = i >= bytesPerPixel ? current[i - bytesPerPixel] & 0xff : 0;
                    int up = previous[i] & 0xff;
                    current[i] = (byte) (current[i] + ((left + up) / 2));
                }
            }
            case 4 -> {
                for (int i = 0; i < length; i++) {
                    int left = i >= bytesPerPixel ? current[i - bytesPerPixel] & 0xff : 0;
                    int up = previous[i] & 0xff;
                    int upLeft = i >= bytesPerPixel ? previous[i - bytesPerPixel] & 0xff : 0;
                    current[i] = (byte) (current[i] + paeth(left, up, upLeft));
                }
            }
            default -> throw new InvalidDataException("Unsupported PNG filter type " + filter);
        }
    }

    private static int paeth(int left, int up, int upLeft) {
        int p = left + up - upLeft;
        int pa = Math.abs(p - left);
        int pb = Math.abs(p - up);
        int pc = Math.abs(p - upLeft);
        if (pa <= pb && pa <= pc) {
            return left;
        }

        return pb <= pc ? up : upLeft;
    }

    private static void writeDecodedRow(PngImage image, int y, byte[] row, int colorType, byte[] palette, byte[] transparency) {
        byte[] target = image.pixels;
        int dest = y * image.width * 4;
        switch (colorType) {
            case 0 -> {
                for (int x = 0; x < image.width; x++, dest += 4) {
                    byte gray = row[x];
                    target[dest] = gray;
                    target[dest + 1] = gray;
                    target[dest + 2] = gray;
                    target[dest + 3] = (byte) 255;
                }
            }
            case 2 -> {
                for (int x = 0, source = 0; x < image.width; x++, dest += 4, source += 3) {
                    target[dest] = row[source];
                    target[dest + 1] = row[source + 1];
                    target[dest + 2] = row[source + 2];
                    target[dest + 3] = (byte) 255;
                }
            }
            case 3 -> {
                for (int x = 0; x < image.width; x++, dest += 4) {
                    int index = row[x] & 0xff;
                    int paletteOffset = index * 3;
                    if (palette == null || paletteOffset + 2 >= palette.length) {
                        target[dest + 3] = (byte) 255;
                        continue;
                    }

                    target[dest] = palette[paletteOffset];
                    target[dest + 1] = palette[paletteOffset + 1];
                    target[dest + 2] = palette[paletteOffset + 2];
                    target[dest + 3] = transparency != null && index < transparency.length ? transparency[index] : (byte) 255;
                }
            }
            case 4 -> {
                for (int x = 0, source = 0; x < image.width; x++, dest += 4, source += 2) {
                    byte gray = row[source];
                    target[dest] = gray;
                    target[dest + 1] = gray;
                    target[dest + 2] = gray;
                    target[dest + 3] = row[source + 1];
                }
            }
            case 6 -> System.arraycopy(row, 0, target, dest, image.width * 4);
            default -> {
            }
        }
    }

    private static void writeChunk(OutputStream stream, String type, byte[] data, int length) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[4];
        writeBigEndianInt(header, 0, length);
        stream.write(header);
        stream.write(typeBytes);
        stream.write(data, 0, length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data, 0, length);
        byte[] crcBytes = new byte[4];
        writeBigEndianInt(crcBytes, 0, (int) crc.getValue());
        stream.write(crcBytes);
    }

    private static void writeBigEndianInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    /** Buffers compressed data and emits it as PNG chunks of up to 64 KiB. */
    private static final class PngChunkOutputStream extends OutputStream {
        private static final int BUFFER_SIZE = 64 * 1024;
        private final OutputStream output;
        private final String chunkType;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int count;

        private PngChunkOutputStream(OutputStream output, String chunkType) {
            this.output = output;
            this.chunkType = chunkType;
        }

        @Override
        public void write(int b) throws IOException {
            buffer[count++] = (byte) b;
            if (count == buffer.length) {
                flushBuffer();
            }
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            while (length > 0) {
                int copy = Math.min(length, buffer.length - count);
                System.arraycopy(data, offset, buffer, count, copy);
                count += copy;
                offset += copy;
                length -= copy;
                if (count == buffer.length) {
                    flushBuffer();
                }
            }
        }

        @Override
        public void flush() {
            // Chunks are emitted only when full or when the image data ends.
        }

        void flushBuffer() throws IOException {
            if (count == 0) {
                return;
            }

            writeChunk(output, chunkType, buffer, count);
            count = 0;
        }
    }
}
