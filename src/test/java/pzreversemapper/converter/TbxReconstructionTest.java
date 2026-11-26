package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /** A kitchen square of the sample building: its first compiled tile is the kitchen floor. */
    private static final int HOLE_X = 2;
    private static final int HOLE_Y = 3;

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
        assertEquals(5, config.furnitureCount());
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
    void furnitureDefinedInSeveralLayersKeepsTheCompiledStackingOrder() {
        // A shelf drawn after the bed on the same square: only the Furniture-layer shelf stacks like
        // that (the WallFurniture one, listed first, would sit under the bed).
        TbxModel.FurnitureDef furnitureShelf = shelf("Furniture");
        original.floors.get(0).objects.add(new Furniture(3, 1, furnitureShelf, "N"));
        compiled = TbxFixture.compiledStacks(original, config);
        assertEquals(List.of("furniture_bedding_01_0", "furniture_shelving_01_33"),
            compiled[0][3][1].subList(compiled[0][3][1].size() - 2, compiled[0][3][1].size()));

        TbxReconstructor.Result result = rebuild();
        assertEquals(0, result.stats().unmatched);
        assertEquals(0, result.stats().extra);
        assertEquals(1, result.stats().userTiles, "the shelf and the bed stay furniture; only the decoration is a user tile");
        Furniture rebuiltShelf = result.building().floors.get(0).objects.stream()
            .filter(object -> object instanceof Furniture item && item.def.layer.equals("Furniture") && item.x == 3 && item.y == 1
                && "furniture_shelving_01_33".equals(item.resolved().tile(0, 0)))
            .map(Furniture.class::cast).findFirst().orElse(null);
        assertNotNull(rebuiltShelf, "the shelf is rebuilt from its Furniture-layer definition");
        assertNotNull(shelf("WallFurniture"), "the WallFurniture shelf exists and comes first in the config");
        assertTrue(shelf("WallFurniture").configIndex < furnitureShelf.configIndex);
    }

    private TbxModel.FurnitureDef shelf(String layer) {
        for (BuildingEdConfig.FurnitureUse use : config.furnitureUsing("furniture_shelving_01_33")) {
            if (use.def().layer.equals(layer)) {
                return use.def();
            }
        }

        return null;
    }

    @Test
    void converterRebuildsBuildingsFromCompiledMaps() throws Exception {
        Path map = Files.createDirectories(temp.resolve("map"));
        writeMap(map, new int[][] {{ORIGIN_X, ORIGIN_Y}}, -1, -1);

        Path output = temp.resolve("out");
        ConverterOptions options = ConverterOptions.parse(new String[] {
            "-i", map.toString(), "-o", output.toString(), "--tbx-only", "--tbx-config", temp.resolve("nested").toString()});
        ConversionResult result = new MapConverter(options).run();
        assertEquals(1, result.buildingCount());
        assertEquals(1, result.tbxCount());
        assertEquals(0, result.lotCount(), "no TMX, so no lots");

        assertEquals(List.of("buildings.csv", "kitchen_8x6.tbx"), fileNames(output.resolve("tbx_buildings")));
        Building reread = TbxReader.read(output.resolve("tbx_buildings/kitchen_8x6.tbx"));
        assertTrue(reread.properties.isEmpty(), "shared TBX files carry no per-placement properties");
        assertRendersLike(original, reread);
        assertEquals(List.of("tbx,world_x,world_y,world_z,source_cell,source_building,lot_cell", "kitchen_8x6.tbx,100,40,0,0_0,0,"),
            readLines(output.resolve("tbx_buildings/buildings.csv")));
    }

    @Test
    void identicalBuildingsShareOneTbxPlacedAsLots() throws Exception {
        Path map = Files.createDirectories(temp.resolve("map"));
        // Three copies of the sample building; the third one lacks the hand-drawn decoration.
        int[][] origins = {{ORIGIN_X, ORIGIN_Y}, {140, 40}, {100, 90}};
        writeMap(map, origins, 2, -1);

        Path output = temp.resolve("out");
        ConversionResult result = new MapConverter(ConverterOptions.parse(new String[] {
            "-i", map.toString(), "-o", output.toString(), "--no-images", "--tbx-config", temp.resolve("nested").toString()})).run();
        assertEquals(3, result.buildingCount());
        assertEquals(2, result.tbxCount(), "the two identical buildings share a file");
        assertEquals(3, result.lotCount());
        assertEquals(List.of("buildings.csv", "kitchen_8x6.tbx", "kitchen_8x6_2.tbx"), fileNames(output.resolve("tbx_buildings")));
        assertEquals(List.of("tbx,world_x,world_y,world_z,source_cell,source_building,lot_cell",
                "kitchen_8x6.tbx,100,40,0,0_0,0,0_0", "kitchen_8x6.tbx,140,40,0,0_0,1,0_0", "kitchen_8x6_2.tbx,100,90,0,0_0,2,0_0"),
            readLines(output.resolve("tbx_buildings/buildings.csv")));
        assertFalse(Files.exists(output.resolve("tmx/tbx")), "no RoomDef TBX set");

        String tmx = Files.readString(output.resolve("tmx/0_0.tmx"), StandardCharsets.UTF_8);
        assertTrue(tmx.contains(" <objectgroup name=\"Lots\" level=\"0\" width=\"256\" height=\"256\">\r\n"
            + "  <object name=\"lot\" type=\"../tbx_buildings/kitchen_8x6.tbx\" x=\"6400\" y=\"1280\" width=\"576\" height=\"224\"/>\r\n"
            + "  <object name=\"lot\" type=\"../tbx_buildings/kitchen_8x6.tbx\" x=\"8960\" y=\"1280\" width=\"576\" height=\"224\"/>\r\n"
            + "  <object name=\"lot\" type=\"../tbx_buildings/kitchen_8x6_2.tbx\" x=\"6400\" y=\"2880\" width=\"576\" height=\"224\"/>\r\n"
            + " </objectgroup>"), tmx);
        assertFalse(tmx.contains("RoomDefs"), "the lots supply every room");
        assertRoundTrip(output, origins, 2, -1);
    }

    @Test
    void buildingsTheTbxCannotReproduceStayInTheTmx() throws Exception {
        assertEquals("floors_interior_tilesandwood_01_40", compiled[0][HOLE_X][HOLE_Y].get(0), "the hole removes the kitchen floor");
        Path map = Files.createDirectories(temp.resolve("map"));
        int[][] origins = {{ORIGIN_X, ORIGIN_Y}, {140, 40}};
        writeMap(map, origins, -1, 1);
        Path output = temp.resolve("out");
        ConversionResult result = new MapConverter(ConverterOptions.parse(new String[] {
            "-i", map.toString(), "-o", output.toString(), "--no-images", "--tbx-config", temp.resolve("nested").toString()})).run();
        assertEquals(2, result.buildingCount());
        assertEquals(1, result.tbxCount(), "BuildingEd cannot leave out one floor tile, so both rebuild the same");
        assertEquals(1, result.lotCount(), "the rebuilt kitchen floor would cover the hole, so that building is not a lot");
        assertEquals("kitchen_8x6.tbx,140,40,0,0_0,1,", readLines(output.resolve("tbx_buildings/buildings.csv")).get(2));

        String tmx = Files.readString(output.resolve("tmx/0_0.tmx"), StandardCharsets.UTF_8);
        assertTrue(tmx.contains("<object name=\"kitchen\" type=\"\" x=\"8960\" y=\"1280\""), "its rooms stay RoomDefs");
        assertRoundTrip(output, origins, -1, 1);
    }

    @Test
    void withoutConfigBuildingsKeepTheirTilesAsUserTiles() throws Exception {
        Path map = Files.createDirectories(temp.resolve("map"));
        int[][] origins = {{ORIGIN_X, ORIGIN_Y}, {140, 40}};
        writeMap(map, origins, -1, -1);
        Path output = temp.resolve("out");
        ConversionResult result = new MapConverter(ConverterOptions.parse(new String[] {
            "-i", map.toString(), "-o", output.toString(), "--no-images"})).run();
        assertEquals(2, result.buildingCount());
        assertEquals(1, result.tbxCount());
        assertEquals(2, result.lotCount());

        String text = Files.readString(output.resolve("tbx_buildings/kitchen_8x6.tbx"), StandardCharsets.UTF_8);
        assertTrue(text.contains("ExteriorWall=\"0\""), "no tile entries without a config");
        assertTrue(text.contains("<room Name=\"kitchen\""));
        assertTrue(text.contains("<tile tile=\"walls_exterior_house_01_000\"/>"), "walls are kept as user tiles");
        assertRoundTrip(output, origins, -1, -1);
    }

    /**
     * Writes cell 0_0 with a copy of the sample building at each origin. Copy {@code plain} lacks the
     * decoration; copy {@code holed} lacks one kitchen floor tile.
     */
    private void writeMap(Path map, int[][] origins, int plain, int holed) throws Exception {
        List<RoomRect> rooms = new ArrayList<>();
        int[][] buildings = new int[origins.length][];
        for (int b = 0; b < origins.length; b++) {
            List<RoomRect> rects = TbxFixture.roomRects(original, origins[b][0], origins[b][1]);
            buildings[b] = new int[rects.size()];
            for (int r = 0; r < rects.size(); r++) {
                buildings[b][r] = rooms.size();
                rooms.add(rects.get(r));
            }
        }

        SyntheticMap.writeCompiledCell(map, 0, 0, 0, 1, (x, y, level) -> compiledAt(origins, plain, holed, x, y, level), rooms,
            buildings);
    }

    private List<String> compiledAt(int[][] origins, int plain, int holed, int x, int y, int level) {
        for (int b = 0; b < origins.length; b++) {
            int bx = x - origins[b][0];
            int by = y - origins[b][1];
            if (bx >= 0 && by >= 0 && bx <= original.width && by <= original.height) {
                List<String> stack = compiled[level][bx][by];
                if (b == plain) {
                    stack = new ArrayList<>(stack);
                    stack.remove(TbxFixture.DECOR);
                }

                if (b == holed && level == 0 && bx == HOLE_X && by == HOLE_Y) {
                    stack = new ArrayList<>(stack.subList(1, stack.size()));
                }

                return stack;
            }
        }

        return level == 0 ? List.of(TbxFixture.GROUND) : List.of();
    }

    /**
     * Rebuilds every square of cell 0_0 the way WorldEd does when it generates lots: the TMX tiles
     * plus the tiles each lot's TBX renders. The result must be exactly the compiled map.
     */
    private void assertRoundTrip(Path output, int[][] origins, int plain, int holed) throws Exception {
        Path tmxFile = output.resolve("tmx/0_0.tmx");
        org.w3c.dom.Element root = MapConverterTest.parse(tmxFile).getDocumentElement();
        java.util.TreeMap<Integer, String> tileSets = new java.util.TreeMap<>();
        java.util.Map<String, List<String>> squares = new java.util.HashMap<>();
        TbxLayout layout = new TbxLayout(config);
        org.w3c.dom.NodeList children = root.getChildNodes();
        for (int c = 0; c < children.getLength(); c++) {
            if (!(children.item(c) instanceof org.w3c.dom.Element element)) {
                continue;
            }

            switch (element.getTagName()) {
                case "tileset" -> tileSets.put(Integer.parseInt(element.getAttribute("firstgid")), element.getAttribute("name"));
                case "layer" -> {
                    int level = Integer.parseInt(element.getAttribute("level"));
                    String[] gids = element.getTextContent().trim().split("[,\\s]+");
                    for (int i = 0; i < gids.length; i++) {
                        int gid = Integer.parseInt(gids[i]);
                        if (gid != 0) {
                            java.util.Map.Entry<Integer, String> tileSet = tileSets.floorEntry(gid);
                            squares.computeIfAbsent(level + "," + (i % 256) + "," + (i / 256), k -> new ArrayList<>())
                                .add(tileSet.getValue() + "_" + (gid - tileSet.getKey()));
                        }
                    }
                }
                case "objectgroup" -> {
                    if (!element.getAttribute("name").equals("Lots")) {
                        continue;
                    }

                    int level = Integer.parseInt(element.getAttribute("level"));
                    org.w3c.dom.NodeList lots = element.getElementsByTagName("object");
                    for (int l = 0; l < lots.getLength(); l++) {
                        org.w3c.dom.Element lot = (org.w3c.dom.Element) lots.item(l);
                        Building building = TbxReader.read(tmxFile.getParent().resolve(lot.getAttribute("type")).normalize());
                        int lotX = Integer.parseInt(lot.getAttribute("x")) / 64;
                        int lotY = Integer.parseInt(lot.getAttribute("y")) / 32;
                        for (Floor floor : building.floors) {
                            TbxLayout.RenderedFloor rendered = layout.render(building, floor);
                            for (int x = 0; x <= building.width; x++) {
                                for (int y = 0; y <= building.height; y++) {
                                    for (Object[] tile : rendered.stack(x, y)) {
                                        squares.computeIfAbsent((level + floor.index) + "," + (lotX + x) + "," + (lotY + y),
                                            k -> new ArrayList<>()).add(tile[1].toString());
                                    }
                                }
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }

        for (int level = 0; level <= 1; level++) {
            for (int x = 0; x < 256; x++) {
                for (int y = 0; y < 256; y++) {
                    List<String> expected = new ArrayList<>(compiledAt(origins, plain, holed, x, y, level));
                    List<String> actual = squares.getOrDefault(level + "," + x + "," + y, new ArrayList<>());
                    expected.sort(null);
                    actual.sort(null);
                    assertEquals(expected, actual, "level " + level + " square " + x + "," + y);
                }
            }
        }
    }

    private static List<String> fileNames(Path directory) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static List<String> readLines(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿"), "UTF-8 BOM");
        return List.of(text.substring(1).split("\r\n"));
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
