package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Writes supplemental building TBX files reconstructed from compiled building room ids. */
final class BuildingTbxWriter {
    private final String outputDirectory;
    private final int cellSize;
    private final Map<CellCoord, TargetCell> targetCells;
    private final TbxReconstructor reconstructor;
    private final TbxReconstructor.Stats stats = new TbxReconstructor.Stats();
    private final Map<CellCoord, Map<Integer, List<TileLayer>>> stackLayers = new HashMap<>();
    private List<LayerKey> allLayerKeys;
    private int reconstructedCount;

    BuildingTbxWriter(String outputDirectory, int cellSize, Map<CellCoord, TargetCell> targetCells) {
        this(outputDirectory, cellSize, targetCells, null);
    }

    /** With a BuildingEd config, buildings are rebuilt as proper BuildingEd buildings instead of tile dumps. */
    BuildingTbxWriter(String outputDirectory, int cellSize, Map<CellCoord, TargetCell> targetCells, BuildingEdConfig config) {
        this.outputDirectory = outputDirectory;
        this.cellSize = cellSize;
        this.targetCells = targetCells;
        this.reconstructor = config == null ? null : new TbxReconstructor(config);
    }

    /** Totals over all rebuilt buildings (only meaningful with a BuildingEd config). */
    TbxReconstructor.Stats stats() {
        return stats;
    }

    int reconstructedCount() {
        return reconstructedCount;
    }

    int write(Collection<LotHeaderData> headers) throws IOException {
        List<LotHeaderData> orderedHeaders = new ArrayList<>(headers);
        orderedHeaders.sort(Comparator.comparingInt(LotHeaderData::cellX).thenComparingInt(LotHeaderData::cellY));

        int count = 0;
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

                if (rooms.isEmpty()) {
                    continue;
                }

                if (reconstructor != null) {
                    writeReconstructed(header, building, rooms);
                } else {
                    writeBuilding(header, building, rooms);
                }

                count++;
            }
        }

        return count;
    }

    private void writeBuilding(LotHeaderData header, BuildingDef building, List<RoomRect> rooms) throws IOException {
        BuildingBounds bounds = getBounds(rooms);

        Map<Integer, List<RoomRect>> groupsById = new LinkedHashMap<>();
        for (RoomRect room : rooms) {
            groupsById.computeIfAbsent(room.sourceRoomId(), id -> new ArrayList<>()).add(room);
        }

        List<Map.Entry<Integer, List<RoomRect>>> roomGroups = new ArrayList<>(groupsById.entrySet());
        roomGroups.sort(Comparator
            .comparingInt((Map.Entry<Integer, List<RoomRect>> group) -> minFloor(group.getValue()))
            .thenComparingInt(Map.Entry::getKey));

        Map<Integer, Integer> roomIndexById = new HashMap<>();
        for (int i = 0; i < roomGroups.size(); i++) {
            roomIndexById.put(roomGroups.get(i).getKey(), i + 1);
        }

        List<String> userTiles = collectUserTiles(bounds);
        Map<String, Integer> userTileIds = new HashMap<>();
        for (int i = 0; i < userTiles.size(); i++) {
            userTileIds.put(userTiles.get(i), i + 1);
        }

        TreeSet<Integer> floors = new TreeSet<>();
        for (RoomRect room : rooms) {
            floors.add(room.floor());
        }

        floors.addAll(getFloorsWithTiles(bounds));

        String sourceCellName = header.cellX() + "_" + header.cellY();
        String buildingName = roomGroups.get(0).getValue().get(0).name();
        String safeName = sanitizeFileName(buildingName);
        Path directory = Paths.get(outputDirectory, "tbx_buildings", sourceCellName);
        Files.createDirectories(directory);

        String fileName = String.format(java.util.Locale.ROOT, "%s_b%04d_%s_%d_%d.tbx",
            sourceCellName, building.sourceBuildingId(), safeName, bounds.minX, bounds.minY);
        Path file = directory.resolve(fileName);

        try (TextFileWriter writer = new TextFileWriter(file)) {
            writer.writeLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.writeLine("<building version=\"4\" width=\"" + bounds.width + "\" height=\"" + bounds.height
                + "\" ExteriorWall=\"0\" ExteriorWallTrim=\"0\" Door=\"0\" DoorFrame=\"0\" Window=\"0\" Curtains=\"0\" Shutters=\"0\""
                + " Stairs=\"0\" RoofCap=\"0\" RoofSlope=\"0\" RoofTop=\"0\" GrimeWall=\"0\">");
            writer.writeLine("<properties>");
            writer.writeLine("<property name=\"Legend\" value=\"Residential\"/>");
            writer.writeLine("<property name=\"WorldX\" value=\"" + bounds.minX + "\"/>");
            writer.writeLine("<property name=\"WorldY\" value=\"" + bounds.minY + "\"/>");
            writer.writeLine("<property name=\"SourceCell\" value=\"" + sourceCellName + "\"/>");
            writer.writeLine("<property name=\"SourceBuilding\" value=\"" + building.sourceBuildingId() + "\"/>");
            writer.writeLine("</properties>");
            writer.writeLine(" <user_tiles>");
            for (String tile : userTiles) {
                writer.writeLine("  <tile tile=\"" + Text.xmlEscape(tile) + "\"/>");
            }

            writer.writeLine(" </user_tiles>");
            writer.writeLine(" <used_tiles></used_tiles>");
            writer.writeLine(" <used_furniture></used_furniture>");

            for (Map.Entry<Integer, List<RoomRect>> group : roomGroups) {
                RoomRect room = group.getValue().get(0);
                writer.writeLine(" <room Name=\"" + Text.xmlEscape(room.name()) + "\" InternalName=\"" + Text.xmlEscape(room.name())
                    + "\" Color=\"" + getRoomColor(room)
                    + "\" InteriorWall=\"0\" InteriorWallTrim=\"0\" Floor=\"0\" GrimeFloor=\"0\" GrimeWall=\"0\"/>");
            }

            for (int floor : floors) {
                List<RoomRect> floorRooms = new ArrayList<>();
                for (RoomRect room : rooms) {
                    if (room.floor() == floor) {
                        floorRooms.add(room);
                    }
                }

                writer.writeLine(" <floor>");
                writer.writeLine("  <rooms>");
                writer.writeLine(buildRoomCsv(bounds, floorRooms, roomIndexById));
                writer.writeLine("  </rooms>");
                writeTileLayers(writer, bounds, floor, userTileIds);
                writer.writeLine(" </floor>");
            }

            writer.writeLine("</building>");
        }
    }

    private void writeReconstructed(LotHeaderData header, BuildingDef building, List<RoomRect> rooms) throws IOException {
        TbxReconstructor.Result result = reconstructor.reconstruct(rooms, this::stack, 4);
        TbxModel.Building model = result.building();
        String sourceCellName = header.cellX() + "_" + header.cellY();
        model.properties.put("WorldX", Integer.toString(result.originX()));
        model.properties.put("WorldY", Integer.toString(result.originY()));
        model.properties.put("WorldZ", Integer.toString(result.baseLevel()));
        model.properties.put("SourceCell", sourceCellName);
        model.properties.put("SourceBuilding", Integer.toString(building.sourceBuildingId()));

        Path directory = Paths.get(outputDirectory, "tbx_buildings", sourceCellName);
        Files.createDirectories(directory);
        String fileName = String.format(java.util.Locale.ROOT, "%s_b%04d_%s_%d_%d.tbx",
            sourceCellName, building.sourceBuildingId(), sanitizeFileName(model.rooms.get(0).internalName),
            result.originX(), result.originY());
        TbxXmlWriter.write(model, directory.resolve(fileName));
        stats.add(result.stats());
        reconstructedCount++;
    }

    /** The compiled tile stack of a world square, bottom to top. */
    private List<String> stack(int worldX, int worldY, int level) {
        CellCoord coord = new CellCoord(BinaryHelpers.floorDiv(worldX, cellSize), BinaryHelpers.floorDiv(worldY, cellSize));
        TargetCell cell = targetCells.get(coord);
        if (cell == null) {
            return List.of();
        }

        List<TileLayer> layers = stackLayers
            .computeIfAbsent(coord, ignored -> new HashMap<>())
            .computeIfAbsent(level, ignored -> {
                List<LayerKey> keys = new ArrayList<>();
                for (LayerKey key : cell.tilesByLayer().keySet()) {
                    if (key.floor() == level) {
                        keys.add(key);
                    }
                }

                keys.sort(LayerKey.ORDER);
                List<TileLayer> ordered = new ArrayList<>();
                for (LayerKey key : keys) {
                    ordered.add(cell.tilesByLayer().get(key));
                }

                return ordered;
            });
        int index = BinaryHelpers.positiveMod(worldX, cellSize) + BinaryHelpers.positiveMod(worldY, cellSize) * cellSize;
        List<String> stack = new ArrayList<>(layers.size());
        for (TileLayer layer : layers) {
            String tile = layer.get(index);
            if (tile != null) {
                stack.add(tile);
            }
        }

        return stack;
    }

    private static int minFloor(List<RoomRect> rooms) {
        int min = Integer.MAX_VALUE;
        for (RoomRect room : rooms) {
            min = Math.min(min, room.floor());
        }

        return min;
    }

    private static BuildingBounds getBounds(List<RoomRect> rooms) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (RoomRect room : rooms) {
            minX = Math.min(minX, room.x());
            minY = Math.min(minY, room.y());
            maxX = Math.max(maxX, room.right());
            maxY = Math.max(maxY, room.bottom());
        }

        minX -= 1;
        minY -= 1;
        maxX += 1;
        maxY += 1;
        return new BuildingBounds(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private List<String> collectUserTiles(BuildingBounds bounds) {
        List<String> userTiles = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (LayerKey key : getAllLayerKeys()) {
            for (int y = 0; y <= bounds.height; y++) {
                for (int x = 0; x <= bounds.width; x++) {
                    String tileName = tryGetTile(bounds.minX + x, bounds.minY + y, key.floor(), key.layer());
                    if (tileName != null && seen.add(tileName)) {
                        userTiles.add(tileName);
                    }
                }
            }
        }

        return userTiles;
    }

    private List<Integer> getFloorsWithTiles(BuildingBounds bounds) {
        List<Integer> floors = new ArrayList<>();
        Set<Integer> distinctFloors = new java.util.LinkedHashSet<>();
        for (LayerKey key : getAllLayerKeys()) {
            distinctFloors.add(key.floor());
        }

        for (int floor : distinctFloors) {
            for (int layer : getLayers(floor)) {
                if (hasAnyTile(bounds, floor, layer)) {
                    floors.add(floor);
                    break;
                }
            }
        }

        return floors;
    }

    private void writeTileLayers(TextFileWriter writer, BuildingBounds bounds, int floor, Map<String, Integer> userTileIds)
        throws IOException {
        for (int layer : getLayers(floor)) {
            if (!hasAnyTile(bounds, floor, layer)) {
                continue;
            }

            writer.writeLine("  <tiles layer=\"" + TmxWriter.getTbxLayerName(layer - 1) + "\">");
            writer.writeLine(TmxWriter.buildCsv(bounds.width + 1, bounds.height + 1, (x, y) -> {
                String tileName = tryGetTile(bounds.minX + x, bounds.minY + y, floor, layer);
                Integer id = tileName == null ? null : userTileIds.get(tileName);
                return id == null ? 0 : id;
            }));
            writer.writeLine("  </tiles>");
        }
    }

    private static String buildRoomCsv(BuildingBounds bounds, List<RoomRect> floorRooms, Map<Integer, Integer> roomIndexById) {
        return TmxWriter.buildCsv(bounds.width, bounds.height, (x, y) -> {
            int worldX = bounds.minX + x;
            int worldY = bounds.minY + y;
            for (RoomRect room : floorRooms) {
                if (worldX >= room.x() && worldX < room.right() && worldY >= room.y() && worldY < room.bottom()) {
                    return roomIndexById.get(room.sourceRoomId());
                }
            }

            return 0;
        });
    }

    private boolean hasAnyTile(BuildingBounds bounds, int floor, int layer) {
        for (int y = 0; y <= bounds.height; y++) {
            for (int x = 0; x <= bounds.width; x++) {
                if (tryGetTile(bounds.minX + x, bounds.minY + y, floor, layer) != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Returns the tile at a world square, or {@code null} when empty or filtered out. */
    private String tryGetTile(int worldX, int worldY, int floor, int layer) {
        CellCoord cellCoord = new CellCoord(
            BinaryHelpers.floorDiv(worldX, cellSize),
            BinaryHelpers.floorDiv(worldY, cellSize));

        TargetCell cell = targetCells.get(cellCoord);
        if (cell == null) {
            return null;
        }

        TileLayer tiles = cell.tilesByLayer().get(new LayerKey(floor, layer));
        if (tiles == null) {
            return null;
        }

        int localX = BinaryHelpers.positiveMod(worldX, cellSize);
        int localY = BinaryHelpers.positiveMod(worldY, cellSize);
        String tileName = tiles.get(localX + localY * cellSize);
        return tileName != null && TbxTileFilter.shouldInclude(tileName) ? tileName : null;
    }

    /** Every distinct (floor, layer) across all target cells, ordered by floor then layer. */
    private List<LayerKey> getAllLayerKeys() {
        if (allLayerKeys == null) {
            TreeSet<LayerKey> keys = new TreeSet<>(LayerKey.ORDER);
            for (TargetCell cell : targetCells.values()) {
                keys.addAll(cell.tilesByLayer().keySet());
            }

            allLayerKeys = List.copyOf(keys);
        }

        return allLayerKeys;
    }

    private List<Integer> getLayers(int floor) {
        List<Integer> layers = new ArrayList<>();
        for (LayerKey key : getAllLayerKeys()) {
            if (key.floor() == floor) {
                layers.add(key.layer());
            }
        }

        return layers;
    }

    /** Deterministic stand-in for the per-process randomized {@code HashCode.Combine} colour. */
    private static String getRoomColor(RoomRect room) {
        int hash = Objects.hash(room.name(), room.sourceRoomId(), room.floor());
        int r = 64 + (hash & 0x7f);
        int g = 64 + ((hash >> 8) & 0x7f);
        int b = 64 + ((hash >> 16) & 0x7f);
        return r + " " + g + " " + b;
    }

    private static String sanitizeFileName(String value) {
        String sanitized = Text.sanitizeFileName(value);
        return Text.isNullOrWhiteSpace(sanitized) ? "building" : sanitized;
    }

    private record BuildingBounds(int minX, int minY, int width, int height) {
    }
}
