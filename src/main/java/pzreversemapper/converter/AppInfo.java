package pzreversemapper.converter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Application identity: version, install folder and the command used to launch the jar. */
public final class AppInfo {
    public static final String APP_NAME = "PZ Reverse Mapper";
    private static final String DEFAULT_JAR_NAME = "PZReverseMapper.jar";
    private static final String VERSION = loadVersion();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    /** Folder containing the running jar (or the classes folder when run from a build tree). */
    public static Path applicationDirectory() {
        Path location = codeSourceLocation();
        if (location == null) {
            return Paths.get("").toAbsolutePath();
        }

        if (Files.isRegularFile(location)) {
            Path parent = location.getParent();
            return parent == null ? Paths.get("").toAbsolutePath() : parent;
        }

        return location;
    }

    /** File name of the running jar, or a default name when not running from a jar. */
    public static String jarFileName() {
        Path location = codeSourceLocation();
        if (location != null && Files.isRegularFile(location)) {
            Path name = location.getFileName();
            if (name != null && Text.endsWithIgnoreCase(name.toString(), ".jar")) {
                return name.toString();
            }
        }

        return DEFAULT_JAR_NAME;
    }

    /** Command prefix that starts the command-line converter, for usage text and previews. */
    public static String cliCommand() {
        String jar = jarFileName();
        return "java -jar " + (jar.indexOf(' ') >= 0 ? "\"" + jar + "\"" : jar);
    }

    private static Path codeSourceLocation() {
        try {
            var source = AppInfo.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }

            return Paths.get(source.getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }

    private static String loadVersion() {
        try (InputStream stream = AppInfo.class.getResourceAsStream("/pzreversemapper/version.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version");
                if (!Text.isNullOrWhiteSpace(version) && !version.contains("${")) {
                    return version.trim();
                }
            }
        } catch (IOException ex) {
            // Fall back to the manifest or the default below.
        }

        String manifestVersion = AppInfo.class.getPackage().getImplementationVersion();
        return Text.isNullOrWhiteSpace(manifestVersion) ? "1.0.0" : manifestVersion;
    }
}
