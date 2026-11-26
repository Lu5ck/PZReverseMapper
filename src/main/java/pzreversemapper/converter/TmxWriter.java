package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Writes TMX map cells and the RoomDef TBX files they reference. */
final class TmxWriter {
    private final String outputDirectory;
    private final int cellSize;
    private final TileSetCatalog tileSets;
    private final boolean exportRoomTbx;
    private final boolean useLevelAttributes;
    private int tbxCount;

    TmxWriter(String outputDirectory, int cellSize, TileSetCatalog tileSets, boolean exportRoomTbx, boolean useLevelAttributes) {
        this.outputDirectory = outputDirectory;
        this.cellSize = cellSize;
        this.tileSets = tileSets;
        this.exportRoomTbx = exportRoomTbx;
        this.useLevelAttributes = useLevelAttributes;
    }

    int tbxCount() {
        return tbxCount;
    }

    void write(TargetCell cell) throws IOException {
        Path tmxDirectory = Paths.get(outputDirectory, "tmx");
        Files.createDirectories(tmxDirectory);

        Path file = tmxDirectory.resolve(cell.coord().x() + "_" + cell.coord().y() + ".tmx");
        boolean levelAttributes = useLevelAttributes || shouldUseLevelAttributes(cell);
        try (TextFileWriter writer = new TextFileWriter(file)) {
            writer.writeLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.writeLine("<map version=\"" + (levelAttributes ? "2.0" : "1.0") + "\" orientation=\"levelisometric\" width=\""
                + cellSize + "\" height=\"" + cellSize + "\" tilewidth=\"64\" tileheight=\"32\">");
            writeTileSets(writer);
            writeLayers(writer, cell, levelAttributes);
            writeRoomDefs(writer, cell, levelAttributes);
            writer.writeLine("</map>");
        }
    }

    void writeRoomTbxOnly(TargetCell cell) throws IOException {
        if (!exportRoomTbx) {
            return;
        }

        for (List<RoomRect> floorGroup : getRoomsByFloor(cell).values()) {
            int counter = 0;
            for (RoomRect room : floorGroup) {
                writeTbx(cell, room, counter++);
            }
        }
    }

    private void writeTileSets(TextFileWriter writer) throws IOException {
        for (TileSetCatalog.TileSetDefinition tileSet : tileSets.tileSets()) {
            String name = Text.xmlEscape(tileSet.name);
            writer.writeLine(" <tileset firstgid=\"" + tileSet.firstGid + "\" name=\"" + name + "\" tilewidth=\"64\" tileheight=\"128\">");
            writer.writeLine("  <image source=\"../../../Tiles/" + name + ".png\" width=\"" + (64 * tileSet.width)
                + "\" height=\"" + (128 * tileSet.height) + "\"/>");
            writer.writeLine(" </tileset>");
        }
    }

    private void writeLayers(TextFileWriter writer, TargetCell cell, boolean levelAttributes) throws IOException {
        for (Map.Entry<LayerKey, TileLayer> entry : cell.orderedLayers()) {
            LayerKey key = entry.getKey();
            String layerName = getLayerName(key.floor(), key.layer());
            writer.writeLine(" <layer name=\"" + getTmxLayerName(key.floor(), layerName, levelAttributes) + "\""
                + getLevelAttribute(key.floor(), levelAttributes) + " width=\"" + cellSize + "\" height=\"" + cellSize + "\">");
            writer.writeLine("  <data encoding=\"csv\">");
            writeCsv(writer, entry.getValue());
            writer.newLine();
            writer.writeLine("  </data>");
            writer.writeLine(" </layer>");
        }
    }

    private void writeCsv(TextFileWriter writer, TileLayer tiles) throws IOException {
        StringBuilder row = new StringBuilder(cellSize * 6);
        boolean first = true;
        for (int y = 0; y < cellSize; y++) {
            row.setLength(0);
            for (int x = 0; x < cellSize; x++) {
                if (!first) {
                    row.append(',');
                }

                String tileName = tiles.get(x + y * cellSize);
                row.append(tileName == null ? 0 : tileSets.getGid(tileName));
                first = false;
            }

            if (y < cellSize - 1) {
                row.append(TextFileWriter.NEW_LINE);
            }

            writer.write(row.toString());
        }
    }

    private void writeRoomDefs(TextFileWriter writer, TargetCell cell, boolean levelAttributes) throws IOException {
        for (Map.Entry<Integer, List<RoomRect>> floorGroup : getRoomsByFloor(cell).entrySet()) {
            int floor = floorGroup.getKey();
            writer.writeLine(" <objectgroup name=\"" + getRoomDefsGroupName(floor, levelAttributes) + "\""
                + getLevelAttribute(floor, levelAttributes) + " width=\"" + cellSize + "\" height=\"" + cellSize + "\">");
            int counter = 0;
            for (RoomRect room : floorGroup.getValue()) {
                String type = exportRoomTbx ? Text.xmlEscape(writeTbx(cell, room, counter++)) : "";
                String name = Text.xmlEscape(room.name());
                writer.writeLine("  <object name=\"" + name + "\" type=\"" + type + "\" x=\"" + (room.x() * 64) + "\" y=\""
                    + (room.y() * 32) + "\" width=\"" + (room.width() * 64) + "\" height=\"" + (room.height() * 32) + "\"/>");
            }

            writer.writeLine(" </objectgroup>");
        }
    }

    /** Rooms with a positive size, grouped by floor (ascending), keeping their original order. */
    private static TreeMap<Integer, List<RoomRect>> getRoomsByFloor(TargetCell cell) {
        TreeMap<Integer, List<RoomRect>> groups = new TreeMap<>();
        for (RoomRect room : cell.rooms()) {
            if (room.width() > 0 && room.height() > 0) {
                groups.computeIfAbsent(room.floor(), floor -> new ArrayList<>()).add(room);
            }
        }

        return groups;
    }

    private String writeTbx(TargetCell cell, RoomRect room, int counter) throws IOException {
        String cellName = cell.coord().x() + "_" + cell.coord().y();
        Path tbxDirectory = Paths.get(outputDirectory, "tmx", "tbx", cellName);
        Files.createDirectories(tbxDirectory);

        String safeRoomName = Text.sanitizeFileName(room.name());
        String fileName = cellName + "_" + room.floor() + "_" + safeRoomName + "_" + room.x() + "_" + room.y() + "_" + counter + ".tbx";
        Path fullPath = tbxDirectory.resolve(fileName);
        List<String> userTiles = collectUserTiles(cell, room);
        Map<String, Integer> userTileIds = new HashMap<>();
        for (int i = 0; i < userTiles.size(); i++) {
            userTileIds.put(userTiles.get(i), i + 1);
        }

        TreeSet<Integer> floors = getFloorsWithTiles(cell, room);
        if (floors.isEmpty()) {
            floors.add(room.floor());
        }

        try (TextFileWriter writer = new TextFileWriter(fullPath)) {
            writer.writeLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.writeLine("<building version=\"4\" width=\"" + room.width() + "\" height=\"" + room.height()
                + "\" ExteriorWall=\"0\" ExteriorWallTrim=\"0\" Door=\"0\" DoorFrame=\"0\" Window=\"0\" Curtains=\"0\" Shutters=\"0\""
                + " Stairs=\"0\" RoofCap=\"0\" RoofSlope=\"0\" RoofTop=\"0\" GrimeWall=\"0\">");
            writer.writeLine("<properties>");
            writer.writeLine("<property name=\"Legend\" value=\"Residential\"/>");
            writer.writeLine("</properties>");
            writer.writeLine(" <user_tiles>");
            for (String tile : userTiles) {
                writer.writeLine("  <tile tile=\"" + Text.xmlEscape(tile) + "\"/>");
            }

            writer.writeLine(" </user_tiles>");
            writer.writeLine(" <used_tiles></used_tiles>");
            writer.writeLine(" <used_furniture></used_furniture>");
            writer.writeLine(" <room Name=\"" + room.floor() + "_" + Text.xmlEscape(room.name()) + "\" InternalName=\""
                + Text.xmlEscape(room.name()) + "\" Color=\"" + getRoomColor(room)
                + "\" InteriorWall=\"0\" InteriorWallTrim=\"0\" Floor=\"0\" GrimeFloor=\"0\" GrimeWall=\"0\"/>");

            for (int floor : floors) {
                writer.writeLine(" <floor>");
                writer.writeLine("  <rooms>");
                writer.writeLine(buildCsv(room.width(), room.height(), (x, y) -> floor == room.floor() ? 1 : 0));
                writer.writeLine("  </rooms>");
                writeTbxTileLayers(writer, cell, room, floor, userTileIds);
                writer.writeLine(" </floor>");
            }

            writer.writeLine("</building>");
        }

        tbxCount++;
        return ".\\tbx\\" + cellName + "\\" + fileName;
    }

    private List<String> collectUserTiles(TargetCell cell, RoomRect room) {
        List<String> userTiles = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Map.Entry<LayerKey, TileLayer> entry : cell.orderedLayers()) {
            TileLayer tiles = entry.getValue();
            for (int y = 0; y <= room.height(); y++) {
                for (int x = 0; x <= room.width(); x++) {
                    String tileName = tryGetTile(tiles, room.x() + x, room.y() + y);
                    if (tileName != null && seen.add(tileName)) {
                        userTiles.add(tileName);
                    }
                }
            }
        }

        return userTiles;
    }

    private TreeSet<Integer> getFloorsWithTiles(TargetCell cell, RoomRect room) {
        TreeSet<Integer> floors = new TreeSet<>();
        for (Map.Entry<LayerKey, TileLayer> entry : cell.tilesByLayer().entrySet()) {
            if (hasAnyTileInTileArea(entry.getValue(), room)) {
                floors.add(entry.getKey().floor());
            }
        }

        return floors;
    }

    private boolean hasAnyTileInTileArea(TileLayer tiles, RoomRect room) {
        for (int y = 0; y <= room.height(); y++) {
            for (int x = 0; x <= room.width(); x++) {
                if (tryGetTile(tiles, room.x() + x, room.y() + y) != null) {
                    return true;
                }
            }
        }

        return false;
    }

    private void writeTbxTileLayers(TextFileWriter writer, TargetCell cell, RoomRect room, int floor, Map<String, Integer> userTileIds)
        throws IOException {
        for (Map.Entry<LayerKey, TileLayer> entry : cell.orderedLayers()) {
            LayerKey key = entry.getKey();
            if (key.floor() != floor || !hasAnyTileInTileArea(entry.getValue(), room)) {
                continue;
            }

            TileLayer tiles = entry.getValue();
            writer.writeLine("  <tiles layer=\"" + getTbxLayerName(key.layer() - 1) + "\">");
            writer.writeLine(buildCsv(room.width() + 1, room.height() + 1, (x, y) -> {
                String tileName = tryGetTile(tiles, room.x() + x, room.y() + y);
                Integer id = tileName == null ? null : userTileIds.get(tileName);
                return id == null ? 0 : id;
            }));
            writer.writeLine("  </tiles>");
        }
    }

    /** Returns the tile at a cell-local square, or {@code null} when empty, out of range or filtered. */
    private String tryGetTile(TileLayer tiles, int localX, int localY) {
        if (localX < 0 || localY < 0 || localX >= cellSize || localY >= cellSize) {
            return null;
        }

        String tileName = tiles.get(localX + localY * cellSize);
        return tileName != null && TbxTileFilter.shouldInclude(tileName) ? tileName : null;
    }

    @FunctionalInterface
    interface CsvValue {
        int valueAt(int x, int y);
    }

    static String buildCsv(int width, int height, CsvValue valueAt) {
        StringBuilder builder = new StringBuilder(Math.max(16, width * height * 2));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                builder.append(valueAt.valueAt(x, y));
                if (x != width - 1 || y != height - 1) {
                    builder.append(',');
                }
            }

            if (y < height - 1) {
                builder.append(TextFileWriter.NEW_LINE);
            }
        }

        return builder.toString();
    }

    static String getTbxLayerName(int layer) {
        return switch (layer) {
            case 0 -> "Floor";
            case 1 -> "FloorOverlay";
            case 2 -> "FloorGrime";
            case 3 -> "FloorGrime2";
            case 4 -> "FloorFurniture";
            case 5 -> "Vegetation";
            case 6 -> "Walls";
            case 7 -> "WallTrim";
            case 8 -> "Walls2";
            case 9 -> "WallTrim2";
            case 10 -> "RoofCap";
            case 11 -> "RoofCap2";
            case 12 -> "WallOverlay";
            case 13 -> "WallOverlay2";
            case 14 -> "WallGrime";
            case 15 -> "WallGrime2";
            case 16 -> "WallFurniture";
            case 17 -> "WallFurniture2";
            case 18 -> "Frames";
            case 19 -> "Doors";
            case 20 -> "Windows";
            case 21 -> "Curtains";
            case 22 -> "Furniture";
            case 23 -> "Furniture2";
            case 24 -> "Furniture3";
            case 25 -> "Furniture4";
            case 26 -> "Curtains2";
            case 27 -> "WallFurniture3";
            case 28 -> "WallFurniture4";
            case 29 -> "WallOverlay3";
            case 30 -> "WallOverlay4";
            case 31 -> "Roof";
            case 32 -> "Roof2";
            case 33 -> "RoofTop";
            default -> "Custom_" + layer;
        };
    }

    /** Deterministic stand-in for the per-process randomized {@code HashCode.Combine} colour. */
    private static String getRoomColor(RoomRect room) {
        int hash = Objects.hash(room.name(), room.floor(), room.width(), room.height());
        int r = 64 + (hash & 0x7f);
        int g = 64 + ((hash >> 8) & 0x7f);
        int b = 64 + ((hash >> 16) & 0x7f);
        return r + " " + g + " " + b;
    }

    private static String getLayerName(int floor, int layer) {
        if (floor <= 0 && layer == 1) {
            return "Floor";
        }

        if (floor == 0 && layer == 6) {
            return "Vegetation";
        }

        if ((floor <= 0 && layer == 2) || (floor > 0 && layer == 1)) {
            return "FloorOverlay";
        }

        return "FloorOverlay" + (layer - 1);
    }

    private static boolean shouldUseLevelAttributes(TargetCell cell) {
        for (LayerKey key : cell.tilesByLayer().keySet()) {
            if (key.floor() < 0) {
                return true;
            }
        }

        for (RoomRect room : cell.rooms()) {
            if (room.floor() < 0) {
                return true;
            }
        }

        return false;
    }

    private static String getTmxLayerName(int floor, String layerName, boolean levelAttributes) {
        return levelAttributes ? layerName : floor + "_" + layerName;
    }

    private static String getRoomDefsGroupName(int floor, boolean levelAttributes) {
        return levelAttributes ? "RoomDefs" : floor + "_RoomDefs";
    }

    private static String getLevelAttribute(int floor, boolean levelAttributes) {
        return levelAttributes ? " level=\"" + floor + "\"" : "";
    }
}
