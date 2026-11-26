package pzreversemapper.converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes a small, deterministic set of compiled-map and asset files in the Project Zomboid
 * binary formats the converter reads. Used by the tests and for comparing the Java port against
 * the original C# build ({@code main(<output-folder>)}).
 */
public final class SyntheticMap {
    static final String[] GROUND = {
        "blends_natural_01_16", "blends_natural_01_32", "blends_natural_01_48", "floors_exterior_street_01_0",
        "blends_street_01_96", "blends_natural_02_0", "floors_exterior_natural_01_12"
    };
    static final String[] DECOR = {
        "vegetation_trees_01_8", "jumbo_tree_01_0", "blends_grassoverlays_01_30", "blends_grassoverlays_01_5",
        "blends_grassoverlays_01_50", "vegetation_foliage_01_5", "d_plants_01_2", "fencing_01_9", "e_roof_01_3",
        "vegetation_groundcover_01_19", "street_curbs_01_4", "trafficlines_01_2"
    };
    static final String[] STRUCTURE = {
        "walls_exterior_house_01_4", "walls_interior_house_02_17", "furniture_seating_01_3", "fixtures_sinks_01_1",
        "floors_interior_tilesandwood_01_40", "roofs_01_20", "lighting_indoor_01_2", "location_shop_generic_01_7"
    };
    static final String[] ODD = {"no_suffix_x", "badname", "carpentry_02_51", "Blends_Natural_01_21"};

    private SyntheticMap() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : "synthetic-map");
        write(root);
        System.out.println("Synthetic map written to " + root.toAbsolutePath());
    }

    /** Creates {@code map/}, {@code map41/}, {@code media/} and {@code modmedia/} under the root. */
    public static void write(Path root) throws IOException {
        Path map = Files.createDirectories(root.resolve("map"));
        Path map41 = Files.createDirectories(root.resolve("map41"));
        Path media = Files.createDirectories(root.resolve("media"));
        Path mod = Files.createDirectories(root.resolve("modmedia"));

        writeB42Cell(map, 1, 1, 11);
        writeB42Cell(map, 2, 1, 22);
        writeB42HeaderOnly(map, 3, 1);
        Files.writeString(map.resolve("objects.lua"), OBJECTS_LUA, StandardCharsets.UTF_8);
        Files.writeString(map.resolve("readme.txt"), "not a lotheader", StandardCharsets.UTF_8);
        writeBiomeTiles(map.resolve("maps"));

        writeV41Cell(map41, 10, 10, 33);

        writeTilesFile(media.resolve("tiledefinitions.tiles"), new String[][] {
            {"blends_natural_01", "8", "16"}, {"walls_exterior_house_01", "8", "8"}, {"vegetation_trees_01", "8", "2"}
        });
        writeTilesFile(media.resolve("tiledefinitions_extra.tiles"), new String[][] {
            {"furniture_seating_01", "8", "4"}, {"blends_street_01", "8", "16"}
        });
        writeTilesFile(media.resolve("newtiledefinitions.tiles"), new String[][] {
            {"roofs_01", "8", "4"}, {"blends_natural_01", "8", "12"}
        });
        Files.write(media.resolve("broken.tiles"), new byte[] {1, 2, 3});

        Path packs = Files.createDirectories(media.resolve("texturepacks"));
        writeNewPack(packs.resolve("Tiles2x.pack"), "Tiles2x0", new String[] {
            "blends_natural_01_16", "blends_natural_01_0", "walls_exterior_house_01_4", "walls_exterior_house_01_9", "jumbo_tree_01_0"
        }, 32, 64, 1);
        writeNewPack(packs.resolve("Tiles2x.floor.pack"), "Tiles2x0", new String[] {
            "blends_natural_01_16", "blends_natural_01_17", "floors_exterior_street_01_0"
        }, 32, 64, 2);
        writeOldPack(packs.resolve("UI.pack"), "UI0", new String[] {"ui_icon_01_0", "ui_icon_01_3"}, 16, 16);

        writeTilesFile(mod.resolve("mod.tiles"), new String[][] {{"blends_natural_01", "8", "20"}, {"mod_sheet_01", "8", "1"}});
        Path modPacks = Files.createDirectories(mod.resolve("TexturePacks"));
        writeNewPack(modPacks.resolve("ModTiles.pack"), "ModAtlas", new String[] {"mod_sheet_01_0", "mod_sheet_01_7", "mod_sheet_01_12"}, 32, 64, 3);
    }

    private static final String OBJECTS_LUA = String.join("\n",
        "-- generated",
        "objects = {",
        "  { name = \"\", type = \"TownZone\", x = 300, y = 260, z = 0, width = 100, height = 50 },",
        "  { name = \"Spawn\", type = \"SpawnPoint\", x = 320, y = 270, z = 0, width = 1, height = 1,"
            + " properties = { Professions = \"all\", Script = 'a, \"b\"', Count = 3, nested = { 1, 2 } } },",
        "  { name = \"river\", type = \"WaterFlow\", geometry = \"polyline\", lineWidth = 2,"
            + " points = { 260, 300, 400, 320, 650, 330 }, properties = { WaterDirection = 90.0, WaterSpeed = 1.0 } },",
        "  { name = \"zone\", type = \"Forest\", geometry = \"polygon\", points = { 600, 400, 700, 400, 700, 500 } },",
        "  { name = \"custom\", type = \"MyCustomType\", x = 280, y = 290 },",
        "  { name = \"far\", type = \"TownZone\", x = 5000, y = 5000, width = 10, height = 10 },",
        "  { name = \"noxy\", type = \"Nav\" },",
        "  { name = \"basement\", type = \"Basement\", x = 610, y = 310, z = -1, width = 4, height = 4 },",
        "  { name = \"esc\\\"aped\", type = \"LootZone\", x = 400, y = 300, width = 5, height = 5,"
            + " properties = { Note = \"line\\nbreak & <tag>\", Zeta = 1, Alpha = true } },",
        "  { name = \"brace } in string\", type = \"ZoneStory\", x = 450, y = 450, width = 3, height = 3 },",
        "}",
        "");

    private static void writeB42Cell(Path directory, int cellX, int cellY, long seed) throws IOException {
        Random random = new Random(seed);
        List<String> tiles = new ArrayList<>();
        addAll(tiles, GROUND, DECOR, STRUCTURE, ODD);

        List<Room> rooms = new ArrayList<>();
        rooms.add(new Room("kitchen", 0, new int[][] {{30, 10, 30, 20}, {60, 10, 10, 6}}));
        rooms.add(new Room("bedroom", 0, new int[][] {{30, 40, 12, 12}}));
        rooms.add(new Room("bedroom", 1, new int[][] {{30, 10, 20, 20}}));
        rooms.add(new Room("storage room", -1, new int[][] {{200, 200, 40, 30}}));
        rooms.add(new Room("", 0, new int[][] {{240, 240, 16, 16}, {0, 0, 0, 5}}));
        rooms.add(new Room("shed", 0, new int[][] {{120, 120, 6, 4}}));

        int[][] buildings = {{0, 1, 2}, {3}, {99}, {5, 4}};
        writeLotHeaderV1(directory.resolve(cellX + "_" + cellY + ".lotheader"), tiles, -1, 2, rooms, buildings, density(32, random));

        writeLotPackV1(directory.resolve("world_" + cellX + "_" + cellY + ".lotpack"), 8, 32, -1, 2, tiles, rooms, random);
    }

    private static void writeB42HeaderOnly(Path directory, int cellX, int cellY) throws IOException {
        writeLotHeaderV1(directory.resolve(cellX + "_" + cellY + ".lotheader"), List.of("blends_natural_01_16"), 0, 0,
            List.of(), new int[0][], new byte[0]);
    }

    private static void writeV41Cell(Path directory, int cellX, int cellY, long seed) throws IOException {
        Random random = new Random(seed);
        List<String> tiles = new ArrayList<>();
        addAll(tiles, GROUND, DECOR, STRUCTURE);
        List<Room> rooms = List.of(
            new Room("diner", 0, new int[][] {{10, 10, 20, 15}}),
            new Room("motel room", 1, new int[][] {{100, 200, 8, 8}}));
        int[][] buildings = {{0}, {1}};

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0);
        writeInt(out, tiles.size());
        for (String tile : tiles) {
            writeLine(out, tile);
        }

        out.write(0);
        writeInt(out, 10);
        writeInt(out, 10);
        writeInt(out, 2);
        writeRoomsAndBuildings(out, rooms, buildings);
        out.write(density(30, random));
        Files.write(directory.resolve(cellX + "_" + cellY + ".lotheader"), out.toByteArray());

        writeLotPackV0(directory.resolve("world_" + cellX + "_" + cellY + ".lotpack"), 10, 30, 0, 1, tiles, rooms, random);
    }

    private record Room(String name, int floor, int[][] rects) {
    }

    /** Supplies the compiled tile stack of a cell-local square. */
    @FunctionalInterface
    interface StackAt {
        List<String> stack(int localX, int localY, int level);
    }

    /**
     * Writes a Build 42 cell (lotheader + lotpack) whose squares hold the given stacks. Rooms are
     * cell-local rectangles; all rooms form building 0.
     */
    static void writeCompiledCell(Path directory, int cellX, int cellY, int minLevel, int maxLevel, StackAt stackAt,
                                  List<RoomRect> localRooms) throws IOException {
        List<String> tiles = new ArrayList<>();
        Map<String, Integer> tileIndex = new LinkedHashMap<>();
        for (int level = minLevel; level <= maxLevel; level++) {
            for (int x = 0; x < 256; x++) {
                for (int y = 0; y < 256; y++) {
                    for (String tile : stackAt.stack(x, y, level)) {
                        tileIndex.computeIfAbsent(tile, name -> {
                            tiles.add(name);
                            return tiles.size() - 1;
                        });
                    }
                }
            }
        }

        List<Room> rooms = new ArrayList<>();
        int[] building = new int[localRooms.size()];
        for (int i = 0; i < localRooms.size(); i++) {
            RoomRect rect = localRooms.get(i);
            rooms.add(new Room(rect.name(), rect.floor(), new int[][] {{rect.x(), rect.y(), rect.width(), rect.height()}}));
            building[i] = i;
        }

        writeLotHeaderV1(directory.resolve(cellX + "_" + cellY + ".lotheader"), tiles, minLevel, maxLevel, rooms,
            new int[][] {building}, new byte[32 * 32]);

        int chunkCount = 32 * 32;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long[] offsets = new long[chunkCount];
        long base = 12L + chunkCount * 8L;
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkY = 0; chunkY < 32; chunkY++) {
                offsets[chunkX * 32 + chunkY] = base + data.size();
                int pendingEmpty = 0;
                for (int z = minLevel; z <= maxLevel; z++) {
                    for (int x = 0; x < 8; x++) {
                        for (int y = 0; y < 8; y++) {
                            List<String> stack = stackAt.stack(chunkX * 8 + x, chunkY * 8 + y, z);
                            if (stack.isEmpty()) {
                                pendingEmpty++;
                                continue;
                            }

                            if (pendingEmpty > 0) {
                                writeInt(data, -1);
                                writeInt(data, pendingEmpty);
                                pendingEmpty = 0;
                            }

                            writeInt(data, stack.size() + 1);
                            writeInt(data, -1);
                            for (String tile : stack) {
                                writeInt(data, tileIndex.get(tile));
                            }
                        }
                    }
                }

                if (pendingEmpty > 0) {
                    writeInt(data, -1);
                    writeInt(data, pendingEmpty);
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("LOTP".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 1);
        writeInt(out, 8);
        for (long offset : offsets) {
            writeLong(out, offset);
        }

        data.writeTo(out);
        Files.write(directory.resolve("world_" + cellX + "_" + cellY + ".lotpack"), out.toByteArray());
    }

    private static void writeLotHeaderV1(Path file, List<String> tiles, int minLevel, int maxLevel, List<Room> rooms, int[][] buildings,
                                         byte[] density) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("LOTH".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 1);
        writeInt(out, tiles.size());
        for (String tile : tiles) {
            writeLine(out, tile + (tile.startsWith("fixtures") ? "  \r" : ""));
        }

        writeInt(out, 8);
        writeInt(out, 8);
        writeInt(out, minLevel);
        writeInt(out, maxLevel);
        writeRoomsAndBuildings(out, rooms, buildings);
        out.write(density);
        Files.write(file, out.toByteArray());
    }

    private static void writeRoomsAndBuildings(ByteArrayOutputStream out, List<Room> rooms, int[][] buildings) throws IOException {
        writeInt(out, rooms.size());
        for (Room room : rooms) {
            writeLine(out, room.name());
            writeInt(out, room.floor());
            writeInt(out, room.rects().length);
            for (int[] rect : room.rects()) {
                for (int value : rect) {
                    writeInt(out, value);
                }
            }

            writeInt(out, 1);
            writeInt(out, 5);
            writeInt(out, 6);
            writeInt(out, 7);
        }

        writeInt(out, buildings.length);
        for (int[] building : buildings) {
            writeInt(out, building.length);
            for (int roomId : building) {
                writeInt(out, roomId);
            }
        }
    }

    private static byte[] density(int chunksPerCell, Random random) {
        byte[] density = new byte[chunksPerCell * chunksPerCell];
        for (int i = 0; i < density.length; i++) {
            density[i] = (byte) random.nextInt(256);
        }

        return density;
    }

    /** Returns the tile stack (indices into the tile list) for one square, or an empty array. */
    private static int[] squareTiles(int localX, int localY, int z, List<String> tiles, List<Room> rooms, Random random) {
        List<Integer> stack = new ArrayList<>();
        boolean inRoom = false;
        for (Room room : rooms) {
            if (room.floor() != z) {
                continue;
            }

            for (int[] rect : room.rects()) {
                if (localX >= rect[0] && localX < rect[0] + rect[2] && localY >= rect[1] && localY < rect[1] + rect[3]) {
                    inRoom = true;
                }
            }
        }

        if (z == 0) {
            if ((localX / 17 + localY / 13) % 7 == 3) {
                return new int[0];
            }

            stack.add(tiles.indexOf(GROUND[(localX / 9 + localY / 11) % GROUND.length]));
            int roll = random.nextInt(100);
            if (roll < 12) {
                stack.add(tiles.indexOf(DECOR[random.nextInt(DECOR.length)]));
            }

            if (roll < 2) {
                stack.add(tiles.indexOf(ODD[random.nextInt(ODD.length)]));
            }
        }

        if (inRoom) {
            stack.add(tiles.indexOf(STRUCTURE[4]));
            if (random.nextInt(4) == 0) {
                stack.add(tiles.indexOf(STRUCTURE[random.nextInt(STRUCTURE.length)]));
            }
        } else if (z > 0 && random.nextInt(400) == 0) {
            stack.add(tiles.indexOf(STRUCTURE[5]));
        }

        int[] result = new int[stack.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = stack.get(i);
        }

        return result;
    }

    private static byte[] encodeChunk(int chunkX, int chunkY, int chunkDim, int minLevel, int maxLevel, List<String> tiles,
                                      List<Room> rooms, Random random) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pendingEmpty = 0;
        for (int z = minLevel; z <= maxLevel; z++) {
            for (int x = 0; x < chunkDim; x++) {
                for (int y = 0; y < chunkDim; y++) {
                    int[] stack = squareTiles(chunkX * chunkDim + x, chunkY * chunkDim + y, z, tiles, rooms, random);
                    if (stack.length == 0) {
                        pendingEmpty++;
                        continue;
                    }

                    if (pendingEmpty > 0) {
                        writeEmpty(out, pendingEmpty, random);
                        pendingEmpty = 0;
                    }

                    writeInt(out, stack.length + 1);
                    writeInt(out, -1);
                    for (int tile : stack) {
                        writeInt(out, tile);
                    }
                }
            }
        }

        if (pendingEmpty > 0) {
            writeEmpty(out, pendingEmpty, random);
        }

        return out.toByteArray();
    }

    /** Encodes a run of empty squares, mixing the run-length, zero-count and one-count encodings. */
    private static void writeEmpty(ByteArrayOutputStream out, int count, Random random) {
        if (count <= 2 && random.nextBoolean()) {
            for (int i = 0; i < count; i++) {
                writeInt(out, i % 2);
            }

            return;
        }

        writeInt(out, -1);
        writeInt(out, count);
    }

    private static void writeLotPackV1(Path file, int chunkDim, int chunksPerCell, int minLevel, int maxLevel, List<String> tiles,
                                       List<Room> rooms, Random random) throws IOException {
        int chunkCount = chunksPerCell * chunksPerCell;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long[] offsets = new long[chunkCount];
        long base = 12L + chunkCount * 8L;
        for (int chunkX = 0; chunkX < chunksPerCell; chunkX++) {
            for (int chunkY = 0; chunkY < chunksPerCell; chunkY++) {
                int index = chunkX * chunksPerCell + chunkY;
                if (chunkX == 5 && chunkY == 7) {
                    offsets[index] = 0;
                    continue;
                }

                offsets[index] = base + data.size();
                data.write(encodeChunk(chunkX, chunkY, chunkDim, minLevel, maxLevel, tiles, rooms, random));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("LOTP".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 1);
        writeInt(out, chunkDim);
        for (long offset : offsets) {
            writeLong(out, offset);
        }

        data.writeTo(out);
        Files.write(file, out.toByteArray());
    }

    private static void writeLotPackV0(Path file, int chunkDim, int chunksPerCell, int minLevel, int maxLevel, List<String> tiles,
                                       List<Room> rooms, Random random) throws IOException {
        int chunkCount = chunksPerCell * chunksPerCell;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[chunkCount];
        int base = 4 + chunkCount * 8;
        for (int chunkX = 0; chunkX < chunksPerCell; chunkX++) {
            for (int chunkY = 0; chunkY < chunksPerCell; chunkY++) {
                offsets[chunkX * chunksPerCell + chunkY] = base + data.size();
                data.write(encodeChunk(chunkX, chunkY, chunkDim, minLevel, maxLevel, tiles, rooms, random));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, chunkCount);
        for (int offset : offsets) {
            writeInt(out, offset);
            writeInt(out, 0);
        }

        data.writeTo(out);
        Files.write(file, out.toByteArray());
    }

    private static void writeTilesFile(Path file, String[][] sheets) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0x66656474);
        writeInt(out, 1);
        writeInt(out, sheets.length);
        for (String[] sheet : sheets) {
            writeLine(out, sheet[0]);
            writeLine(out, "Tiles/" + sheet[0] + ".png");
            int width = Integer.parseInt(sheet[1]);
            int height = Integer.parseInt(sheet[2]);
            writeInt(out, width);
            writeInt(out, height);
            writeInt(out, 100);
            writeInt(out, 2);
            for (int tile = 0; tile < 2; tile++) {
                writeInt(out, tile);
                for (int prop = 0; prop < tile; prop++) {
                    writeLine(out, "IsMoveable");
                    writeLine(out, "true");
                }
            }
        }

        Files.write(file, out.toByteArray());
    }

    private static byte[] atlasPng(int width, int height, int seed) throws IOException {
        PngImage atlas = new PngImage(width, height);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                atlas.setPixel(x, y, new Rgba(random.nextInt(256), (x * 7 + seed) & 0xff, (y * 5) & 0xff, 128 + random.nextInt(128)));
            }
        }

        Path temp = Files.createTempFile("atlas", ".png");
        try {
            atlas.save(temp);
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writePackTiles(ByteArrayOutputStream out, String[] names, int tileWidth, int tileHeight) {
        writeInt(out, names.length);
        writeInt(out, 1);
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            writeInt(out, names[i].length());
            out.write(name, 0, name.length);
            int width = tileWidth - 4 - i;
            int height = tileHeight - 10 - 2 * i;
            writeInt(out, i * tileWidth);
            writeInt(out, 0);
            writeInt(out, width);
            writeInt(out, height);
            writeInt(out, 2 + i);
            writeInt(out, 6 + i);
            writeInt(out, tileWidth);
            writeInt(out, tileHeight);
        }
    }

    private static void writeNewPack(Path file, String atlasName, String[] names, int tileWidth, int tileHeight, int seed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 1263557200);
        writeInt(out, 1);
        writeInt(out, 1);
        writeInt(out, atlasName.length());
        out.write(atlasName.getBytes(StandardCharsets.UTF_8));
        writePackTiles(out, names, tileWidth, tileHeight);
        byte[] png = atlasPng(names.length * tileWidth, tileHeight, seed);
        writeInt(out, png.length);
        out.write(png);
        Files.write(file, out.toByteArray());
    }

    private static void writeOldPack(Path file, String atlasName, String[] names, int tileWidth, int tileHeight) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 1);
        writeInt(out, atlasName.length());
        out.write(atlasName.getBytes(StandardCharsets.UTF_8));
        writePackTiles(out, names, tileWidth, tileHeight);
        out.write(atlasPng(names.length * tileWidth, tileHeight, 9));
        writeInt(out, 0xDEADBEEF);
        Files.write(file, out.toByteArray());
    }

    private static void writeBiomeTiles(Path directory) throws IOException {
        Files.createDirectories(directory);
        Map<String, Integer> tiles = new LinkedHashMap<>();
        tiles.put("biomemap_1_1.png", 1);
        tiles.put("biomemap_2_1.png", 2);
        tiles.put("biomemap_1_2.png", 3);
        for (Map.Entry<String, Integer> tile : tiles.entrySet()) {
            PngImage image = new PngImage(24, 16);
            image.clear(new Rgba(tile.getValue() * 60, 255 - tile.getValue() * 40, 30));
            image.setPixel(tile.getValue(), tile.getValue(), Rgba.RED);
            image.save(directory.resolve(tile.getKey()));
        }

        Files.writeString(directory.resolve("biomemap_notes.png"), "ignored");
    }

    private static void addAll(List<String> target, String[]... groups) {
        for (String[] group : groups) {
            for (String value : group) {
                if (!target.contains(value)) {
                    target.add(value);
                }
            }
        }
    }

    private static void writeLine(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        out.write(bytes, 0, 4);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
        out.write(bytes, 0, 8);
    }
}
