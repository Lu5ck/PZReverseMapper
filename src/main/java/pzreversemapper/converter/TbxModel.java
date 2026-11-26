package pzreversemapper.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory model of a BuildingEd {@code .tbx} building: tile entries, furniture, rooms and floors
 * with their objects and user-drawn tiles. Mirrors the structures read by BuildingEd's
 * {@code BuildingReader} so a reconstructed building can be rendered and written back.
 */
final class TbxModel {
    /** Building-level tile slots, in BuildingEd's {@code Building::enumToString} order. */
    static final String[] BUILDING_TILES = {
        "ExteriorWall", "ExteriorWallTrim", "Door", "DoorFrame", "Window", "Curtains", "Shutters", "Stairs",
        "RoofCap", "RoofSlope", "RoofTop", "GrimeWall"
    };

    /** Room-level tile slots, in BuildingEd's {@code Room::enumToString} order. */
    static final String[] ROOM_TILES = {"InteriorWall", "InteriorWallTrim", "Floor", "GrimeFloor", "GrimeWall", "Ceiling"};

    private TbxModel() {
    }

    /** Canonical tile key used for matching: {@code sheet_index} without zero padding. */
    static String canonicalTile(String name) {
        if (name == null) {
            return null;
        }

        String trimmed = Text.trim(name);
        if (trimmed.isEmpty()) {
            return null;
        }

        int index = trimmed.lastIndexOf('_');
        if (index <= 0 || index == trimmed.length() - 1) {
            return trimmed;
        }

        Integer id = Text.tryParseInt(trimmed.substring(index + 1));
        return id == null ? trimmed : trimmed.substring(0, index + 1) + id;
    }

    /** Tile name as BuildingEd writes it: {@code sheet_000}. */
    static String buildingEdTileName(String canonical) {
        int index = canonical.lastIndexOf('_');
        if (index <= 0 || index == canonical.length() - 1) {
            return canonical;
        }

        Integer id = Text.tryParseInt(canonical.substring(index + 1));
        return id == null ? canonical : canonical.substring(0, index + 1) + String.format(Locale.ROOT, "%03d", id);
    }

    /** A set of tiles for one BuildingTiles category (an exterior wall style, a door, a floor...). */
    static final class TileEntry {
        final String category;
        /** Enum name to canonical tile name, in file order. */
        final LinkedHashMap<String, String> tiles = new LinkedHashMap<>();
        /** Enum name to "x,y" offset. */
        final LinkedHashMap<String, int[]> offsets = new LinkedHashMap<>();
        /** Position in the configuration, or -1 for an entry reconstructed from observed tiles. */
        final int configIndex;

        TileEntry(String category, int configIndex) {
            this.category = category;
            this.configIndex = configIndex;
        }

        String tile(String enumName) {
            if (enumName == null) {
                return null;
            }

            String tile = tiles.get(enumName);
            return tile == null || tile.isEmpty() ? null : tile;
        }

        boolean isCustom() {
            return configIndex < 0;
        }

        String key() {
            return category + "|" + tiles + "|" + offsetsKey();
        }

        private String offsetsKey() {
            StringBuilder builder = new StringBuilder();
            offsets.forEach((name, offset) -> builder.append(name).append(offset[0]).append(',').append(offset[1]).append(';'));
            return builder.toString();
        }

        @Override
        public String toString() {
            return category + tiles;
        }
    }

    /** One orientation of a furniture definition: tiles at (x, y) offsets. */
    static final class FurnitureEntry {
        final String orient;
        boolean allowGrime = true;
        final LinkedHashMap<Point, String> tiles = new LinkedHashMap<>();

        FurnitureEntry(String orient) {
            this.orient = orient;
        }

        int width() {
            int width = 0;
            for (Point point : tiles.keySet()) {
                width = Math.max(width, point.x() + 1);
            }

            return width;
        }

        int height() {
            int height = 0;
            for (Point point : tiles.keySet()) {
                height = Math.max(height, point.y() + 1);
            }

            return height;
        }

        String tile(int x, int y) {
            return tiles.get(new Point(x, y));
        }

        boolean isEmpty() {
            return tiles.isEmpty();
        }

        boolean isWestOrNorth() {
            return orient.equals("W") || orient.equals("N") || orient.equals("NW");
        }
    }

    /** A furniture definition from BuildingFurniture.txt (one {@code furniture} block). */
    static final class FurnitureDef {
        static final String[] ORIENTS = {"W", "N", "E", "S", "SW", "NW", "NE", "SE"};

        final String layer;
        final boolean corners;
        final int configIndex;
        final String group;
        final LinkedHashMap<String, FurnitureEntry> entries = new LinkedHashMap<>();

        FurnitureDef(String layer, boolean corners, int configIndex, String group) {
            this.layer = layer == null || layer.isEmpty() ? "Furniture" : layer;
            this.corners = corners;
            this.configIndex = configIndex;
            this.group = group;
        }

        FurnitureEntry entry(String orient) {
            FurnitureEntry entry = entries.get(orient);
            return entry == null ? new FurnitureEntry(orient) : entry;
        }

        /** Port of {@code FurnitureTile::resolved()}: missing E/N/S orientations fall back. */
        FurnitureEntry resolved(String orient) {
            FurnitureEntry entry = entry(orient);
            if (!entry.isEmpty()) {
                return entry;
            }

            return switch (orient) {
                case "E", "N" -> entry("W");
                case "S" -> entry("N").isEmpty() ? entry("W") : entry("N");
                default -> entry;
            };
        }
    }

    record Point(int x, int y) {
    }

    /** A room: its names, colour and per-room tile entries. */
    static final class Room {
        final String name;
        final String internalName;
        final int[] color;
        final Map<String, TileEntry> tiles = new LinkedHashMap<>();
        final int sourceRoomId;

        Room(String name, String internalName, int[] color, int sourceRoomId) {
            this.name = name;
            this.internalName = internalName;
            this.color = color;
            this.sourceRoomId = sourceRoomId;
        }

        TileEntry tile(String slot) {
            return tiles.get(slot);
        }

        /** Port of {@code RoofHiding::isEmptyOutside}. */
        boolean isEmptyOutside() {
            String name = internalName;
            int hash = name.indexOf('#');
            return (hash >= 0 ? name.substring(0, hash) : name).equalsIgnoreCase("emptyoutside");
        }
    }

    /** Base class of floor objects. Coordinates are building-local squares. */
    abstract static class BuildingObject {
        final int x;
        final int y;
        /** "W" or "N" for doors, windows and stairs. */
        final String dir;

        BuildingObject(int x, int y, String dir) {
            this.x = x;
            this.y = y;
            this.dir = dir;
        }

        boolean isW() {
            return "W".equals(dir);
        }

        boolean isN() {
            return "N".equals(dir);
        }
    }

    static final class Door extends BuildingObject {
        final TileEntry tile;
        final TileEntry frame;

        Door(int x, int y, String dir, TileEntry tile, TileEntry frame) {
            super(x, y, dir);
            this.tile = tile;
            this.frame = frame;
        }
    }

    static final class Window extends BuildingObject {
        final TileEntry tile;
        final TileEntry curtains;
        final TileEntry shutters;

        Window(int x, int y, String dir, TileEntry tile, TileEntry curtains, TileEntry shutters) {
            super(x, y, dir);
            this.tile = tile;
            this.curtains = curtains;
            this.shutters = shutters;
        }
    }

    static final class Stairs extends BuildingObject {
        final TileEntry tile;

        Stairs(int x, int y, String dir, TileEntry tile) {
            super(x, y, dir);
            this.tile = tile;
        }

        /** Port of {@code Stairs::getOffset}: enum for a square of the 5-square stair run, or null. */
        String offsetEnum(int sx, int sy) {
            if (isN() && sx == x) {
                return switch (sy - y) {
                    case 1 -> "North3";
                    case 2 -> "North2";
                    case 3 -> "North1";
                    default -> null;
                };
            }

            if (isW() && sy == y) {
                return switch (sx - x) {
                    case 1 -> "West3";
                    case 2 -> "West2";
                    case 3 -> "West1";
                    default -> null;
                };
            }

            return null;
        }
    }

    static final class Furniture extends BuildingObject {
        final FurnitureDef def;
        final String orient;

        Furniture(int x, int y, FurnitureDef def, String orient) {
            super(x, y, null);
            this.def = def;
            this.orient = orient;
        }

        FurnitureEntry resolved() {
            return def.resolved(orient);
        }
    }

    /** One building floor: room grid, objects and user-drawn tiles per layer. */
    static final class Floor {
        final int index;
        final int worldLevel;
        /** Room index (into {@link Building#rooms}) per square, or -1. Size width x height. */
        final int[][] roomAt;
        final List<BuildingObject> objects = new ArrayList<>();
        /** Layer name to user tile grid of size (width + 1) x (height + 1); null cells are empty. */
        final LinkedHashMap<String, String[][]> userTiles = new LinkedHashMap<>();

        Floor(int index, int worldLevel, int width, int height) {
            this.index = index;
            this.worldLevel = worldLevel;
            this.roomAt = new int[width][height];
            for (int[] column : roomAt) {
                java.util.Arrays.fill(column, -1);
            }
        }

        String userTile(String layer, int x, int y) {
            String[][] grid = userTiles.get(layer);
            return grid == null ? null : grid[x][y];
        }

        void setUserTile(String layer, int x, int y, String tile, int width, int height) {
            userTiles.computeIfAbsent(layer, ignored -> new String[width + 1][height + 1])[x][y] = tile;
        }
    }

    /** A whole building. */
    static final class Building {
        final int width;
        final int height;
        final Map<String, TileEntry> tiles = new LinkedHashMap<>();
        final List<Room> rooms = new ArrayList<>();
        final List<Floor> floors = new ArrayList<>();
        final LinkedHashMap<String, String> properties = new LinkedHashMap<>();

        Building(int width, int height) {
            this.width = width;
            this.height = height;
        }

        TileEntry tile(String slot) {
            return tiles.get(slot);
        }

        Room roomAt(Floor floor, int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return null;
            }

            int index = floor.roomAt[x][y];
            return index < 0 ? null : rooms.get(index);
        }

        Floor floorBelow(Floor floor) {
            return floor.index > 0 ? floors.get(floor.index - 1) : null;
        }
    }
}
