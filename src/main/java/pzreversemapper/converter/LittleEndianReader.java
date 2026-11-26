package pzreversemapper.converter;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Buffered, seekable little-endian reader over a file. Mirrors the subset of
 * {@code System.IO.BinaryReader} + {@code FileStream} used by the original converter.
 */
final class LittleEndianReader implements Closeable {
    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileChannel channel;
    private final long length;
    private final ByteBuffer buffer;
    private long bufferStart;

    LittleEndianReader(Path file) throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.READ);
        length = channel.size();
        buffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.limit(0);
        bufferStart = 0;
    }

    long length() {
        return length;
    }

    long position() {
        return bufferStart + buffer.position();
    }

    void position(long position) {
        if (position < 0) {
            throw new IllegalArgumentException("Negative stream position: " + position);
        }

        if (position >= bufferStart && position <= bufferStart + buffer.limit()) {
            buffer.position((int) (position - bufferStart));
            return;
        }

        bufferStart = position;
        buffer.clear();
        buffer.limit(0);
    }

    void skip(long count) {
        position(position() + count);
    }

    boolean hasRemaining() {
        return position() < length;
    }

    int readInt() throws IOException {
        require(4);
        return buffer.getInt();
    }

    long readUInt32() throws IOException {
        return readInt() & 0xffffffffL;
    }

    long readLong() throws IOException {
        require(8);
        return buffer.getLong();
    }

    int readUnsignedByte() throws IOException {
        require(1);
        return buffer.get() & 0xff;
    }

    /** Reads up to {@code count} bytes; fewer are returned at end of file, like BinaryReader.ReadBytes. */
    byte[] readBytes(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("Negative byte count: " + count);
        }

        long available = Math.max(0, length - position());
        int total = (int) Math.min(count, available);
        byte[] result = new byte[total];
        int fromBuffer = Math.min(total, buffer.remaining());
        buffer.get(result, 0, fromBuffer);
        int offset = fromBuffer;
        if (offset < total) {
            long filePosition = position();
            ByteBuffer target = ByteBuffer.wrap(result, offset, total - offset);
            while (target.hasRemaining()) {
                int read = channel.read(target, filePosition);
                if (read < 0) {
                    break;
                }

                filePosition += read;
            }

            int actual = target.position();
            bufferStart = filePosition;
            buffer.clear();
            buffer.limit(0);
            if (actual < total) {
                byte[] shorter = new byte[actual];
                System.arraycopy(result, 0, shorter, 0, actual);
                return shorter;
            }
        }

        return result;
    }

    /**
     * Reads {@code count} UTF-16 characters decoded from UTF-8, like BinaryReader.ReadChars with a
     * UTF-8 encoding. Invalid sequences decode to U+FFFD.
     */
    String readUtf8Chars(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("Negative character count: " + count);
        }

        StringBuilder builder = new StringBuilder(count);
        while (builder.length() < count) {
            if (!hasRemaining()) {
                break;
            }

            int b0 = readUnsignedByte();
            if (b0 < 0x80) {
                builder.append((char) b0);
                continue;
            }

            int needed;
            int codePoint;
            if ((b0 & 0xe0) == 0xc0) {
                needed = 1;
                codePoint = b0 & 0x1f;
            } else if ((b0 & 0xf0) == 0xe0) {
                needed = 2;
                codePoint = b0 & 0x0f;
            } else if ((b0 & 0xf8) == 0xf0) {
                needed = 3;
                codePoint = b0 & 0x07;
            } else {
                builder.append('�');
                continue;
            }

            boolean valid = true;
            for (int i = 0; i < needed; i++) {
                if (!hasRemaining()) {
                    valid = false;
                    break;
                }

                long mark = position();
                int next = readUnsignedByte();
                if ((next & 0xc0) != 0x80) {
                    position(mark);
                    valid = false;
                    break;
                }

                codePoint = (codePoint << 6) | (next & 0x3f);
            }

            if (!valid || !Character.isValidCodePoint(codePoint)) {
                builder.append('�');
            } else {
                builder.appendCodePoint(codePoint);
            }
        }

        return builder.toString();
    }

    /** Reads bytes up to the next LF (or end of file), dropping CR, and decodes them as UTF-8. */
    String readLineString() throws IOException {
        byte[] bytes = new byte[64];
        int count = 0;
        while (hasRemaining()) {
            int b = readUnsignedByte();
            if (b == 10) {
                break;
            }

            if (b != 13) {
                if (count == bytes.length) {
                    byte[] larger = new byte[bytes.length * 2];
                    System.arraycopy(bytes, 0, larger, 0, count);
                    bytes = larger;
                }

                bytes[count++] = (byte) b;
            }
        }

        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    /** Reads a 4-byte ASCII magic; rewinds and returns false when it does not match. */
    boolean tryReadMagic(String expected) throws IOException {
        if (length < 4) {
            return false;
        }

        long start = position();
        byte[] magic = readBytes(4);
        if (new String(magic, StandardCharsets.US_ASCII).equals(expected)) {
            return true;
        }

        position(start);
        return false;
    }

    private void require(int count) throws IOException {
        if (buffer.remaining() >= count) {
            return;
        }

        fill(count);
        if (buffer.remaining() < count) {
            throw new EOFException("Unable to read beyond the end of the stream.");
        }
    }

    private void fill(int count) throws IOException {
        bufferStart += buffer.position();
        buffer.compact();
        while (buffer.position() < count) {
            int read = channel.read(buffer, bufferStart + buffer.position());
            if (read < 0) {
                break;
            }
        }

        buffer.flip();
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
