package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes TMX map cells: tile layers, RoomDefs and the building lots placed from {@code tbx_buildings/}.
 * Tiles and rooms drawn by a lot are left out, so WorldEd does not stack them twice.
 */
final class TmxWriter {
    private final String outputDirectory;
    private final int cellSize;
    private final TileSetCatalog tileSets;
    private final boolean useLevelAttributes;

    TmxWriter(String outputDirectory, int cellSize, TileSetCatalog tileSets, boolean useLevelAttributes) {
        this.outputDirectory = outputDirectory;
        this.cellSize = cellSize;
        this.tileSets = tileSets;
        this.useLevelAttributes = useLevelAttributes;
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
            writeLots(writer, cell, levelAttributes);
            writer.writeLine("</map>");
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
            writeCsv(writer, cell, key, entry.getValue());
            writer.newLine();
            writer.writeLine("  </data>");
            writer.writeLine(" </layer>");
        }
    }

    private void writeCsv(TextFileWriter writer, TargetCell cell, LayerKey key, TileLayer tiles) throws IOException {
        boolean lotTiles = cell.hasLotTiles(key);
        StringBuilder row = new StringBuilder(cellSize * 6);
        boolean first = true;
        for (int y = 0; y < cellSize; y++) {
            row.setLength(0);
            for (int x = 0; x < cellSize; x++) {
                if (!first) {
                    row.append(',');
                }

                int index = x + y * cellSize;
                String tileName = tiles.get(index);
                row.append(tileName == null || (lotTiles && cell.coveredByLot(key, index)) ? 0 : tileSets.getGid(tileName));
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
            for (RoomRect room : floorGroup.getValue()) {
                String name = Text.xmlEscape(room.name());
                writer.writeLine("  <object name=\"" + name + "\" type=\"\" x=\"" + (room.x() * 64) + "\" y=\""
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

    /** One {@code Lots} object group per level, holding WorldEd lot objects that reference shared TBX files. */
    private void writeLots(TextFileWriter writer, TargetCell cell, boolean levelAttributes) throws IOException {
        TreeMap<Integer, List<TargetCell.Lot>> lotsByLevel = new TreeMap<>();
        for (TargetCell.Lot lot : cell.lots()) {
            lotsByLevel.computeIfAbsent(lot.level(), level -> new ArrayList<>()).add(lot);
        }

        for (Map.Entry<Integer, List<TargetCell.Lot>> level : lotsByLevel.entrySet()) {
            writer.writeLine(" <objectgroup name=\"" + (levelAttributes ? "Lots" : level.getKey() + "_Lots") + "\""
                + getLevelAttribute(level.getKey(), levelAttributes) + " width=\"" + cellSize + "\" height=\"" + cellSize + "\">");
            for (TargetCell.Lot lot : level.getValue()) {
                writer.writeLine("  <object name=\"lot\" type=\"" + Text.xmlEscape(lot.tbxPath()) + "\" x=\"" + (lot.x() * 64)
                    + "\" y=\"" + (lot.y() * 32) + "\" width=\"" + (lot.width() * 64) + "\" height=\"" + (lot.height() * 32) + "\"/>");
            }

            writer.writeLine(" </objectgroup>");
        }
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

        for (TargetCell.Lot lot : cell.lots()) {
            if (lot.level() < 0) {
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
