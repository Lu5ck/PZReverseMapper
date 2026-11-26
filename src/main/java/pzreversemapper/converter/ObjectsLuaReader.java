package pzreversemapper.converter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight parser for the {@code objects.lua} table written by WorldEd. */
final class ObjectsLuaReader {
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private ObjectsLuaReader() {
    }

    static List<MapObject> read(Path file) throws IOException {
        String text = readAllText(file);
        List<MapObject> objects = new ArrayList<>();
        for (String block : extractObjectBlocks(text)) {
            MapObject mapObject = parseObject(block);
            if (mapObject != null) {
                objects.add(mapObject);
            }
        }

        return objects;
    }

    /** Reads text like {@code File.ReadAllText}: honours a UTF-8/UTF-16 BOM, defaults to UTF-8. */
    static String readAllText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
            offset = 3;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }

        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static List<String> extractObjectBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int objectsIndex = text.indexOf("objects");
        int rootStart = text.indexOf('{', Math.max(objectsIndex, 0));
        if (rootStart < 0) {
            return blocks;
        }

        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        char quote = '\0';
        boolean escaped = false;

        for (int i = rootStart; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    inString = false;
                }

                continue;
            }

            if (current == '"' || current == '\'') {
                inString = true;
                quote = current;
                continue;
            }

            if (current == '{') {
                depth++;
                if (depth == 2) {
                    objectStart = i;
                }
            } else if (current == '}') {
                if (depth == 2 && objectStart >= 0) {
                    blocks.add(text.substring(objectStart, i + 1));
                    objectStart = -1;
                }

                depth--;
                if (depth <= 0) {
                    break;
                }
            }
        }

        return blocks;
    }

    private static MapObject parseObject(String block) {
        String type = readString(block, "type");
        if (Text.isNullOrWhiteSpace(type)) {
            return null;
        }

        String propertiesTable = extractTable(block, "properties");
        String pointsTable = extractTable(block, "points");
        String name = readString(block, "name");

        return new MapObject(
            name == null ? "" : name,
            type,
            readInt(block, "x"),
            readInt(block, "y"),
            readInt(block, "z"),
            readInt(block, "width"),
            readInt(block, "height"),
            readString(block, "geometry"),
            readInt(block, "lineWidth"),
            parsePoints(pointsTable),
            parseProperties(propertiesTable));
    }

    private static String readString(String block, String key) {
        Matcher match = keyPattern(key, "\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(block);
        return match.find() ? unescapeLuaString(match.group(1)) : null;
    }

    private static Integer readInt(String block, String key) {
        Matcher match = keyPattern(key, "\\s*=\\s*(-?\\d+)").matcher(block);
        return match.find() ? Text.tryParseInt(match.group(1)) : null;
    }

    private static String extractTable(String block, String key) {
        Matcher match = keyPattern(key, "\\s*=\\s*\\{").matcher(block);
        if (!match.find()) {
            return null;
        }

        int start = match.end() - 1;
        int depth = 0;
        boolean inString = false;
        char quote = '\0';
        boolean escaped = false;

        for (int i = start; i < block.length(); i++) {
            char current = block.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    inString = false;
                }

                continue;
            }

            if (current == '"' || current == '\'') {
                inString = true;
                quote = current;
                continue;
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return block.substring(start, i + 1);
                }
            }
        }

        return null;
    }

    private static Pattern keyPattern(String key, String suffix) {
        return PATTERNS.computeIfAbsent(key + '\u0000' + suffix,
            ignored -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(key) + suffix, Pattern.DOTALL));
    }

    private static List<ObjectPoint> parsePoints(String table) {
        if (Text.isNullOrWhiteSpace(table)) {
            return List.of();
        }

        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = INTEGER.matcher(table);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }

        List<ObjectPoint> points = new ArrayList<>(numbers.size() / 2);
        for (int i = 0; i + 1 < numbers.size(); i += 2) {
            points.add(new ObjectPoint(numbers.get(i), numbers.get(i + 1)));
        }

        return List.copyOf(points);
    }

    private static Map<String, String> parseProperties(String table) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (Text.isNullOrWhiteSpace(table) || table.length() < 2) {
            return properties;
        }

        String inner = table.substring(1, table.length() - 1);
        int[] index = {0};
        while (index[0] < inner.length()) {
            skipSeparators(inner, index);
            if (index[0] >= inner.length()) {
                break;
            }

            String key = readIdentifier(inner, index);
            if (key.isEmpty()) {
                index[0]++;
                continue;
            }

            skipWhitespace(inner, index);
            if (index[0] >= inner.length() || inner.charAt(index[0]) != '=') {
                index[0]++;
                continue;
            }

            index[0]++;
            skipWhitespace(inner, index);
            properties.put(key, readValue(inner, index));
        }

        return properties;
    }

    private static void skipSeparators(String text, int[] index) {
        while (index[0] < text.length() && (Text.isWhiteSpace(text.charAt(index[0])) || text.charAt(index[0]) == ',')) {
            index[0]++;
        }
    }

    private static void skipWhitespace(String text, int[] index) {
        while (index[0] < text.length() && Text.isWhiteSpace(text.charAt(index[0]))) {
            index[0]++;
        }
    }

    private static String readIdentifier(String text, int[] index) {
        if (index[0] >= text.length() || !(Character.isLetter(text.charAt(index[0])) || text.charAt(index[0]) == '_')) {
            return "";
        }

        int start = index[0];
        index[0]++;
        while (index[0] < text.length() && (Character.isLetterOrDigit(text.charAt(index[0])) || text.charAt(index[0]) == '_')) {
            index[0]++;
        }

        return text.substring(start, index[0]);
    }

    private static String readValue(String text, int[] index) {
        if (index[0] >= text.length()) {
            return "";
        }

        char first = text.charAt(index[0]);
        if (first == '"' || first == '\'') {
            return readQuotedString(text, index);
        }

        if (first == '{') {
            return readBalancedTable(text, index);
        }

        int start = index[0];
        while (index[0] < text.length() && text.charAt(index[0]) != ',') {
            index[0]++;
        }

        return Text.trim(text.substring(start, index[0]));
    }

    private static String readQuotedString(String text, int[] index) {
        char quote = text.charAt(index[0]++);
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;

        while (index[0] < text.length()) {
            char current = text.charAt(index[0]++);
            if (escaped) {
                builder.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> current;
                });
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                break;
            } else {
                builder.append(current);
            }
        }

        return builder.toString();
    }

    private static String readBalancedTable(String text, int[] index) {
        int start = index[0];
        int depth = 0;
        boolean inString = false;
        char quote = '\0';
        boolean escaped = false;

        while (index[0] < text.length()) {
            char current = text.charAt(index[0]++);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    inString = false;
                }

                continue;
            }

            if (current == '"' || current == '\'') {
                inString = true;
                quote = current;
                continue;
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }

        return Text.trim(text.substring(start, index[0]));
    }

    private static String unescapeLuaString(String value) {
        int[] index = {0};
        return readQuotedString("\"" + value + "\"", index);
    }
}
