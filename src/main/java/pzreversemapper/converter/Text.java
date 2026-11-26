package pzreversemapper.converter;

import java.util.Comparator;

/**
 * String helpers that reproduce the .NET semantics the original converter relied on
 * (whitespace rules, ordinal case-insensitive comparisons, invalid file-name characters),
 * so that the Java port produces the same names and the same processing order.
 */
public final class Text {
    /** Characters returned by {@code Path.GetInvalidFileNameChars()} on Windows. */
    private static final boolean[] INVALID_FILE_NAME_CHARS = buildInvalidFileNameChars();

    /** Equivalent of {@code StringComparer.OrdinalIgnoreCase} ordering. */
    public static final Comparator<String> ORDINAL_IGNORE_CASE = Text::compareOrdinalIgnoreCase;

    /**
     * Approximates the default culture-sensitive ordering .NET used for {@code OrderBy(f => f)}
     * (ICU root collation) for ASCII text: whitespace, then punctuation, then digits, then letters
     * compared case-insensitively, with lowercase before uppercase as a tie-breaker.
     */
    public static final Comparator<String> CULTURE_ORDER = Text::compareCulture;

    private Text() {
    }

    public static boolean isWhiteSpace(char c) {
        if (c == ' ' || (c >= '\t' && c <= '\r') || c == '\u0085' || c == ' ') {
            return true;
        }

        if (c < 0x1680) {
            return false;
        }

        int type = Character.getType(c);
        return type == Character.SPACE_SEPARATOR
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR;
    }

    public static boolean isNullOrWhiteSpace(String value) {
        if (value == null) {
            return true;
        }

        for (int i = 0; i < value.length(); i++) {
            if (!isWhiteSpace(value.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** Equivalent of .NET {@code string.Trim()}. */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhiteSpace(value.charAt(start))) {
            start++;
        }

        while (end > start && isWhiteSpace(value.charAt(end - 1))) {
            end--;
        }

        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    /** Equivalent of .NET {@code string.TrimEnd(chars)}. */
    public static String trimEnd(String value, char... chars) {
        int end = value.length();
        outer:
        while (end > 0) {
            char c = value.charAt(end - 1);
            for (char candidate : chars) {
                if (c == candidate) {
                    end--;
                    continue outer;
                }
            }

            break;
        }

        return value.substring(0, end);
    }

    /** Removes trailing directory separators, like the original {@code TrimPath} helpers. */
    public static String trimPath(String path) {
        return trimEnd(path, '\\', '/');
    }

    /** Replaces every Windows-invalid file-name character with an underscore. */
    public static String sanitizeFileName(String value) {
        StringBuilder builder = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < INVALID_FILE_NAME_CHARS.length && INVALID_FILE_NAME_CHARS[c]) {
                if (builder == null) {
                    builder = new StringBuilder(value);
                }

                builder.setCharAt(i, '_');
            }
        }

        return builder == null ? value : builder.toString();
    }

    public static String xmlEscape(String value) {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    public static int compareOrdinalIgnoreCase(String a, String b) {
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            char ca = a.charAt(i);
            char cb = b.charAt(i);
            if (ca != cb) {
                char ua = Character.toUpperCase(ca);
                char ub = Character.toUpperCase(cb);
                if (ua != ub) {
                    return ua - ub;
                }
            }
        }

        return a.length() - b.length();
    }

    public static boolean equalsOrdinalIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }

        return a.length() == b.length() && compareOrdinalIgnoreCase(a, b) == 0;
    }

    /** Key usable in hash maps to emulate {@code StringComparer.OrdinalIgnoreCase}. */
    public static String foldCase(String value) {
        StringBuilder builder = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char upper = Character.toUpperCase(c);
            if (upper != c) {
                if (builder == null) {
                    builder = new StringBuilder(value);
                }

                builder.setCharAt(i, upper);
            }
        }

        return builder == null ? value : builder.toString();
    }

    public static boolean containsIgnoreCase(String value, String needle) {
        int max = value.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }

        return false;
    }

    public static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static boolean endsWithIgnoreCase(String value, String suffix) {
        int offset = value.length() - suffix.length();
        return offset >= 0 && value.regionMatches(true, offset, suffix, 0, suffix.length());
    }

    /**
     * Equivalent of .NET {@code int.TryParse(string, out int)}: surrounding whitespace and a
     * leading sign are allowed, only ASCII digits are accepted, overflow fails.
     *
     * @return the parsed value, or {@code null} when the text is not a valid 32-bit integer
     */
    public static Integer tryParseInt(String value) {
        if (value == null) {
            return null;
        }

        String text = trim(value);
        if (text.isEmpty()) {
            return null;
        }

        int start = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (start == text.length()) {
            return null;
        }

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }

        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Returns the text after the last '/' or '\' separator. */
    public static String fileName(String path) {
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return index < 0 ? path : path.substring(index + 1);
    }

    /** Equivalent of {@code Path.GetFileNameWithoutExtension} using either separator style. */
    public static String fileNameWithoutExtension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Equivalent of {@code Path.GetExtension}, including the leading dot. */
    public static String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot);
    }

    public static String messageOf(Throwable error) {
        String message = error.getMessage();
        return isNullOrWhiteSpace(message) ? error.toString() : message;
    }

    private static int compareCulture(String a, String b) {
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            int pa = primaryWeight(a.charAt(i));
            int pb = primaryWeight(b.charAt(i));
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
        }

        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }

        for (int i = 0; i < length; i++) {
            char ca = a.charAt(i);
            char cb = b.charAt(i);
            if (ca != cb) {
                boolean upperA = Character.isUpperCase(ca);
                boolean upperB = Character.isUpperCase(cb);
                if (upperA != upperB) {
                    return upperA ? 1 : -1;
                }
            }
        }

        return a.compareTo(b);
    }

    private static final String PUNCTUATION_ORDER = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";

    private static int primaryWeight(char c) {
        if (isWhiteSpace(c)) {
            return 1;
        }

        int punctuation = PUNCTUATION_ORDER.indexOf(c);
        if (punctuation >= 0) {
            return 10 + punctuation;
        }

        if (c >= '0' && c <= '9') {
            return 100 + (c - '0');
        }

        if (c < 0x80 && Character.isLetter(c)) {
            return 200 + (Character.toLowerCase(c) - 'a');
        }

        if (c < 0x80) {
            return 50 + c;
        }

        return 1000 + Character.toLowerCase(c);
    }

    private static boolean[] buildInvalidFileNameChars() {
        boolean[] invalid = new boolean[128];
        for (int c = 0; c < 32; c++) {
            invalid[c] = true;
        }

        for (char c : new char[] {'"', '<', '>', '|', ':', '*', '?', '\\', '/'}) {
            invalid[c] = true;
        }

        return invalid;
    }
}
