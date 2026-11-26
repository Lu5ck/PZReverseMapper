package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class MapConverterTest {
    @TempDir
    static Path fixture;

    @TempDir
    Path output;

    @BeforeAll
    static void createFixture() throws IOException {
        SyntheticMap.write(fixture);
    }

    private ConverterOptions.Builder b42Options() {
        return ConverterOptions.builder()
            .inputDirectory(fixture.resolve("map").toString())
            .outputDirectory(output.toString())
            .projectName("Synthetic")
            .tilesPath(fixture.resolve("media").toString())
            .modTilesPath(fixture.resolve("modmedia").toString())
            .expectedSourceCellSize(256)
            .targetCellSize(300);
    }

    @Test
    void b42SourceIsReprojectedToClassicGrid() throws Exception {
        ConversionResult result = new MapConverter(b42Options().build()).run();

        assertEquals(3, result.sourceCellCount());
        // World x 256..1023, y 256..511 -> target cells 0..3 x 0..1.
        assertEquals(8, result.targetCellCount());
        try (Stream<Path> tmxFiles = Files.list(output.resolve("tmx"))) {
            assertEquals(8, tmxFiles.filter(p -> p.toString().endsWith(".tmx")).count());
        }
        assertTrue(Files.isRegularFile(output.resolve("Synthetic.pzw")));
        assertTrue(result.tbxCount() > 0);
        assertEquals(6, result.buildingTbxCount(), "3 buildings with rooms in each of two source cells");
        assertEquals(8 * 3 + 3 + 1, result.imageCount() - 1, "per-cell images, merged images, zombie map (+ biome map)");
        assertTrue(Files.isRegularFile(output.resolve("biomemap.png")));
        assertTrue(Files.isRegularFile(output.resolve("Map_ZombieSpawnMap.png")));

        PngImage world = PngImage.load(output.resolve("world.png"));
        assertEquals(4 * 300, world.width());
        assertEquals(2 * 300, world.height());

        PngImage biome = PngImage.load(output.resolve("biomemap.png"));
        assertEquals(48, biome.width());
        assertEquals(32, biome.height());
        assertEquals((byte) 255, biome.pixels()[(25 + 15 * 48) * 4 + 3], "missing biome tiles are opaque black");

        for (Path file : allFiles(output, ".tmx", ".tbx", ".pzw")) {
            byte[] bytes = Files.readAllBytes(file);
            assertEquals((byte) 0xEF, bytes[0], "UTF-8 BOM in " + file);
            parse(file);
        }

        String tmx = Files.readString(output.resolve("tmx/1_0.tmx"), StandardCharsets.UTF_8);
        assertTrue(tmx.contains("<map version=\"2.0\" orientation=\"levelisometric\" width=\"300\" height=\"300\""));
        assertTrue(tmx.contains("\r\n"), "CRLF line endings");
        // The basement room (source 1_1 local 200..239) lands in world 456..495 -> target cell 1_1.
        String basementCell = Files.readString(output.resolve("tmx/1_1.tmx"), StandardCharsets.UTF_8);
        assertTrue(basementCell.contains("<layer name=\"Floor\" level=\"-1\""), "basement level is kept");
        assertTrue(basementCell.contains("<objectgroup name=\"RoomDefs\" level=\"-1\""), "basement RoomDefs are kept");
        assertTrue(tmx.contains("type=\".\\tbx\\1_0\\"), "RoomDef objects reference their TBX");
    }

    @Test
    void tileGidsMatchTilesetsAndReprojection() throws Exception {
        new MapConverter(b42Options().exportImages(false).build()).run();

        Document tmx = parse(output.resolve("tmx/0_0.tmx"));
        NodeList tilesets = tmx.getElementsByTagName("tileset");
        assertTrue(tilesets.getLength() > 5);
        java.util.Map<String, Integer> firstGids = new java.util.HashMap<>();
        for (int i = 0; i < tilesets.getLength(); i++) {
            Element tileset = (Element) tilesets.item(i);
            firstGids.put(tileset.getAttribute("name"), Integer.parseInt(tileset.getAttribute("firstgid")));
            if (tileset.getAttribute("name").equals("blends_natural_01")) {
                Element image = (Element) tileset.getElementsByTagName("image").item(0);
                assertEquals("512", image.getAttribute("width"));
                assertEquals(String.valueOf(128 * 20), image.getAttribute("height"), "mod .tiles overrides the sheet size");
            }
        }

        // newtiledefinitions.tiles sorts first: roofs_01 (8x4) takes gids 1..32.
        assertEquals(1, firstGids.get("roofs_01"));
        assertEquals(33, firstGids.get("blends_natural_01"));
        assertTrue(firstGids.get("furniture_seating_01") < firstGids.get("walls_exterior_house_01"),
            "tiledefinitions_extra.tiles sorts before tiledefinitions.tiles, like .NET culture ordering");
        assertTrue(firstGids.containsKey("jumbo_tree_01"), "sheets without .tiles metadata are inferred from tile names");
        assertTrue(!firstGids.containsKey("no_suffix") && !firstGids.containsKey("badname"), "names without a numeric id are ignored");

        // Source cell 1_1 starts at world (256, 256): target cell 0_0 local (256, 256) holds its local (0, 0).
        Element floor = findLayer(tmx, "Floor", "0");
        String[] values = floor.getTextContent().trim().split("[,\\s]+");
        assertEquals(300 * 300, values.length);
        assertEquals("0", values[0], "world (0,0) has no source cell");
        int sourceGid = Integer.parseInt(values[256 + 256 * 300]);
        assertTrue(sourceGid > 0, "reprojected ground tile present");
    }

    @Test
    void objectsAreWrittenIntoTheirTargetCells() throws Exception {
        new MapConverter(b42Options().exportImages(false).build()).run();
        Document pzw = parse(output.resolve("Synthetic.pzw"));
        NodeList objects = pzw.getElementsByTagName("object");
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < objects.getLength(); i++) {
            Element object = (Element) objects.item(i);
            names.add(object.getAttribute("name"));
            if (object.getAttribute("name").equals("Spawn")) {
                Element cell = (Element) object.getParentNode();
                assertEquals("tmx/1_0.tmx", cell.getAttribute("map"));
                assertEquals("20", object.getAttribute("x"));
                assertEquals("270", object.getAttribute("y"));
                assertEquals(4, object.getElementsByTagName("property").getLength());
            }

            if (object.getAttribute("name").equals("river")) {
                // Anchored on its first point (260, 300) -> cell 0_1; points become cell-local.
                assertEquals("tmx/0_1.tmx", ((Element) object.getParentNode()).getAttribute("map"));
                assertEquals("polyline", object.getAttribute("geometry"));
                assertEquals("2", object.getAttribute("linewidth"));
                assertEquals("260,0 400,20 650,30", object.getAttribute("points"));
            }

            if (object.getAttribute("type").equals("LootZone")) {
                assertEquals("esc\"aped", object.getAttribute("name"));
            }
        }

        assertTrue(names.containsAll(List.of("", "Spawn", "river", "zone", "custom", "basement", "brace } in string")));
        assertFalse(names.contains("far"));
        assertFalse(names.contains("noxy"));
        String pzwText = Files.readString(output.resolve("Synthetic.pzw"), StandardCharsets.UTF_8);
        assertTrue(pzwText.contains("<objecttype name=\"MyCustomType\"/>"));
        assertTrue(pzwText.contains("<property name=\"Note\" value=\"line\nbreak &amp; &lt;tag&gt;\"/>"),
            "Lua escapes are decoded and XML-escaped like the original");
    }

    @Test
    void v41SourceWritesClassicLayerNames() throws Exception {
        ConverterOptions options = ConverterOptions.builder()
            .inputDirectory(fixture.resolve("map41").toString())
            .outputDirectory(output.toString())
            .expectedSourceCellSize(300)
            .build();
        ConversionResult result = new MapConverter(options).run();
        assertEquals(1, result.sourceCellCount());
        assertEquals(1, result.targetCellCount());
        String tmx = Files.readString(output.resolve("tmx/10_10.tmx"));
        assertTrue(tmx.contains("<map version=\"1.0\""));
        assertTrue(tmx.contains("name=\"0_Floor\""));
        assertTrue(tmx.contains("name=\"1_RoomDefs\""));
        assertTrue(Files.isRegularFile(output.resolve("ConvertedMap.pzw")));
    }

    @Test
    void tbxOnlyAndCellFilter() throws Exception {
        ConversionResult result = new MapConverter(b42Options().tbxOnly(true).exportImages(false).exportObjects(false)
            .includeCells(Set.of(new CellCoord(1, 1))).build()).run();
        assertEquals(1, result.sourceCellCount());
        assertEquals("", result.projectFile());
        assertFalse(Files.exists(output.resolve("tmx/0_0.tmx")));
        assertTrue(Files.isDirectory(output.resolve("tmx/tbx/0_0")));
        assertTrue(Files.isDirectory(output.resolve("tbx_buildings/1_1")));
        assertFalse(Files.exists(output.resolve("biomemap.png")));
    }

    @Test
    void validationErrors() {
        assertThrows(InvalidDataException.class,
            () -> new MapConverter(b42Options().expectedSourceCellSize(300).build()).run());
        assertThrows(InvalidDataException.class,
            () -> new MapConverter(b42Options().includeCells(Set.of(new CellCoord(9, 9))).build()).run());
        assertThrows(IllegalStateException.class,
            () -> new MapConverter(b42Options().outputDirectory(fixture.resolve("map").toString()).build()).run());
        assertThrows(IllegalStateException.class,
            () -> new MapConverter(b42Options().tbxOnly(true).exportRoomTbx(false).exportBuildingTbx(false).build()).run());
    }

    @Test
    void cleanRequiresConfirmation() throws Exception {
        Files.writeString(output.resolve("old.txt"), "old");
        assertThrows(IllegalStateException.class, () -> new MapConverter(b42Options().cleanOutput(true).build()).run());
        assertTrue(Files.exists(output.resolve("old.txt")), "nothing is removed without confirmation");
    }

    @Test
    void cliRunsAndReportsUsage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(new String[] {
            "--input", fixture.resolve("map").toString(), "--output", output.toString(), "--cells", "2_1", "--no-images"},
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Input cells:  1"));

        assertEquals(0, Cli.run(new String[] {"--help"}, new PrintStream(out), new PrintStream(err)));
        assertEquals(1, Cli.run(new String[0], new PrintStream(out), new PrintStream(err)));
        assertEquals(2, Cli.run(new String[] {"--nope"}, new PrintStream(out), new PrintStream(err)));
    }

    private static Element findLayer(Document document, String name, String level) {
        NodeList layers = document.getElementsByTagName("layer");
        for (int i = 0; i < layers.getLength(); i++) {
            Element layer = (Element) layers.item(i);
            if (layer.getAttribute("name").equals(name) && layer.getAttribute("level").equals(level)) {
                return (Element) layer.getElementsByTagName("data").item(0);
            }
        }

        throw new AssertionError("layer " + name + " level " + level + " not found");
    }

    static Document parse(Path file) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
    }

    static List<Path> allFiles(Path root, String... extensions) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                .filter(file -> Stream.of(extensions).anyMatch(file.toString()::endsWith))
                .collect(Collectors.toList());
        }
    }
}
