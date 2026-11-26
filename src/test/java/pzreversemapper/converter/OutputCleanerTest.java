package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputCleanerTest {
    @TempDir
    Path temp;

    @Test
    void refusesRootsAndPersonalFolders() {
        Path root = Paths.get("").toAbsolutePath().getRoot();
        assertThrows(IllegalStateException.class, () -> OutputCleaner.ensureSafeCleanTarget(root.toString()));
        String home = System.getProperty("user.home");
        assertThrows(IllegalStateException.class, () -> OutputCleaner.ensureSafeCleanTarget(home));
        assertThrows(IllegalStateException.class, () -> OutputCleaner.ensureSafeCleanTarget(home + "/Downloads/"));
        assertThrows(IllegalStateException.class, () -> OutputCleaner.ensureSafeCleanTarget(Paths.get(home, "Desktop").toString()));
        assertDoesNotThrow(() -> OutputCleaner.ensureSafeCleanTarget(temp.resolve("export").toString()));
    }

    @Test
    void detectsContent() throws Exception {
        assertFalse(OutputCleaner.hasContent(temp.resolve("missing").toString()));
        assertFalse(OutputCleaner.hasContent(temp.toString()));
        Files.writeString(temp.resolve("file.txt"), "x");
        assertTrue(OutputCleaner.hasContent(temp.toString()));
    }
}
