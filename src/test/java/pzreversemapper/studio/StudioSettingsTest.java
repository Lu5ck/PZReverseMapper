package pzreversemapper.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudioSettingsTest {
    @TempDir
    Path temp;

    @Test
    void pathsSurviveARestart() throws Exception {
        Path file = temp.resolve("config/PZReverseMapper/studio.properties");
        StudioSettings settings = new StudioSettings(file).load();
        assertEquals("", settings.get(StudioSettings.TILES_PATH), "nothing remembered yet");

        assertTrue(settings.set(StudioSettings.TILES_PATH, "  C:\\Games\\ProjectZomboid\\media  "));
        assertTrue(settings.set(StudioSettings.BUILDINGED_CONFIG_PATH, "/home/me/PZ_Mapping_Tools é"));
        assertFalse(settings.set(StudioSettings.TILES_PATH, "C:\\Games\\ProjectZomboid\\media"), "unchanged");
        settings.save();

        StudioSettings reloaded = new StudioSettings(file).load();
        assertEquals("C:\\Games\\ProjectZomboid\\media", reloaded.get(StudioSettings.TILES_PATH));
        assertEquals("/home/me/PZ_Mapping_Tools é", reloaded.get(StudioSettings.BUILDINGED_CONFIG_PATH));

        assertTrue(reloaded.set(StudioSettings.TILES_PATH, " "), "clearing a field forgets it");
        reloaded.save();
        assertEquals("", new StudioSettings(file).load().get(StudioSettings.TILES_PATH));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("buildingEdConfigPath"));
    }

    @Test
    void unreadableOrMissingFilesAreEmpty() throws Exception {
        assertEquals("", new StudioSettings(temp.resolve("missing.properties")).load().get(StudioSettings.TILES_PATH));
        Path bad = Files.writeString(temp.resolve("bad.properties"), "tilesPath=\\uZZZZ\n");
        assertEquals("", new StudioSettings(bad).load().get(StudioSettings.TILES_PATH));
    }

    @Test
    void defaultFileCanBeOverridden() {
        String previous = System.getProperty("pzreversemapper.settings");
        try {
            System.setProperty("pzreversemapper.settings", temp.resolve("x.properties").toString());
            assertEquals(temp.resolve("x.properties"), StudioSettings.defaultFile());
            System.clearProperty("pzreversemapper.settings");
            assertTrue(StudioSettings.defaultFile().endsWith(Path.of("PZReverseMapper", "studio.properties")));
        } finally {
            if (previous == null) {
                System.clearProperty("pzreversemapper.settings");
            } else {
                System.setProperty("pzreversemapper.settings", previous);
            }
        }
    }
}
