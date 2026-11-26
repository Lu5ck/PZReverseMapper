package pzreversemapper.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pzreversemapper.converter.TbxModel.Building;
import pzreversemapper.converter.TbxModel.BuildingObject;
import pzreversemapper.converter.TbxModel.Door;
import pzreversemapper.converter.TbxModel.Floor;
import pzreversemapper.converter.TbxModel.Furniture;
import pzreversemapper.converter.TbxModel.FurnitureEntry;
import pzreversemapper.converter.TbxModel.Room;
import pzreversemapper.converter.TbxModel.Stairs;
import pzreversemapper.converter.TbxModel.TileEntry;
import pzreversemapper.converter.TbxModel.Window;

/**
 * Renders a {@link TbxModel.Building} into tiles the way WorldEd compiles a TBX lot: a port of
 * BuildingEd's {@code BuildingFloor::LayoutToSquares} (walls, wall trim, doors, windows, curtains,
 * shutters, stairs, furniture, floors, ceilings and grime) followed by BuildingMap's merge of
 * user-drawn tiles. Roof objects and wall-layer furniture are not modelled.
 */
final class TbxLayout {
    static final String[] SECTION_LAYERS = {
        "Floor", "FloorGrime", "FloorGrime2", "FloorFurniture", "Walls", "WallTrim", "Walls2", "WallTrim2", "RoofCap", "RoofCap2",
        "WallOverlay", "WallOverlay2", "WallGrime", "WallGrime2", "WallFurniture", "WallFurniture2", "Frames", "Doors",
        "Windows", "Curtains", "Furniture", "Furniture2", "Furniture3", "Furniture4", "Curtains2", "WallFurniture3",
        "WallFurniture4", "WallOverlay3", "WallOverlay4", "Roof", "Roof2", "RoofTop"
    };

    static final int FLOOR = 0;
    static final int FLOOR_GRIME = 1;
    static final int FLOOR_GRIME2 = 2;
    static final int FLOOR_FURNITURE = 3;
    static final int WALL = 4;
    static final int WALL_TRIM = 5;
    static final int WALL2 = 6;
    static final int WALL_TRIM2 = 7;
    static final int ROOF_CAP = 8;
    static final int ROOF_CAP2 = 9;
    static final int WALL_OVERLAY = 10;
    static final int WALL_OVERLAY2 = 11;
    static final int WALL_GRIME = 12;
    static final int WALL_GRIME2 = 13;
    static final int WALL_FURNITURE = 14;
    static final int WALL_FURNITURE2 = 15;
    static final int FRAME = 16;
    static final int DOOR = 17;
    static final int WINDOW = 18;
    static final int CURTAINS = 19;
    static final int FURNITURE = 20;
    static final int FURNITURE4 = 23;
    static final int CURTAINS2 = 24;
    static final int WALL_FURNITURE3 = 25;
    static final int WALL_FURNITURE4 = 26;
    static final int WALL_OVERLAY3 = 27;
    static final int WALL_OVERLAY4 = 28;
    static final int ROOF = 29;
    static final int ROOF2 = 30;
    static final int MAX_SECTION = 32;

    static final int ORIENT_INVALID = -1;
    static final int ORIENT_N = 0;
    static final int ORIENT_W = 1;
    static final int ORIENT_NW = 2;
    static final int ORIENT_SE = 3;

    /** BuildingEd's "none" entry: occupies a section without drawing anything. */
    static final TileEntry NONE = new TileEntry("none", -1);

    private final BuildingEdConfig config;

    TbxLayout(BuildingEdConfig config) {
        this.config = config;
    }

    /** Rendered tiles of one floor: per square, the tile in each TMXConfig layer (null when empty). */
    static final class RenderedFloor {
        final List<String> layers;
        final String[][][] tiles;

        RenderedFloor(List<String> layers, int width, int height) {
            this.layers = layers;
            this.tiles = new String[width][height][layers.size()];
        }

        /** Non-empty tiles of a square in layer order, as {layerIndex, tile}. */
        List<Object[]> stack(int x, int y) {
            List<Object[]> stack = new ArrayList<>();
            String[] square = tiles[x][y];
            for (int i = 0; i < square.length; i++) {
                if (square[i] != null) {
                    stack.add(new Object[] {i, square[i]});
                }
            }

            return stack;
        }
    }

    static boolean isNone(TileEntry entry) {
        return entry == null || entry == NONE || entry.tiles.isEmpty();
    }

    static boolean isWallWest(String e) {
        return e != null && e.startsWith("West");
    }

    static boolean isWallNorth(String e) {
        return e != null && e.startsWith("North") && !e.equals("NorthWest");
    }

    /** The first enum of a category, used where BuildingEd passes offset 0. */
    static String firstEnum(TileEntry entry) {
        if (entry == null || entry.tiles.isEmpty()) {
            return null;
        }

        return switch (entry.category) {
            case "floors" -> "Floor";
            case "ceiling" -> "Ceiling";
            default -> entry.tiles.keySet().iterator().next();
        };
    }

    /** Mutable per-square state, mirroring {@code BuildingFloor::Square}. */
    final class Square {
        final TileEntry[] entries = new TileEntry[MAX_SECTION];
        final String[] entryEnum = new String[MAX_SECTION];
        final String[] tiles = new String[MAX_SECTION];
        int wallOrientation = ORIENT_INVALID;
        boolean exterior = true;
        TileEntry wallNEntry;
        TileEntry wallWEntry;
        TileEntry wallNTrim;
        TileEntry wallWTrim;

        boolean isWallOrient(int orient) {
            return entries[WALL] != null && !isNone(entries[WALL]) && wallOrientation == orient;
        }

        void replaceFloor(TileEntry tile, String enumName) {
            entries[FLOOR] = tile;
            entryEnum[FLOOR] = enumName;
        }

        void replaceWall(TileEntry tile, int orient) {
            entries[WALL] = tile;
            wallOrientation = orient;
            entryEnum[WALL] = wallOffset(orient);
        }

        String wallOffset(int orient) {
            if (entries[WALL] == null) {
                return null;
            }

            return switch (orient) {
                case ORIENT_N -> {
                    if (entries[DOOR] != null && isDoorNorth(entryEnum[DOOR])) {
                        yield "NorthDoor";
                    }

                    if (entries[WINDOW] != null && "North".equals(entryEnum[WINDOW])) {
                        yield windowWallEnum(entries[WINDOW], "North");
                    }

                    yield "North";
                }
                case ORIENT_NW -> "NorthWest";
                case ORIENT_W -> {
                    if (entries[DOOR] != null && isDoorWest(entryEnum[DOOR])) {
                        yield "WestDoor";
                    }

                    if (entries[WINDOW] != null && "West".equals(entryEnum[WINDOW])) {
                        yield windowWallEnum(entries[WINDOW], "West");
                    }

                    yield "West";
                }
                case ORIENT_SE -> "SouthEast";
                default -> null;
            };
        }

        void replaceDoor(TileEntry tile, String offset) {
            entries[DOOR] = tile != null ? tile : NONE;
            entryEnum[DOOR] = offset;
            if (entries[WINDOW] != null && "West".equals(entryEnum[WINDOW]) == "West".equals(offset)) {
                entries[WINDOW] = null;
                entryEnum[WINDOW] = null;
            }

            if (wallOrientation == ORIENT_NW) {
                splitCorner("West".equals(offset), "West".equals(offset) ? "WestDoor" : "NorthDoor");
                return;
            }

            if (wallOrientation != ORIENT_INVALID) {
                entryEnum[WALL] = wallOffset(wallOrientation);
            }
        }

        void replaceFrame(TileEntry tile, String offset) {
            entries[FRAME] = tile != null ? tile : NONE;
            entryEnum[FRAME] = offset;
        }

        void replaceWindow(TileEntry tile, String offset) {
            entries[WINDOW] = tile != null ? tile : NONE;
            entryEnum[WINDOW] = offset;
            if (entries[DOOR] != null && "West".equals(entryEnum[DOOR]) == "West".equals(offset)) {
                entries[DOOR] = null;
                entryEnum[DOOR] = null;
            }

            if (wallOrientation == ORIENT_NW) {
                splitCorner("West".equals(offset), windowWallEnum(tile, offset));
                return;
            }

            if (wallOrientation != ORIENT_INVALID) {
                entryEnum[WALL] = wallOffset(wallOrientation);
            }
        }

        /** Shared NW-corner handling of ReplaceDoor/ReplaceWindow. */
        private void splitCorner(boolean west, String wallEnum) {
            TileEntry entry1 = entries[WALL] == null ? NONE : entries[WALL];
            TileEntry entry2 = entries[WALL2] == null ? NONE : entries[WALL2];
            if (!isNone(entry1) && !isNone(entry2)) {
                if (west) {
                    if (isWallWest(entryEnum[WALL])) {
                        entryEnum[WALL] = wallEnum;
                    } else if (isWallWest(entryEnum[WALL2])) {
                        entryEnum[WALL2] = wallEnum;
                    }
                } else {
                    if (isWallNorth(entryEnum[WALL])) {
                        entryEnum[WALL] = wallEnum;
                    } else if (isWallNorth(entryEnum[WALL2])) {
                        entryEnum[WALL2] = wallEnum;
                    }
                }
            } else {
                entries[WALL2] = entry1;
                entryEnum[WALL] = west ? "North" : "West";
                entryEnum[WALL2] = wallEnum;
            }
        }

        void replaceCurtains(Window window, boolean exteriorSide) {
            int section = exteriorSide ? CURTAINS2 : CURTAINS;
            entries[section] = window.curtains;
            entryEnum[section] = window.isW() ? (exteriorSide ? "East" : "West") : (exteriorSide ? "South" : "North");
        }

        void replaceShutters(Window window, boolean first) {
            if (isNone(window.shutters) || !exterior) {
                return;
            }

            String tile = window.isN()
                ? window.shutters.tile(first ? "NorthLeft" : "NorthRight")
                : window.shutters.tile(first ? "WestAbove" : "WestBelow");
            replaceFurnitureTile(tile, WALL_FURNITURE, WALL_FURNITURE2);
        }

        void replaceFurnitureTile(String tile, int sectionMin, int sectionMax) {
            if (tile == null) {
                return;
            }

            for (int s = sectionMin; s <= sectionMax; s++) {
                if (tiles[s] == null) {
                    tiles[s] = tile;
                    entryEnum[s] = null;
                    return;
                }
            }

            for (int s = sectionMin + 1; s <= sectionMax; s++) {
                tiles[s - 1] = tiles[s];
                entryEnum[s - 1] = entryEnum[s];
            }

            tiles[sectionMax] = tile;
            entryEnum[sectionMax] = null;
        }

        void replaceFloorGrime(TileEntry grime) {
            if (isNone(grime)) {
                return;
            }

            TileEntry wall1 = isNone(entries[WALL]) ? null : entries[WALL];
            TileEntry wall2 = isNone(entries[WALL2]) ? null : entries[WALL2];
            String grimeW = null;
            String grimeN = null;
            if (wall1 != null) {
                String e = entryEnum[WALL];
                switch (e == null ? "" : e) {
                    case "West", "WestWindow" -> grimeW = "West";
                    case "WestDoor", "NorthDoor" -> {
                    }
                    case "North", "NorthWindow" -> grimeN = "North";
                    case "NorthWest" -> {
                        grimeW = "West";
                        grimeN = "North";
                    }
                    case "SouthEast" -> {
                        if (wallOrientation == ORIENT_SE) {
                            grimeW = "NorthWest";
                        }
                    }
                    default -> {
                        if (isWallNorth(e)) {
                            grimeN = "North";
                        }

                        if (isWallWest(e)) {
                            grimeW = "West";
                        }
                    }
                }
            }

            if (wall2 != null) {
                String e = entryEnum[WALL2];
                switch (e == null ? "" : e) {
                    case "West", "WestWindow" -> grimeW = "West";
                    case "North", "NorthWindow" -> grimeN = "North";
                    case "WestDoor", "NorthDoor", "NorthWest", "SouthEast" -> {
                    }
                    default -> {
                        if (isWallNorth(e)) {
                            grimeN = "North";
                        }

                        if (isWallWest(e)) {
                            grimeW = "West";
                        }
                    }
                }
            }

            if (grimeW != null) {
                entries[FLOOR_GRIME] = grime;
                entryEnum[FLOOR_GRIME] = grimeW;
            }

            if (grimeN != null) {
                entries[FLOOR_GRIME2] = grime;
                entryEnum[FLOOR_GRIME2] = grimeN;
            }
        }

        void replaceWallGrime(TileEntry grime, String userTileWalls, String userTileWalls2) {
            if (isNone(grime)) {
                return;
            }

            TileEntry wall1 = isNone(entries[WALL]) ? null : entries[WALL];
            TileEntry wall2 = isNone(entries[WALL2]) ? null : entries[WALL2];
            String[] grimeEnum = {null, null};
            String[] tileW = {null};
            String[] tileN = {null};
            String[] tileNW = {null};
            if (wall1 != null) {
                wallGrime(wall1.tile(entryEnum[WALL]), entryEnum[WALL], grimeEnum, tileW, tileN, tileNW);
            }

            if (wall2 != null) {
                wallGrime(wall2.tile(entryEnum[WALL2]), entryEnum[WALL2], grimeEnum, tileW, tileN, tileNW);
            }

            String grimeW = grimeEnum[0];
            String grimeN = grimeEnum[1];
            GrimeProps props;
            if (tileNW[0] != null && (props = grimeProperties(tileNW[0])) != null) {
                if (props.fullWindow) {
                    grimeW = null;
                    grimeN = null;
                } else if (props.trim) {
                    grimeW = "NorthWestTrim";
                    grimeN = null;
                }
            }

            if (tileW[0] != null && (props = grimeProperties(tileW[0])) != null) {
                if (props.fullWindow) {
                    grimeW = null;
                } else if (props.trim) {
                    grimeW = "WestTrim";
                } else if (props.doubleLeft) {
                    grimeW = "WestDoubleLeft";
                } else if (props.doubleRight) {
                    grimeW = "WestDoubleRight";
                }
            }

            if (tileN[0] != null && (props = grimeProperties(tileN[0])) != null) {
                if (props.fullWindow) {
                    grimeN = null;
                } else if (props.trim) {
                    grimeN = "NorthTrim";
                } else if (props.doubleLeft) {
                    grimeN = "NorthDoubleLeft";
                } else if (props.doubleRight) {
                    grimeN = "NorthDoubleRight";
                }
            }

            for (String userTile : new String[] {userTileWalls, userTileWalls2}) {
                if (userTile == null || (props = grimeProperties(userTile)) == null) {
                    continue;
                }

                if (props.fullWindow) {
                    if (props.west) {
                        grimeW = null;
                    }

                    if (props.north) {
                        grimeN = null;
                    }
                } else if (props.trim) {
                    if (props.west && props.north) {
                        grimeW = "NorthWestTrim";
                        grimeN = null;
                    } else if (props.west) {
                        grimeW = "WestTrim";
                    } else if (props.north) {
                        grimeN = "NorthTrim";
                    } else if (props.southEast) {
                        grimeW = "SouthEastTrim";
                    }
                } else if (props.doubleLeft) {
                    if (props.west) {
                        grimeW = "WestDoubleLeft";
                    } else if (props.north) {
                        grimeN = "NorthDoubleLeft";
                    }
                } else if (props.doubleRight) {
                    if (props.west) {
                        grimeW = "WestDoubleRight";
                    } else if (props.north) {
                        grimeN = "NorthDoubleRight";
                    }
                } else if (props.west) {
                    grimeW = "West";
                } else if (props.north) {
                    grimeN = "North";
                }
            }

            if ("NorthWest".equals(grimeW) && grimeN != null) {
                grimeW = "West";
            }

            if ("NorthWestTrim".equals(grimeW) && grimeN != null) {
                grimeW = "WestTrim";
            }

            if (grimeW != null) {
                entries[WALL_GRIME] = grime;
                entryEnum[WALL_GRIME] = grimeW;
            }

            if (grimeN != null) {
                entries[WALL_GRIME2] = grime;
                entryEnum[WALL_GRIME2] = grimeN;
            }
        }

        private void wallGrime(String btile, String e, String[] grimeEnum, String[] tileW, String[] tileN, String[] tileNW) {
            switch (e == null ? "" : e) {
                case "West", "WestDoor", "WestWindow" -> {
                    tileW[0] = btile;
                    grimeEnum[0] = e;
                }
                case "North", "NorthDoor", "NorthWindow" -> {
                    tileN[0] = btile;
                    grimeEnum[1] = e;
                }
                case "NorthWest" -> {
                    tileNW[0] = btile;
                    grimeEnum[0] = "NorthWest";
                    grimeEnum[1] = null;
                }
                case "SouthEast" -> grimeEnum[0] = "SouthEast";
                default -> {
                    if (isWallWest(e)) {
                        tileW[0] = btile;
                        grimeEnum[0] = "WestWindow";
                    } else if (isWallNorth(e)) {
                        tileN[0] = btile;
                        grimeEnum[1] = "NorthWindow";
                    }
                }
            }
        }

        void replaceWallTrim() {
            TileEntry wall1 = isNone(entries[WALL]) ? null : entries[WALL];
            TileEntry wall2 = isNone(entries[WALL2]) ? null : entries[WALL2];
            if (wall1 == null && wall2 == null) {
                return;
            }

            TileEntry trimW = wallWTrim;
            TileEntry trimN = wallNTrim;
            if (trimW == null && trimN == null) {
                return;
            }

            String enumW = null;
            String enumN = null;
            if (wall1 != null) {
                if (isWallWest(entryEnum[WALL]) || "NorthWest".equals(entryEnum[WALL])) {
                    enumW = entryEnum[WALL];
                    if ("NorthWest".equals(enumW)) {
                        enumW = "West";
                        enumN = "North";
                    }
                } else {
                    enumN = entryEnum[WALL];
                }
            }

            int sectionW = WALL_TRIM2;
            int sectionN = WALL_TRIM;
            if (wall2 != null) {
                if (isWallWest(entryEnum[WALL2])) {
                    enumW = entryEnum[WALL2];
                } else {
                    enumN = entryEnum[WALL2];
                    int swap = sectionW;
                    sectionW = sectionN;
                    sectionN = swap;
                }
            }

            if ("West".equals(enumW) && "North".equals(enumN) && trimW == trimN) {
                if (sectionN == WALL_TRIM2) {
                    enumN = "NorthWest";
                    enumW = null;
                } else {
                    enumW = "NorthWest";
                    enumN = null;
                }
            }

            if (enumW != null) {
                entries[sectionW] = trimW;
                entryEnum[sectionW] = enumW;
            }

            if (enumN != null) {
                entries[sectionN] = trimN;
                entryEnum[sectionN] = enumN;
            }
        }
    }

    private static boolean isDoorWest(String e) {
        return "West".equals(e) || "WestOpen".equals(e);
    }

    private static boolean isDoorNorth(String e) {
        return "North".equals(e) || "NorthOpen".equals(e);
    }

    /** Port of {@code BTC_Windows::wallEnum}: the wall variant depends on the window tile's WindowShape. */
    String windowWallEnum(TileEntry window, String offset) {
        String fallback = "West".equals(offset) ? "WestWindow" : "NorthWindow";
        if (isNone(window)) {
            return fallback;
        }

        String shape = config.tileProperties(window.tile(offset)).get("WindowShape");
        if (shape == null) {
            return fallback;
        }

        Integer number = Text.tryParseInt(shape);
        if (number == null || number < 1 || number > 19 || !Integer.toString(number).equals(Text.trim(shape))) {
            return fallback;
        }

        return fallback + number;
    }

    private record GrimeProps(boolean west, boolean north, boolean southEast, boolean fullWindow, boolean trim,
                              boolean doubleLeft, boolean doubleRight) {
    }

    /** Port of {@code tileHasGrimeProperties}; null when the tile has no grime-related properties. */
    private GrimeProps grimeProperties(String tile) {
        Map<String, String> props = config.tileProperties(tile);
        if (props.isEmpty()) {
            return null;
        }

        if (props.containsKey("GrimeType")) {
            boolean west = false;
            boolean north = false;
            boolean southEast = false;
            if (props.containsKey("DoorWallW") || props.containsKey("WallW") || props.containsKey("WallWTrans") || props.containsKey("windowW")) {
                west = true;
            } else if (props.containsKey("DoorWallN") || props.containsKey("WallN") || props.containsKey("WallNTrans")
                || props.containsKey("windowN")) {
                north = true;
            } else if (props.containsKey("WallNW")) {
                west = true;
                north = true;
            } else if (props.containsKey("WallSE")) {
                southEast = true;
            }

            String type = props.get("GrimeType");
            return new GrimeProps(west, north, southEast, "FullWindow".equals(type), "Trim".equals(type),
                "DoubleLeft".equals(type), "DoubleRight".equals(type));
        }

        if (props.containsKey("WallW")) {
            return new GrimeProps(true, false, false, false, false, false, false);
        }

        if (props.containsKey("WallN")) {
            return new GrimeProps(false, true, false, false, false, false, false);
        }

        return null;
    }

    /** Lays out one floor and returns its tiles per TMXConfig layer, including user-drawn tiles. */
    RenderedFloor render(Building building, Floor floor) {
        Square[][] squares = layout(building, floor);
        List<String> layers = config.layers();
        int w = building.width + 1;
        int h = building.height + 1;
        RenderedFloor result = new RenderedFloor(layers, w, h);
        int[] sectionToLayer = new int[MAX_SECTION];
        for (int s = 0; s < MAX_SECTION; s++) {
            sectionToLayer[s] = layers.indexOf(SECTION_LAYERS[s]);
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Square square = squares[x][y];
                for (int s = 0; s < MAX_SECTION; s++) {
                    int layer = sectionToLayer[s];
                    if (layer < 0) {
                        continue;
                    }

                    String tile = square.tiles[s];
                    if (tile == null && square.entries[s] != null && !isNone(square.entries[s])) {
                        tile = square.entries[s].tile(square.entryEnum[s]);
                    }

                    if (tile != null) {
                        result.tiles[x][y][layer] = tile;
                    }
                }
            }
        }

        floor.userTiles.forEach((layerName, grid) -> {
            int layer = layers.indexOf(layerName);
            if (layer < 0) {
                return;
            }

            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    if (grid[x][y] != null) {
                        result.tiles[x][y][layer] = grid[x][y];
                    }
                }
            }
        });
        return result;
    }

    private Square[][] layout(Building building, Floor floor) {
        int width = building.width;
        int height = building.height;
        int w = width + 1;
        int h = height + 1;
        Square[][] squares = new Square[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                squares[x][y] = new Square();
            }
        }

        TileEntry exteriorWall = building.tile("ExteriorWall");
        TileEntry exteriorWallTrim = floor.index > 0 ? null : building.tile("ExteriorWallTrim");
        int[][] indexAt = new int[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Room room = building.roomAt(floor, x, y);
                if (room != null && room.isEmptyOutside()) {
                    room = null;
                }

                indexAt[x][y] = room == null ? -1 : floor.roomAt[x][y];
                squares[x][y].exterior = room == null;
            }
        }

        for (int x = 0; x <= width; x++) {
            for (int y = 0; y <= height; y++) {
                Square square = squares[x][y];
                if (x < width) {
                    if (y == height && height > 0 && indexAt[x][y - 1] >= 0) {
                        square.wallNEntry = exteriorWall;
                        square.wallNTrim = exteriorWallTrim;
                    } else if (y < height && indexAt[x][y] < 0 && y > 0 && indexAt[x][y - 1] != indexAt[x][y]) {
                        square.wallNEntry = exteriorWall;
                        square.wallNTrim = exteriorWallTrim;
                    } else if (y < height && (y == 0 || indexAt[x][y - 1] != indexAt[x][y]) && indexAt[x][y] >= 0) {
                        Room room = building.rooms.get(indexAt[x][y]);
                        square.wallNEntry = room.tile("InteriorWall");
                        square.wallNTrim = room.tile("InteriorWallTrim");
                    }
                }

                if (y < height) {
                    if (x == width && width > 0 && indexAt[x - 1][y] >= 0) {
                        square.wallWEntry = exteriorWall;
                        square.wallWTrim = exteriorWallTrim;
                    } else if (x < width && indexAt[x][y] < 0 && x > 0 && indexAt[x - 1][y] != indexAt[x][y]) {
                        square.wallWEntry = exteriorWall;
                        square.wallWTrim = exteriorWallTrim;
                    } else if (x < width && indexAt[x][y] >= 0 && (x == 0 || indexAt[x - 1][y] != indexAt[x][y])) {
                        Room room = building.rooms.get(indexAt[x][y]);
                        square.wallWEntry = room.tile("InteriorWall");
                        square.wallWTrim = room.tile("InteriorWallTrim");
                    }
                }
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Square s = squares[x][y];
                TileEntry wallN = s.wallNEntry;
                TileEntry wallW = s.wallWEntry;
                if (wallN != null || wallW != null) {
                    if (wallN == null) {
                        wallN = NONE;
                    }

                    if (wallW == null) {
                        wallW = NONE;
                    }

                    if (wallN == wallW) {
                        s.replaceWall(wallN, ORIENT_NW);
                    } else if (isNone(wallW)) {
                        s.replaceWall(wallN, ORIENT_N);
                    } else if (isNone(wallN)) {
                        s.replaceWall(wallW, ORIENT_W);
                    } else {
                        s.entries[WALL] = wallN;
                        s.wallOrientation = ORIENT_N;
                        s.entryEnum[WALL] = s.wallOffset(ORIENT_N);
                        s.entries[WALL2] = wallW;
                        s.wallOrientation = ORIENT_W;
                        s.entryEnum[WALL2] = s.wallOffset(ORIENT_W);
                        s.wallOrientation = ORIENT_NW;
                    }
                }
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Square sq = squares[x][y];
                if (sq.entries[WALL] != null && !isNone(sq.entries[WALL])) {
                    continue;
                }

                boolean aboveWest = y > 0 && (squares[x][y - 1].isWallOrient(ORIENT_W) || squares[x][y - 1].isWallOrient(ORIENT_NW));
                boolean leftNorth = x > 0 && (squares[x - 1][y].isWallOrient(ORIENT_N) || squares[x - 1][y].isWallOrient(ORIENT_NW));
                if (x > 0 && aboveWest && leftNorth) {
                    sq.replaceWall(squares[x][y - 1].wallWEntry, ORIENT_SE);
                } else if (aboveWest) {
                    sq.entries[WALL] = squares[x][y - 1].wallWEntry;
                    sq.entryEnum[WALL] = "SouthEast";
                    sq.wallOrientation = ORIENT_INVALID;
                } else if (leftNorth) {
                    sq.entries[WALL] = squares[x - 1][y].wallNEntry;
                    sq.entryEnum[WALL] = "SouthEast";
                    sq.wallOrientation = ORIENT_INVALID;
                }
            }
        }

        Floor below = building.floorBelow(floor);
        if (below != null) {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    Room room = building.roomAt(below, x, y);
                    if (room != null) {
                        TileEntry ceiling = room.tile("Ceiling");
                        squares[x][y].replaceFloor(ceiling, firstEnum(ceiling));
                    }
                }
            }
        }

        for (BuildingObject object : floor.objects) {
            int x = object.x;
            int y = object.y;
            if (object instanceof Door door) {
                if (x >= 0 && y >= 0 && x < w && y < h) {
                    squares[x][y].replaceDoor(door.tile, door.isW() ? "West" : "North");
                    squares[x][y].replaceFrame(door.frame, door.isW() ? "West" : "North");
                }
            } else if (object instanceof Window window) {
                placeWindow(window, squares, w, h);
            } else if (object instanceof Stairs stairs) {
                if (!isNone(stairs.tile)) {
                    for (int i = 1; i <= 3; i++) {
                        int sx = stairs.isN() ? x : x + i;
                        int sy = stairs.isN() ? y + i : y;
                        furniture(squares, width, height, sx, sy, stairs.tile.tile(stairs.offsetEnum(sx, sy)), FURNITURE, FURNITURE4, 0, 0);
                    }
                }
            } else if (object instanceof Furniture furniture) {
                placeFurniture(furniture, squares, width, height);
            }
        }

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (indexAt[x][y] >= 0) {
                    TileEntry floorTile = building.rooms.get(indexAt[x][y]).tile("Floor");
                    squares[x][y].replaceFloor(floorTile, firstEnum(floorTile));
                }
            }
        }

        if (below != null) {
            for (BuildingObject object : below.objects) {
                if (object instanceof Stairs stairs) {
                    int x = stairs.x;
                    int y = stairs.y;
                    if (stairs.isW() && !(x + 1 < 0 || x + 3 >= width || y < 0 || y >= height)) {
                        for (int i = 1; i <= 3; i++) {
                            squares[x + i][y].replaceFloor(null, null);
                        }
                    }

                    if (stairs.isN() && !(x < 0 || x >= width || y + 1 < 0 || y + 3 >= height)) {
                        for (int i = 1; i <= 3; i++) {
                            squares[x][y + i].replaceFloor(null, null);
                        }
                    }
                }
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Square sq = squares[x][y];
                sq.replaceWallTrim();
                if ("SouthEast".equals(sq.entryEnum[WALL])) {
                    if (y > 0 && squares[x][y - 1].wallWTrim != null) {
                        sq.entries[WALL_TRIM] = squares[x][y - 1].wallWTrim;
                        sq.entryEnum[WALL_TRIM] = "SouthEast";
                    } else if (x > 0 && squares[x - 1][y].wallNTrim != null) {
                        sq.entries[WALL_TRIM] = squares[x - 1][y].wallNTrim;
                        sq.entryEnum[WALL_TRIM] = "SouthEast";
                    }
                }

                String userWalls = floor.userTile("Walls", x, y);
                String userWalls2 = floor.userTile("Walls2", x, y);
                if (sq.exterior) {
                    if (floor.index > 0) {
                        continue;
                    }

                    sq.replaceWallGrime(building.tile("GrimeWall"), userWalls, userWalls2);
                } else {
                    Room room = building.roomAt(floor, x, y);
                    sq.replaceFloorGrime(room == null ? null : room.tile("GrimeFloor"));
                    sq.replaceWallGrime(room == null ? null : room.tile("GrimeWall"), userWalls, userWalls2);
                }
            }
        }

        return squares;
    }

    private void placeWindow(Window window, Square[][] squares, int w, int h) {
        int x = window.x;
        int y = window.y;
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }

        squares[x][y].replaceWindow(window.tile, window.isW() ? "West" : "North");
        if (squares[x][y].exterior) {
            int dx = window.isW() ? 1 : 0;
            int dy = window.isN() ? 1 : 0;
            if (x - dx >= 0 && y - dy >= 0) {
                squares[x - dx][y - dy].replaceCurtains(window, true);
            }
        } else {
            squares[x][y].replaceCurtains(window, false);
        }

        if (squares[x][y].exterior) {
            if (window.isN()) {
                if (x > 0) {
                    squares[x - 1][y].replaceShutters(window, true);
                }

                squares[x][y].replaceShutters(window, true);
                squares[x][y].replaceShutters(window, false);
                if (x < w - 1) {
                    squares[x + 1][y].replaceShutters(window, false);
                }
            } else {
                if (y > 0) {
                    squares[x][y - 1].replaceShutters(window, true);
                }

                squares[x][y].replaceShutters(window, true);
                squares[x][y].replaceShutters(window, false);
                if (y < h - 1) {
                    squares[x][y + 1].replaceShutters(window, false);
                }
            }
        }
    }

    private void placeFurniture(Furniture furniture, Square[][] squares, int width, int height) {
        FurnitureEntry entry = furniture.resolved();
        String layer = furniture.def.layer;
        if (layer.equals("Walls")) {
            return;
        }

        int dx = furniture.orient.equals("E") ? 1 : 0;
        int dy = furniture.orient.equals("S") ? 1 : 0;
        boolean westOrNorth = entry.isWestOrNorth();
        for (int i = 0; i < entry.height(); i++) {
            for (int j = 0; j < entry.width(); j++) {
                String tile = entry.tile(j, i);
                int x = furniture.x + j;
                int y = furniture.y + i;
                switch (layer) {
                    case "RoofCap" -> furniture(squares, width, height, x + dx, y + dy, tile, ROOF_CAP, ROOF_CAP2, dx, dy);
                    case "WallOverlay" -> furniture(squares, width, height, x, y, tile,
                        westOrNorth ? WALL_OVERLAY : WALL_OVERLAY3, westOrNorth ? WALL_OVERLAY2 : WALL_OVERLAY4, 0, 0);
                    case "WallFurniture" -> furniture(squares, width, height, x, y, tile,
                        westOrNorth ? WALL_FURNITURE : WALL_FURNITURE3, westOrNorth ? WALL_FURNITURE2 : WALL_FURNITURE4, 0, 0);
                    case "Frames" -> furniture(squares, width, height, x + dx, y + dy, tile, FRAME, FRAME, dx, dy);
                    case "Doors" -> furniture(squares, width, height, x + dx, y + dy, tile, DOOR, DOOR, dx, dy);
                    case "Roof" -> furniture(squares, width, height, x, y, tile, ROOF, ROOF2, 0, 0);
                    case "FloorFurniture" -> furniture(squares, width, height, x, y, tile, FLOOR_FURNITURE, FLOOR_FURNITURE, 0, 0);
                    default -> furniture(squares, width, height, x, y, tile, FURNITURE, FURNITURE4, 0, 0);
                }
            }
        }
    }

    /** Port of the static {@code ReplaceFurniture}: furniture stays inside the building, plus dw/dh. */
    private static void furniture(Square[][] squares, int width, int height, int x, int y, String tile, int min, int max, int dw, int dh) {
        if (tile == null) {
            return;
        }

        if (x >= 0 && y >= 0 && x < width + dw && y < height + dh) {
            squares[x][y].replaceFurnitureTile(tile, min, max);
        }
    }
}
