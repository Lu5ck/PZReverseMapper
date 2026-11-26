package pzreversemapper.converter;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes text files the same way the original Windows build did: UTF-8 with a byte order mark and
 * CRLF line endings, independent of the platform the port runs on.
 */
final class TextFileWriter implements Closeable {
    static final String NEW_LINE = "\r\n";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final Writer writer;

    /** Writes to any writer, without a byte order mark (used to build file content in memory). */
    TextFileWriter(Writer writer) {
        this.writer = writer;
    }

    /** Writes already-formatted text to a file with the UTF-8 byte order mark. */
    static void writeFile(Path file, String text) throws IOException {
        try (TextFileWriter out = new TextFileWriter(file)) {
            out.write(text);
        }
    }

    TextFileWriter(Path file) throws IOException {
        OutputStream stream = Files.newOutputStream(file);
        try {
            stream.write(UTF8_BOM);
            writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 1 << 16);
        } catch (IOException | RuntimeException ex) {
            stream.close();
            throw ex;
        }
    }

    void write(String text) throws IOException {
        writer.write(text);
    }

    void write(char c) throws IOException {
        writer.write(c);
    }

    void write(int value) throws IOException {
        writer.write(Integer.toString(value));
    }

    void newLine() throws IOException {
        writer.write(NEW_LINE);
    }

    void writeLine(String text) throws IOException {
        writer.write(text);
        writer.write(NEW_LINE);
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
