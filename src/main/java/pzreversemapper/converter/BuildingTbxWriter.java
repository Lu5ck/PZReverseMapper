package pzreversemapper.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Writes the building TBX set. Every compiled building is rebuilt as a BuildingEd building;
 * buildings that come out identical share one TBX file under {@code tbx_buildings/}, and each
 * building is placed back on the map as a WorldEd lot in the TMX cell holding it. Tiles and rooms a
 * lot supplies are recorded on the target cells so the TMX does not repeat them.
 *
 * <p>With a BuildingEd configuration, buildings get rooms, walls, floors, doors, windows, stairs and
 * furniture. Without one, they hold their rooms and keep every building tile as a user-drawn tile.
 */
final class BuildingTbxWriter {
    static final String DIRECTORY = "tbx_buildings";
    static final String INDEX_FILE = "buildings.csv";

    private final String outputDirectory;
    private final int cellSize;
    private final Map<CellCoord, TargetCell> targetCells;
    private final TbxReconstructor reconstructor;
    private final boolean rebuilt;
    private final boolean placeLots;
    private final UnaryOperator<String> tileNames;
    private final TbxReconstructor.Stats stats = new TbxReconstructor.Stats();
    private final Map<CellCoord, Map<Integer, List<Map.Entry<LayerKey, TileLayer>>>> stackLayers = new HashMap<>();
    /** SHA-256 of a TBX document to the file holding it. */
    private final Map<String, String> fileByContent = new HashMap<>();
    private final Set<String> usedFileNames = new HashSet<>();
    private final Set<RoomKey> lotRooms = new HashSet<>();
    private final List<Placement> placements = new ArrayList<>();
    private int lotCount;
    private int crossingCount;
    private int inexactCount;

    /** A compiled room, identified by its source cell and id. */
    record RoomKey(int cellX, int cellY, int roomId) {
    }

    /** One compiled building: its shared TBX file, world position and lot cell (null when not placed). */
    record Placement(String file, int worldX, int worldY, int worldZ, int sourceCellX, int sourceCellY, int sourceBuilding,
                     CellCoord lotCell) {
    }

    /**
     * With a BuildingEd config, buildings are rebuilt as proper BuildingEd buildings instead of rooms
     * holding user tiles. Without {@code placeLots} (no TMX is written) buildings are not placed.
     * {@code tileNames} spells compiled tiles the way the TMX does and returns {@code null} for tiles a
     * TMX cannot store, which are then left out of the TBX files as well.
     */
    BuildingTbxWriter(String outputDirectory, int cellSize, Map<CellCoord, TargetCell> targetCells, BuildingEdConfig config,
                      boolean placeLots, UnaryOperator<String> tileNames) {
        this.outputDirectory = outputDirectory;
        this.cellSize = cellSize;
        this.targetCells = targetCells;
        this.rebuilt = config != null;
        this.placeLots = placeLots;
        this.tileNames = tileNames;
        this.reconstructor = new TbxReconstructor(config == null ? BuildingEdConfig.empty() : config);
    }

    /** Totals over all buildings: how much of the compiled building tiles the TBX files reproduce. */
    TbxReconstructor.Stats stats() {
        return stats;
    }

    /** Whether buildings were rebuilt from a BuildingEd configuration. */
    boolean rebuilt() {
        return rebuilt;
    }

    /** Compiled buildings written (each one is a placement of a shared TBX file). */
    int buildingCount() {
        return placements.size();
    }

    /** Distinct TBX files written. */
    int uniqueCount() {
        return fileByContent.size();
    }

    /** Buildings placed on the map as lots. */
    int lotCount() {
        return lotCount;
    }

    /** Buildings not placed because they do not fit in one TMX cell. */
    int crossingCount() {
        return crossingCount;
    }

    /** Buildings not placed because their TBX would draw tiles that are not in the compiled map. */
    int inexactCount() {
        return inexactCount;
    }

    boolean placesLots() {
        return placeLots;
    }

    /** Whether a compiled room is drawn by a lot, so the TMX must not repeat it as a RoomDef. */
    boolean isLotRoom(int sourceCellX, int sourceCellY, int roomId) {
        return lotRooms.contains(new RoomKey(sourceCellX, sourceCellY, roomId));
    }

    /** Writes the TBX files and {@code buildings.csv}; returns the number of buildings. */
    int write(Collection<LotHeaderData> headers) throws IOException {
        List<LotHeaderData> orderedHeaders = new ArrayList<>(headers);
        orderedHeaders.sort(Comparator.comparingInt(LotHeaderData::cellX).thenComparingInt(LotHeaderData::cellY));
        Path directory = Paths.get(outputDirectory, DIRECTORY);
        Files.createDirectories(directory);

        for (LotHeaderData header : orderedHeaders) {
            List<BuildingDef> buildings = new ArrayList<>(header.buildings());
            buildings.sort(Comparator.comparingInt(BuildingDef::sourceBuildingId));
            for (BuildingDef building : buildings) {
                Set<Integer> roomIds = new HashSet<>(building.roomIds());
                List<RoomRect> rooms = new ArrayList<>();
                for (RoomRect room : header.rooms()) {
                    if (roomIds.contains(room.sourceRoomId()) && room.width() > 0 && room.height() > 0) {
                        rooms.add(room);
                    }
                }

                if (!rooms.isEmpty()) {
                    writeBuilding(directory, header, building, rooms);
                }
            }
        }

        writeIndex(directory.resolve(INDEX_FILE));
        return placements.size();
    }

    private void writeBuilding(Path directory, LotHeaderData header, BuildingDef building, List<RoomRect> rooms) throws IOException {
        TbxReconstructor.Result result = reconstructor.reconstruct(rooms, this::stack, 4);
        TbxModel.Building model = result.building();
        stats.add(result.stats());

        String content = TbxXmlWriter.toXml(model);
        String digest = digest(content);
        String file = fileByContent.get(digest);
        if (file == null) {
            file = uniqueFileName(sanitizeFileName(model.rooms.get(0).internalName) + "_" + model.width + "x" + model.height);
            fileByContent.put(digest, file);
            TextFileWriter.writeFile(directory.resolve(file), content);
        }

        CellCoord lotCell = placeLot(result, file);
        if (lotCell != null) {
            for (RoomRect room : rooms) {
                lotRooms.add(new RoomKey(header.cellX(), header.cellY(), room.sourceRoomId()));
            }
        }

        placements.add(new Placement(file, result.originX(), result.originY(), result.baseLevel(), header.cellX(), header.cellY(),
            building.sourceBuildingId(), lotCell));
    }

    /**
     * Adds the building as a lot to the target cell holding it and marks the compiled tiles the lot
     * reproduces. A building is not placed (its tiles and rooms stay in the TMX) when it does not fit
     * in one target cell, its east and south wall squares included, or when its TBX would draw tiles
     * the compiled map does not have, so placing lots never changes the map.
     */
    private CellCoord placeLot(TbxReconstructor.Result result, String file) {
        if (!placeLots) {
            return null;
        }

        TbxModel.Building model = result.building();
        int originX = result.originX();
        int originY = result.originY();
        CellCoord coord = new CellCoord(BinaryHelpers.floorDiv(originX, cellSize), BinaryHelpers.floorDiv(originY, cellSize));
        CellCoord far = new CellCoord(
            BinaryHelpers.floorDiv(originX + model.width, cellSize),
            BinaryHelpers.floorDiv(originY + model.height, cellSize));
        TargetCell cell = targetCells.get(coord);
        if (cell == null || !coord.equals(far)) {
            crossingCount++;
            return null;
        }

        if (result.stats().extra > 0) {
            inexactCount++;
            return null;
        }

        int localX = BinaryHelpers.positiveMod(originX, cellSize);
        int localY = BinaryHelpers.positiveMod(originY, cellSize);
        cell.addLot(new TargetCell.Lot("../" + DIRECTORY + "/" + file, localX, localY, result.baseLevel(), model.width + 1,
            model.height + 1));

        boolean[][][][] reproduced = result.reproduced();
        for (int floor = 0; floor < reproduced.length; floor++) {
            int level = result.baseLevel() + floor;
            for (int x = 0; x <= model.width; x++) {
                for (int y = 0; y <= model.height; y++) {
                    boolean[] tiles = reproduced[floor][x][y];
                    if (tiles == null || tiles.length == 0) {
                        continue;
                    }

                    int index = (localX + x) + (localY + y) * cellSize;
                    List<LayerKey> keys = new ArrayList<>(tiles.length);
                    squareStack(coord, cell, level, index, keys);
                    for (int i = 0; i < tiles.length; i++) {
                        if (tiles[i]) {
                            cell.coverByLot(keys.get(i), index);
                        }
                    }
                }
            }
        }

        lotCount++;
        return coord;
    }

    private static String digest(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String uniqueFileName(String base) {
        String name = base;
        for (int counter = 2; !usedFileNames.add(Text.foldCase(name)); counter++) {
            name = base + "_" + counter;
        }

        return name + ".tbx";
    }

    /** {@code buildings.csv}: where each compiled building was and which TBX file it uses. */
    private void writeIndex(Path file) throws IOException {
        try (TextFileWriter writer = new TextFileWriter(file)) {
            writer.writeLine("tbx,world_x,world_y,world_z,source_cell,source_building,lot_cell");
            for (Placement placement : placements) {
                writer.writeLine(csv(placement.file()) + "," + placement.worldX() + "," + placement.worldY() + "," + placement.worldZ()
                    + "," + placement.sourceCellX() + "_" + placement.sourceCellY() + "," + placement.sourceBuilding() + ","
                    + (placement.lotCell() == null ? "" : placement.lotCell().x() + "_" + placement.lotCell().y()));
            }
        }
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** The compiled tile stack of a world square, bottom to top. */
    private List<String> stack(int worldX, int worldY, int level) {
        CellCoord coord = new CellCoord(BinaryHelpers.floorDiv(worldX, cellSize), BinaryHelpers.floorDiv(worldY, cellSize));
        TargetCell cell = targetCells.get(coord);
        if (cell == null) {
            return List.of();
        }

        int index = BinaryHelpers.positiveMod(worldX, cellSize) + BinaryHelpers.positiveMod(worldY, cellSize) * cellSize;
        return squareStack(coord, cell, level, index, null);
    }

    /** The tiles of one square of a target cell, bottom to top; {@code keys} receives each tile's layer. */
    private List<String> squareStack(CellCoord coord, TargetCell cell, int level, int index, List<LayerKey> keys) {
        List<Map.Entry<LayerKey, TileLayer>> layers = layers(coord, cell, level);
        List<String> stack = new ArrayList<>(layers.size());
        for (Map.Entry<LayerKey, TileLayer> layer : layers) {
            String tile = layer.getValue().get(index);
            String name = tile == null ? null : tileNames.apply(tile);
            if (name != null) {
                stack.add(name);
                if (keys != null) {
                    keys.add(layer.getKey());
                }
            }
        }

        return stack;
    }

    /** The layers of one level of a target cell, bottom to top. */
    private List<Map.Entry<LayerKey, TileLayer>> layers(CellCoord coord, TargetCell cell, int level) {
        return stackLayers
            .computeIfAbsent(coord, ignored -> new HashMap<>())
            .computeIfAbsent(level, ignored -> {
                List<Map.Entry<LayerKey, TileLayer>> ordered = new ArrayList<>();
                for (Map.Entry<LayerKey, TileLayer> entry : cell.tilesByLayer().entrySet()) {
                    if (entry.getKey().floor() == level) {
                        ordered.add(Map.entry(entry.getKey(), entry.getValue()));
                    }
                }

                ordered.sort(Map.Entry.comparingByKey(LayerKey.ORDER));
                return ordered;
            });
    }

    private static String sanitizeFileName(String value) {
        String sanitized = Text.sanitizeFileName(value);
        return Text.isNullOrWhiteSpace(sanitized) ? "building" : sanitized;
    }
}
