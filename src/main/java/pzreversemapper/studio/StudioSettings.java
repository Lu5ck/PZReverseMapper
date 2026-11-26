package pzreversemapper.studio;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

/**
 * Paths Studio remembers between runs (the game media folder and the BuildingEd config folder),
 * stored in a small properties file in the user's configuration folder, so they survive replacing
 * the jar with a newer version:
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\PZReverseMapper\studio.properties}</li>
 *   <li>macOS: {@code ~/Library/Application Support/PZReverseMapper/studio.properties}</li>
 *   <li>Linux and others: {@code $XDG_CONFIG_HOME/PZReverseMapper/studio.properties}, by default under {@code ~/.config}</li>
 * </ul>
 *
 * The system property {@code pzreversemapper.settings} names another file.
 */
final class StudioSettings {
    static final String TILES_PATH = "tilesPath";
    static final String BUILDINGED_CONFIG_PATH = "buildingEdConfigPath";
    private static final String FOLDER = "PZReverseMapper";
    private static final String FILE = "studio.properties";

    private final Path file;
    private final Properties values = new Properties();

    StudioSettings(Path file) {
        this.file = file;
    }

    /** Settings at the default location for this user and platform. */
    static StudioSettings forCurrentUser() {
        return new StudioSettings(defaultFile());
    }

    static Path defaultFile() {
        String override = System.getProperty("pzreversemapper.settings");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }

        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData != null && !appData.isBlank() ? Paths.get(appData) : Paths.get(home, "AppData", "Roaming");
        } else if (os.contains("mac")) {
            base = Paths.get(home, "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            base = xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(home, ".config");
        }

        return base.resolve(FOLDER).resolve(FILE);
    }

    Path file() {
        return file;
    }

    /** Reads the file; a missing or unreadable file leaves the settings empty. */
    StudioSettings load() {
        values.clear();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException | IllegalArgumentException ex) {
                values.clear();
            }
        }

        return this;
    }

    /** The saved value, or an empty string. */
    String get(String key) {
        return values.getProperty(key, "");
    }

    /** Sets a value; a blank value removes it. Returns whether anything changed. */
    boolean set(String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        String previous = values.getProperty(key, "");
        if (previous.equals(trimmed)) {
            return false;
        }

        if (trimmed.isEmpty()) {
            values.remove(key);
        } else {
            values.setProperty(key, trimmed);
        }

        return true;
    }

    void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            values.store(writer, "PZ Reverse Mapper Studio: remembered paths");
        }
    }
}
