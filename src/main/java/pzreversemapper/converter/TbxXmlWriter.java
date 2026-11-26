package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import pzreversemapper.converter.TbxModel.Building;
import pzreversemapper.converter.TbxModel.BuildingObject;
import pzreversemapper.converter.TbxModel.Door;
import pzreversemapper.converter.TbxModel.Floor;
import pzreversemapper.converter.TbxModel.Furniture;
import pzreversemapper.converter.TbxModel.FurnitureDef;
import pzreversemapper.converter.TbxModel.FurnitureEntry;
import pzreversemapper.converter.TbxModel.Room;
import pzreversemapper.converter.TbxModel.Stairs;
import pzreversemapper.converter.TbxModel.TileEntry;
import pzreversemapper.converter.TbxModel.Window;

/** Writes a {@link TbxModel.Building} in the format of BuildingEd's {@code BuildingWriter} (version 4). */
final class TbxXmlWriter {
    private static final String NL = TextFileWriter.NEW_LINE;

    private final Building building;
    private final List<TileEntry> entries = new ArrayList<>();
    private final Map<TileEntry, Integer> entryIndex = new IdentityHashMap<>();
    private final List<FurnitureDef> furniture = new ArrayList<>();
    private final Map<FurnitureDef, Integer> furnitureIndex = new IdentityHashMap<>();
    private final List<String> userTiles;

    private TbxXmlWriter(Building building) {
        this.building = building;
        collectEntries();
        collectFurniture();
        TreeSet<String> names = new TreeSet<>();
        for (Floor floor : building.floors) {
            for (String[][] grid : floor.userTiles.values()) {
                for (String[] column : grid) {
                    for (String tile : column) {
                        if (tile != null) {
                            names.add(TbxModel.buildingEdTileName(tile));
                        }
                    }
                }
            }
        }

        userTiles = new ArrayList<>(names);
    }

    static void write(Building building, Path file) throws IOException {
        new TbxXmlWriter(building).write(file);
    }

    private void collectEntries() {
        List<TileEntry> all = new ArrayList<>();
        all.addAll(building.tiles.values());
        for (Room room : building.rooms) {
            all.addAll(room.tiles.values());
        }

        for (Floor floor : building.floors) {
            for (BuildingObject object : floor.objects) {
                if (object instanceof Door door) {
                    all.add(door.tile);
                    all.add(door.frame);
                } else if (object instanceof Window window) {
                    all.add(window.tile);
                    all.add(window.curtains);
                    all.add(window.shutters);
                } else if (object instanceof Stairs stairs) {
                    all.add(stairs.tile);
                }
            }
        }

        for (TileEntry entry : all) {
            if (entry != null && !TbxLayout.isNone(entry) && !entryIndex.containsKey(entry)) {
                entryIndex.put(entry, -1);
                entries.add(entry);
            }
        }

        entries.sort(Comparator.comparing((TileEntry entry) -> entry.category)
            .thenComparing(entry -> entry.isCustom() ? Integer.MAX_VALUE : entry.configIndex)
            .thenComparing(TileEntry::key));
        for (int i = 0; i < entries.size(); i++) {
            entryIndex.put(entries.get(i), i + 1);
        }
    }

    private void collectFurniture() {
        for (Floor floor : building.floors) {
            for (BuildingObject object : floor.objects) {
                if (object instanceof Furniture item && !furnitureIndex.containsKey(item.def)) {
                    furnitureIndex.put(item.def, furniture.size());
                    furniture.add(item.def);
                }
            }
        }
    }

    private String index(TileEntry entry) {
        Integer index = entry == null ? null : entryIndex.get(entry);
        return index == null || index < 0 ? "0" : index.toString();
    }

    private void write(Path file) throws IOException {
        try (TextFileWriter out = new TextFileWriter(file)) {
            out.writeLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            StringBuilder start = new StringBuilder("<building version=\"4\" width=\"" + building.width + "\" height=\"" + building.height + "\"");
            for (String slot : TbxModel.BUILDING_TILES) {
                start.append(' ').append(slot).append("=\"").append(index(building.tile(slot))).append('"');
            }

            out.writeLine(start + ">");
            writeProperties(out);
            for (TileEntry entry : entries) {
                writeEntry(out, entry);
            }

            for (FurnitureDef def : furniture) {
                writeFurniture(out, def);
            }

            out.writeLine(" <user_tiles>");
            for (String tile : userTiles) {
                out.writeLine("  <tile tile=\"" + Text.xmlEscape(tile) + "\"/>");
            }

            out.writeLine(" </user_tiles>");
            List<String> used = new ArrayList<>();
            for (int i = 1; i <= entries.size(); i++) {
                used.add(Integer.toString(i));
            }

            out.writeLine(" <used_tiles>" + String.join(" ", used) + "</used_tiles>");
            List<String> usedFurniture = new ArrayList<>();
            for (int i = 0; i < furniture.size(); i++) {
                usedFurniture.add(Integer.toString(i));
            }

            out.writeLine(" <used_furniture>" + String.join(" ", usedFurniture) + "</used_furniture>");
            for (Room room : building.rooms) {
                StringBuilder line = new StringBuilder(" <room Name=\"" + Text.xmlEscape(room.name) + "\" InternalName=\""
                    + Text.xmlEscape(room.internalName) + "\" Color=\"" + room.color[0] + " " + room.color[1] + " " + room.color[2] + "\"");
                for (String slot : TbxModel.ROOM_TILES) {
                    line.append(' ').append(slot).append("=\"").append(index(room.tile(slot))).append('"');
                }

                out.writeLine(line + "/>");
            }

            for (Floor floor : building.floors) {
                writeFloor(out, floor);
            }

            out.writeLine("</building>");
        }
    }

    private void writeProperties(TextFileWriter out) throws IOException {
        if (building.properties.isEmpty()) {
            return;
        }

        out.writeLine(" <properties>");
        for (Map.Entry<String, String> property : building.properties.entrySet()) {
            out.writeLine("  <property name=\"" + Text.xmlEscape(property.getKey()) + "\" value=\"" + Text.xmlEscape(property.getValue()) + "\"/>");
        }

        out.writeLine(" </properties>");
    }

    private void writeEntry(TextFileWriter out, TileEntry entry) throws IOException {
        out.writeLine(" <tile_entry category=\"" + Text.xmlEscape(entry.category) + "\">");
        for (Map.Entry<String, String> tile : entry.tiles.entrySet()) {
            String name = tile.getValue().isEmpty() ? "" : TbxModel.buildingEdTileName(tile.getValue());
            int[] offset = entry.offsets.get(tile.getKey());
            String offsetText = offset == null || (offset[0] == 0 && offset[1] == 0) ? "" : " offset=\"" + offset[0] + "," + offset[1] + "\"";
            out.writeLine("  <tile enum=\"" + Text.xmlEscape(tile.getKey()) + "\" tile=\"" + Text.xmlEscape(name) + "\"" + offsetText + "/>");
        }

        out.writeLine(" </tile_entry>");
    }

    private void writeFurniture(TextFileWriter out, FurnitureDef def) throws IOException {
        StringBuilder start = new StringBuilder(" <furniture");
        if (def.corners) {
            start.append(" corners=\"true\"");
        }

        if (!def.layer.equals("Furniture")) {
            start.append(" layer=\"").append(Text.xmlEscape(def.layer)).append('"');
        }

        out.writeLine(start + ">");
        List<String> orients = new ArrayList<>(List.of("W", "N", "E", "S"));
        if (def.corners) {
            orients.addAll(List.of("SW", "NW", "NE", "SE"));
        }

        for (String orient : orients) {
            FurnitureEntry entry = def.entries.get(orient);
            if (entry == null || entry.isEmpty()) {
                continue;
            }

            out.writeLine("  <entry orient=\"" + orient + "\"" + (entry.allowGrime ? "" : " grime=\"false\"") + ">");
            Map<TbxModel.Point, String> sorted = new LinkedHashMap<>();
            for (int x = 0; x < entry.width(); x++) {
                for (int y = 0; y < entry.height(); y++) {
                    String tile = entry.tile(x, y);
                    if (tile != null) {
                        sorted.put(new TbxModel.Point(x, y), tile);
                    }
                }
            }

            for (Map.Entry<TbxModel.Point, String> tile : sorted.entrySet()) {
                out.writeLine("   <tile x=\"" + tile.getKey().x() + "\" y=\"" + tile.getKey().y() + "\" name=\""
                    + Text.xmlEscape(TbxModel.buildingEdTileName(tile.getValue())) + "\"/>");
            }

            out.writeLine("  </entry>");
        }

        out.writeLine(" </furniture>");
    }

    private void writeFloor(TextFileWriter out, Floor floor) throws IOException {
        out.writeLine(" <floor>");
        for (BuildingObject object : floor.objects) {
            out.writeLine("  " + objectXml(object));
        }

        StringBuilder rooms = new StringBuilder(NL);
        int count = 0;
        int max = building.width * building.height;
        for (int y = 0; y < building.height; y++) {
            for (int x = 0; x < building.width; x++) {
                rooms.append(floor.roomAt[x][y] + 1);
                if (++count < max) {
                    rooms.append(',');
                }
            }

            rooms.append(NL);
        }

        out.writeLine("  <rooms>" + rooms + "</rooms>");
        StringBuilder attributes = new StringBuilder(NL);
        count = 0;
        for (int y = 0; y < building.height; y++) {
            for (int x = 0; x < building.width; x++) {
                attributes.append('0');
                if (++count < max) {
                    attributes.append(',');
                }
            }

            attributes.append(NL);
        }

        out.writeLine("  <attributes>" + attributes + "</attributes>");
        for (Map.Entry<String, String[][]> layer : floor.userTiles.entrySet()) {
            String[][] grid = layer.getValue();
            StringBuilder text = new StringBuilder(NL);
            count = 0;
            int cells = (building.width + 1) * (building.height + 1);
            boolean any = false;
            for (int y = 0; y <= building.height; y++) {
                for (int x = 0; x <= building.width; x++) {
                    String tile = grid[x][y];
                    if (tile == null) {
                        text.append('0');
                    } else {
                        text.append(userTiles.indexOf(TbxModel.buildingEdTileName(tile)) + 1);
                        any = true;
                    }

                    if (++count < cells) {
                        text.append(',');
                    }
                }

                text.append(NL);
            }

            if (any) {
                out.writeLine("  <tiles layer=\"" + Text.xmlEscape(layer.getKey()) + "\">" + text + "</tiles>");
            }
        }

        out.writeLine(" </floor>");
    }

    private String objectXml(BuildingObject object) {
        String position = " x=\"" + object.x + "\" y=\"" + object.y + "\"";
        if (object instanceof Door door) {
            return "<object type=\"door\" FrameTile=\"" + index(door.frame) + "\"" + position + " dir=\"" + door.dir + "\" Tile=\"" + index(door.tile) + "\"/>";
        }

        if (object instanceof Window window) {
            return "<object type=\"window\" CurtainsTile=\"" + index(window.curtains) + "\" ShuttersTile=\"" + index(window.shutters) + "\""
                + position + " dir=\"" + window.dir + "\" Tile=\"" + index(window.tile) + "\"/>";
        }

        if (object instanceof Stairs stairs) {
            return "<object type=\"stairs\"" + position + " dir=\"" + stairs.dir + "\" Tile=\"" + index(stairs.tile) + "\"/>";
        }

        Furniture item = (Furniture) object;
        return "<object type=\"furniture\" FurnitureTiles=\"" + furnitureIndex.get(item.def) + "\" orient=\"" + item.orient + "\"" + position + "/>";
    }
}
