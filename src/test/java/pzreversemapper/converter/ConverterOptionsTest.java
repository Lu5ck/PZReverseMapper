package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConverterOptionsTest {
    @Test
    void parsesFullCommandLine() {
        ConverterOptions options = ConverterOptions.parse(new String[] {
            "--INPUT", "in", "-o", "out", "--tiles", "media", "--mod-media", "mods", "--name", "Kentucky",
            "--source-cell-size", "256", "--target-cell-size", "300", "--cells", "46_26, 46_27;-1_3", "--clean",
            "--no-images", "--extract-tiles", "--no-objects", "--no-room-tbx", "--no-building-tbx"});

        assertTrue(options.inputDirectory().endsWith("in"));
        assertTrue(options.outputDirectory().endsWith("out"));
        assertEquals("Kentucky", options.projectName());
        assertEquals(256, options.expectedSourceCellSize());
        assertEquals(300, options.targetCellSize());
        assertNull(ConverterOptions.parse(new String[] {"-i", "a", "-o", "b"}).targetCellSize(), "auto by default");
        assertNull(ConverterOptions.parse(new String[] {"-i", "a", "-o", "b", "--target-cell-size", "AUTO"}).targetCellSize());
        assertEquals(Set.of(new CellCoord(46, 26), new CellCoord(46, 27), new CellCoord(-1, 3)), options.includeCells());
        assertTrue(options.cleanOutput());
        assertFalse(options.exportImages());
        assertTrue(options.exportTilePacks());
        assertFalse(options.exportObjects());
        assertFalse(options.exportRoomTbx());
        assertFalse(options.exportBuildingTbx());
        assertEquals(2, options.enumerateTilesAssetPaths().size());
    }

    @Test
    void tilesOnlyDoesNotNeedInput() {
        ConverterOptions options = ConverterOptions.parse(new String[] {"--output", "out", "--tiles", "media", "--tiles-only"});
        assertTrue(options.tilesOnly());
        assertTrue(options.exportTilePacks());
        assertFalse(options.exportImages());
        assertEquals("", options.inputDirectory());
    }

    @Test
    void missingRequiredArgumentsReturnNull() {
        assertNull(ConverterOptions.parse(new String[0]));
        assertNull(ConverterOptions.parse(new String[] {"--output", "out"}));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ConverterOptions.parse(new String[] {"--bogus"}));
        assertThrows(IllegalArgumentException.class, () -> ConverterOptions.parse(new String[] {"--input"}));
        assertThrows(IllegalArgumentException.class,
            () -> ConverterOptions.parse(new String[] {"-i", "a", "-o", "b", "--target-cell-size", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ConverterOptions.parse(new String[] {"-i", "a", "-o", "b", "--cells", "1-2"}));
        assertThrows(IllegalArgumentException.class, () -> ConverterOptions.parse(new String[] {"-i", "a", "-o", "b", "--cells", " , "}));
        assertThrows(IllegalArgumentException.class, () -> ConverterOptions.parse(new String[] {"-o", "b", "--tiles-only"}));
    }

    @Test
    void progressPercentRoundsHalfToEven() {
        assertEquals(50, new ConversionProgress("s", "m", 1, 2).percent());
        assertEquals(12, new ConversionProgress("s", "m", 1, 8).percent());
        assertEquals(38, new ConversionProgress("s", "m", 3, 8).percent());
        assertNull(new ConversionProgress("s", "m", null, 8).percent());
    }
}
