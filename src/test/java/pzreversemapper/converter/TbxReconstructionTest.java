package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pzreversemapper.converter.TbxModel.Building;
import pzreversemapper.converter.TbxModel.BuildingObject;
import pzreversemapper.converter.TbxModel.Door;
import pzreversemapper.converter.TbxModel.Floor;
import pzreversemapper.converter.TbxModel.Furniture;
import pzreversemapper.converter.TbxModel.Stairs;
import pzreversemapper.converter.TbxModel.Window;

class TbxReconstructionTest {
    private static final int ORIGIN_X = 100;
    private static final int ORIGIN_Y = 40;

    @TempDir
    Path temp;

    private BuildingEdConfig config;
    private Building original;
    private List<String>[][][] compiled;

    @BeforeEach
    void setUp() throws Exception {
        config = BuildingEdConfig.load(TbxFixture.writeConfig(temp.resolve("nested/config")).getParent().getParent().toString());
        original = TbxFixture.sampleBuilding(config);
        compiled = TbxFixture.compiledStacks(original, config);
    }

    private TbxReconstructor.Result rebuild() {
        TbxReconstructor.StackSource source = (worldX, worldY, level) -> {
            int x = worldX - ORIGIN_X;
            int y = worldY - ORIGIN_Y;
            if (level < 0 || level >= compiled.length || x < 0 || y < 0 || x > original.width || y > original.height) {
                return List.of();
            }

            return compiled[level][x][y];
        };
        return new TbxReconstructor(config).reconstruct(TbxFixture.roomRects(original, ORIGIN_X, ORIGIN_Y), source, 4);
    }

    @Test
    void configIsFoundInSubfoldersAndParsed() {
        assertTrue(config.entryCount() >= 15);
        assertEquals(3, config.furnitureCount());
        assertEquals("Floor", config.layers().get(0));
        assertEquals(2, config.entries("exterior_walls").size());
        assertEquals("walls_exterior_house_01_10", config.entries("exterior_walls").get(0).tile("WestDoor"), "padded ids are normalised");
        assertThrows(FileNotFoundException.class, () -> BuildingEdConfig.load(temp.resolve("missing").toString()));
    }

    @Test
    void rebuildsWallsFloorsObjectsAndFurniture() {
        TbxReconstructor.Result result = rebuild();
        Building rebuilt = result.building();
        TbxReconstructor.Stats stats = result.stats();

        assertEquals(0, stats.unmatched, "every compiled building tile is reproduced");
        assertEquals(0, stats.extra, "nothing is rendered that was not compiled");
        assertEquals(1, stats.userTiles, "only the hand-drawn decoration stays a user tile");
        assertTrue(stats.mapTiles > 0, "map ground under exterior squares is left to the map");
        assertEquals(ORIGIN_X, result.originX());
        assertEquals(ORIGIN_Y, result.originY());
        assertEquals(8, rebuilt.width);
        assertEquals(6, rebuilt.height);
        assertEquals(2, rebuilt.floors.size());

        assertSame(original.tile("ExteriorWall"), rebuilt.tile("ExteriorWall"));
        assertSame(original.tile("GrimeWall"), rebuilt.tile("GrimeWall"));
        assertEquals(List.of("kitchen", "bedroom", "bedroom"), rebuilt.rooms.stream().map(room -> room.internalName).collect(Collectors.toList()));
        for (String slot : new String[] {"InteriorWall", "InteriorWallTrim", "Floor", "GrimeFloor", "GrimeWall"}) {
            for (int r = 0; r < 3; r++) {
                assertSame(original.rooms.get(r).tile(slot), rebuilt.rooms.get(r).tile(slot), "room " + r + " " + slot);
            }
        }

        assertSame(original.rooms.get(1).tile("Ceiling"), rebuilt.rooms.get(1).tile("Ceiling"), "visible bedroom ceiling");
        assertSame(config.entries("doors").get(0), rebuilt.tile("Door"));
        assertSame(config.entries("windows").get(0), rebuilt.tile("Window"));

        assertEquals(sorted(original.floors.get(0).objects), sorted(rebuilt.floors.get(0).objects));
        assertEquals(sorted(original.floors.get(1).objects), sorted(rebuilt.floors.get(1).objects));
        assertEquals(2, stats.doors);
        assertEquals(2, stats.windows);
        assertEquals(1, stats.stairs);
        assertEquals(4, stats.furniture);

        assertEquals(TbxFixture.DECOR, rebuilt.floors.get(0).userTile("FloorOverlay", 1, 1));
        assertRendersLike(original, rebuilt);
    }

    @Test
    void writtenTbxReadsBackAndRendersTheSameTiles() throws Exception {
        Building rebuilt = rebuild().building();
        Path file = temp.resolve("building.tbx");
        TbxXmlWriter.write(rebuilt, file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("<building version=\"4\" width=\"8\" height=\"6\" ExteriorWall=\""));
        assertTrue(text.contains("<tile enum=\"WestDoor\" tile=\"walls_exterior_house_01_010\"/>"), "BuildingEd-style padded tile names");
        assertTrue(text.contains("<object type=\"door\" FrameTile=\""));
        assertTrue(text.contains("<furniture layer=\"WallFurniture\">"));
        assertTrue(text.contains("<tiles layer=\"FloorOverlay\">"));
        MapConverterTest.parse(file);

        Building reread = TbxReader.read(file);
        assertEquals(sorted(rebuilt.floors.get(0).objects), sorted(reread.floors.get(0).objects));
        assertRendersLike(original, reread);
    }

    @Test
    void converterRebuildsBuildingsFromCompiledMaps() throws Exception {
        Path map = Files.createDirectories(temp.resolve("map"));
        List<RoomRect> local = TbxFixture.roomRects(original, ORIGIN_X, ORIGIN_Y);
        SyntheticMap.writeCompiledCell(map, 0, 0, 0, 1, (x, y, level) -> {
            int bx = x - ORIGIN_X;
            int by = y - ORIGIN_Y;
            if (bx >= 0 && by >= 0 && bx <= original.width && by <= original.height) {
                return compiled[level][bx][by];
            }

            return level == 0 ? List.of(TbxFixture.GROUND) : List.of();
        }, local);

        Path output = temp.resolve("out");
        ConverterOptions options = ConverterOptions.parse(new String[] {
            "-i", map.toString(), "-o", output.toString(), "--tbx-only", "--tbx-config", temp.resolve("nested").toString()});
        ConversionResult result = new MapConverter(options).run();
        assertEquals(1, result.buildingTbxCount());

        List<Path> files;
        try (Stream<Path> stream = Files.list(output.resolve("tbx_buildings/0_0"))) {
            files = stream.collect(Collectors.toList());
        }

        assertEquals(1, files.size());
        assertEquals("0_0_b0000_kitchen_100_40.tbx", files.get(0).getFileName().toString());
        Building reread = TbxReader.read(files.get(0));
        assertEquals("100", reread.properties.get("WorldX"));
        assertEquals("0", reread.properties.get("WorldZ"));
        assertRendersLike(original, reread);
    }

    @Test
    void withoutConfigTheOriginalTileDumpIsWritten() throws Exception {
        Path map = Files.createDirectories(temp.resolve("map"));
        SyntheticMap.writeCompiledCell(map, 0, 0, 0, 1, (x, y, level) -> {
            int bx = x - ORIGIN_X;
            int by = y - ORIGIN_Y;
            return bx >= 0 && by >= 0 && bx <= original.width && by <= original.height ? compiled[level][bx][by] : List.of();
        }, TbxFixture.roomRects(original, ORIGIN_X, ORIGIN_Y));
        Path output = temp.resolve("out");
        new MapConverter(ConverterOptions.parse(new String[] {"-i", map.toString(), "-o", output.toString(), "--tbx-only"})).run();
        try (Stream<Path> stream = Files.list(output.resolve("tbx_buildings/0_0"))) {
            Path file = stream.findFirst().orElseThrow();
            assertEquals("0_0_b0000_kitchen_99_39.tbx", file.getFileName().toString(), "legacy name keeps the 1-square margin");
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("ExteriorWall=\"0\""));
        }
    }

    private void assertRendersLike(Building expected, Building actual) {
        TbxLayout layout = new TbxLayout(config);
        for (int f = 0; f < expected.floors.size(); f++) {
            TbxLayout.RenderedFloor a = layout.render(expected, expected.floors.get(f));
            TbxLayout.RenderedFloor b = layout.render(actual, actual.floors.get(f));
            for (int x = 0; x <= expected.width; x++) {
                for (int y = 0; y <= expected.height; y++) {
                    assertEquals(names(a, x, y), names(b, x, y), "floor " + f + " square " + x + "," + y);
                }
            }
        }
    }

    private static List<String> names(TbxLayout.RenderedFloor rendered, int x, int y) {
        List<String> names = new ArrayList<>();
        for (Object[] tile : rendered.stack(x, y)) {
            names.add(tile[1].toString());
        }

        return names;
    }

    private static List<String> sorted(List<BuildingObject> objects) {
        List<String> descriptions = new ArrayList<>();
        for (BuildingObject object : objects) {
            String text;
            if (object instanceof Door door) {
                text = "door " + door.x + "," + door.y + " " + door.dir + " " + entryName(door.tile) + " " + entryName(door.frame);
            } else if (object instanceof Window window) {
                text = "window " + window.x + "," + window.y + " " + window.dir + " " + entryName(window.tile) + " "
                    + entryName(window.curtains) + " " + entryName(window.shutters);
            } else if (object instanceof Stairs stairs) {
                text = "stairs " + stairs.x + "," + stairs.y + " " + stairs.dir + " " + entryName(stairs.tile);
            } else {
                Furniture furniture = (Furniture) object;
                text = "furniture " + furniture.x + "," + furniture.y + " " + furniture.orient + " "
                    + furniture.resolved().tiles.values();
            }

            descriptions.add(text);
        }

        descriptions.sort(null);
        return descriptions;
    }

    private static String entryName(TbxModel.TileEntry entry) {
        return entry == null ? "none" : entry.category + entry.tiles.values();
    }

    @Test
    void sampleUsesEveryFeature() {
        Floor ground = original.floors.get(0);
        assertNotNull(ground.objects.stream().filter(Window.class::isInstance).findAny().orElse(null));
        assertTrue(compiled[0][6][3].contains("fixtures_windows_detailing_01_18"), "exterior window shutters");
        assertTrue(compiled[0][6][2].contains("fixtures_windows_curtains_01_35"), "exterior window curtains inside the room");
        assertTrue(compiled[1][2][4].isEmpty() || !compiled[1][2][4].get(0).startsWith("floors"), "stairs remove the floor above");
    }
}
