package pzreversemapper.converter;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guarded "clean output" support. Existing output entries are moved to the Recycle Bin / Trash,
 * never deleted permanently, and well-known personal folders and drive roots are refused.
 */
public final class OutputCleaner {
    private static final String[] PERSONAL_FOLDERS = {"Desktop", "Documents", "Pictures", "Music", "Videos", "Downloads"};

    private OutputCleaner() {
    }

    /** "Recycle Bin" on Windows, "Trash" elsewhere, for user-facing messages. */
    public static String trashName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "Recycle Bin" : "Trash";
    }

    public static boolean hasContent(String directory) throws IOException {
        Path path = Paths.get(directory);
        if (!Files.isDirectory(path)) {
            return false;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            return entries.iterator().hasNext();
        }
    }

    /** Moves every entry of the output folder to the trash and returns how many were moved. */
    public static int cleanToRecycleBin(String outputDirectory) throws IOException {
        ensureSafeCleanTarget(outputDirectory);
        Path directory = Paths.get(outputDirectory);
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(entries::add);
        }

        for (Path entry : entries) {
            moveEntryToRecycleBin(entry);
        }

        return entries.size();
    }

    /** Throws {@link IllegalStateException} when the folder is a drive root or a protected personal folder. */
    public static void ensureSafeCleanTarget(String outputDirectory) {
        Path fullPath = Paths.get(outputDirectory).toAbsolutePath().normalize();
        Path root = fullPath.getRoot();
        if (root != null && Text.equalsOrdinalIgnoreCase(Text.trimPath(fullPath.toString()), Text.trimPath(root.toString()))) {
            throw new IllegalStateException("Refusing to clean drive root: " + fullPath);
        }

        for (Path protectedPath : enumerateProtectedPaths()) {
            if (Text.equalsOrdinalIgnoreCase(Text.trimPath(fullPath.toString()), Text.trimPath(protectedPath.toString()))) {
                throw new IllegalStateException(
                    "Refusing to clean protected folder: " + fullPath + ". Select or create a dedicated export folder instead.");
            }
        }
    }

    private static void moveEntryToRecycleBin(Path entry) throws IOException {
        if (moveToTrashWithDesktop(entry)) {
            return;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            moveToMacTrash(entry);
            return;
        }

        if (os.contains("win")) {
            throw new IOException("The Recycle Bin is not available in this session, so " + entry
                + " was not removed. Clean the output folder manually or disable the clean option.");
        }

        moveToFreedesktopTrash(entry);
    }

    private static boolean moveToTrashWithDesktop(Path entry) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }

        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }

            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                return false;
            }

            if (!desktop.moveToTrash(entry.toFile())) {
                throw new IOException("Could not move " + entry + " to the Recycle Bin. Nothing was deleted.");
            }

            return true;
        } catch (UnsupportedOperationException | SecurityException ex) {
            return false;
        }
    }

    private static void moveToMacTrash(Path entry) throws IOException {
        Path trash = Paths.get(System.getProperty("user.home"), ".Trash");
        Files.createDirectories(trash);
        Path target = uniqueName(trash, entry.getFileName().toString());
        Files.move(entry, target);
    }

    /** Implements the freedesktop.org trash specification (home trash, then the volume's .Trash-uid). */
    private static void moveToFreedesktopTrash(Path entry) throws IOException {
        Path absolute = entry.toAbsolutePath().normalize();
        Path homeTrash = homeTrashDirectory();
        try {
            if (sameFileStore(absolute, homeTrash)) {
                moveIntoTrash(absolute, homeTrash, absolute.toString());
                return;
            }

            Path topDirectory = volumeTopDirectory(absolute);
            if (topDirectory == null) {
                throw new IOException("the volume of the output folder could not be determined");
            }

            Path volumeTrash = topDirectory.resolve(".Trash-" + currentUserId());
            moveIntoTrash(absolute, volumeTrash, topDirectory.relativize(absolute).toString());
        } catch (IOException ex) {
            throw new IOException("Could not move " + entry + " to the Trash (" + Text.messageOf(ex)
                + "). Nothing was deleted; clean the output folder manually or disable the clean option.", ex);
        }
    }

    private static void moveIntoTrash(Path entry, Path trashDirectory, String originalPath) throws IOException {
        Path files = trashDirectory.resolve("files");
        Path info = trashDirectory.resolve("info");
        Files.createDirectories(files);
        Files.createDirectories(info);

        String baseName = entry.getFileName().toString();
        for (int attempt = 1; ; attempt++) {
            String name = attempt == 1 ? baseName : baseName + "." + attempt;
            Path infoFile = info.resolve(name + ".trashinfo");
            Path target = files.resolve(name);
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            String content = "[Trash Info]\nPath=" + encodeTrashPath(originalPath) + "\nDeletionDate="
                + LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n";
            try {
                Files.write(infoFile, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                continue;
            }

            try {
                Files.move(entry, target);
                return;
            } catch (IOException ex) {
                Files.deleteIfExists(infoFile);
                throw ex;
            }
        }
    }

    private static Path uniqueName(Path directory, String baseName) {
        for (int attempt = 1; ; attempt++) {
            Path candidate = directory.resolve(attempt == 1 ? baseName : baseName + " " + attempt);
            if (!Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }

    private static Path homeTrashDirectory() {
        String dataHome = System.getenv("XDG_DATA_HOME");
        Path base = Text.isNullOrWhiteSpace(dataHome)
            ? Paths.get(System.getProperty("user.home"), ".local", "share")
            : Paths.get(dataHome);
        return base.resolve("Trash");
    }

    private static boolean sameFileStore(Path a, Path b) throws IOException {
        Path existingB = b;
        while (existingB != null && !Files.exists(existingB)) {
            existingB = existingB.getParent();
        }

        if (existingB == null) {
            return false;
        }

        FileStore storeA = Files.getFileStore(a);
        FileStore storeB = Files.getFileStore(existingB);
        return storeA.equals(storeB);
    }

    private static Path volumeTopDirectory(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            Path current = path.getParent();
            Path top = current;
            while (current != null && Files.getFileStore(current).equals(store)) {
                top = current;
                current = current.getParent();
            }

            return top;
        } catch (IOException ex) {
            return null;
        }
    }

    private static String currentUserId() {
        try {
            Object uid = Files.getAttribute(Paths.get(System.getProperty("user.home")), "unix:uid");
            return String.valueOf(uid);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ex) {
            return System.getProperty("user.name", "user");
        }
    }

    private static String encodeTrashPath(String path) {
        StringBuilder builder = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '-' || c == '_' || c == '.' || c == '~') {
                builder.append((char) c);
            } else {
                builder.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }

        return builder.toString();
    }

    private static Set<Path> enumerateProtectedPaths() {
        Set<Path> paths = new LinkedHashSet<>();
        String home = System.getProperty("user.home");
        if (!Text.isNullOrWhiteSpace(home)) {
            Path homePath = Paths.get(home).toAbsolutePath().normalize();
            paths.add(homePath);
            for (String folder : PERSONAL_FOLDERS) {
                paths.add(homePath.resolve(folder));
            }

            addXdgUserDirectories(homePath, paths);
        }

        addSystemDocumentsFolder(paths);
        return paths;
    }

    /** Adds localized desktop/documents folders declared in {@code ~/.config/user-dirs.dirs}. */
    private static void addXdgUserDirectories(Path home, Set<Path> paths) {
        Path config = home.resolve(".config").resolve("user-dirs.dirs");
        if (!Files.isRegularFile(config)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (!trimmed.startsWith("XDG_") || equals < 0) {
                    continue;
                }

                String value = trimmed.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                value = value.replace("$HOME", home.toString());
                if (!value.isEmpty()) {
                    paths.add(Paths.get(value).toAbsolutePath().normalize());
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Optional hint only.
        }
    }

    /** Adds the platform "Documents" folder (handles redirected Windows libraries). */
    private static void addSystemDocumentsFolder(Set<Path> paths) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            java.io.File documents = javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory();
            if (documents != null) {
                paths.add(documents.toPath().toAbsolutePath().normalize());
            }
        } catch (RuntimeException ex) {
            // Optional hint only.
        }
    }
}
