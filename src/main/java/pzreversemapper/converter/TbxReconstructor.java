package pzreversemapper.converter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pzreversemapper.converter.BuildingEdConfig.EntryUse;
import pzreversemapper.converter.BuildingEdConfig.FurnitureUse;
import pzreversemapper.converter.TbxLayout.RenderedFloor;
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

/**
 * Rebuilds a BuildingEd building from compiled tiles. Rooms come from the lotheader RoomDefs; tile
 * entries (walls, trims, floors, ceilings, grime) are chosen per owner by majority vote against the
 * BuildingEd configuration; doors, windows, stairs and furniture become objects; whatever the
 * rebuilt building does not reproduce is kept as user-drawn tiles. The result is checked by
 * re-rendering it with {@link TbxLayout}.
 */
final class TbxReconstructor {
    private static final String[] WALL_CATEGORIES = {"exterior_walls", "interior_walls"};
    private static final String[] TRIM_CATEGORIES = {"exterior_wall_trim", "interior_wall_trim"};
    /**
     * An entry is only used when it matches at least this share of the places BuildingEd would draw
     * it: generated tiles cannot be removed afterwards, so weak evidence must not add walls or grime.
     */
    private static final double MIN_COVERAGE = 0.5;

    private final BuildingEdConfig config;
    private final TbxLayout layout;
    private final Map<String, TileEntry> customEntries = new HashMap<>();

    /** Supplies the compiled tile stack of a world square, bottom to top. */
    @FunctionalInterface
    interface StackSource {
        List<String> stack(int worldX, int worldY, int level);
    }

    /** How well the rebuilt building reproduces the compiled tiles. */
    static final class Stats {
        /** Compiled building tiles the rebuilt TBX renders exactly (including user tiles). */
        int reproduced;
        /** Compiled tiles kept as user-drawn tiles because no BuildingEd rule produces them. */
        int userTiles;
        /** Compiled building tiles the rebuilt TBX does not render. */
        int unmatched;
        /** Tiles the rebuilt TBX renders that are not in the compiled data. */
        int extra;
        /** Map ground and vegetation under the building's exterior squares, left to the map. */
        int mapTiles;
        int doors;
        int windows;
        int stairs;
        int furniture;

        void add(Stats other) {
            reproduced += other.reproduced;
            userTiles += other.userTiles;
            unmatched += other.unmatched;
            extra += other.extra;
            mapTiles += other.mapTiles;
            doors += other.doors;
            windows += other.windows;
            stairs += other.stairs;
            furniture += other.furniture;
        }

        int buildingTiles() {
            return reproduced + unmatched;
        }
    }

    /**
     * A rebuilt building. {@code reproduced[floor][x][y][i]} tells whether the {@code i}-th tile of the
     * compiled stack at building square (x, y) of that floor is drawn by the rebuilt building.
     */
    record Result(Building building, Stats stats, int originX, int originY, int baseLevel, boolean[][][][] reproduced) {
    }

    TbxReconstructor(BuildingEdConfig config) {
        this.config = config;
        this.layout = new TbxLayout(config);
    }

    /** Rooms of one compiled building, in world coordinates. */
    Result reconstruct(List<RoomRect> roomRects, StackSource source, int maxExtraLevels) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minLevel = Integer.MAX_VALUE;
        int maxLevel = Integer.MIN_VALUE;
        for (RoomRect room : roomRects) {
            minX = Math.min(minX, room.x());
            minY = Math.min(minY, room.y());
            maxX = Math.max(maxX, room.right());
            maxY = Math.max(maxY, room.bottom());
            minLevel = Math.min(minLevel, room.floor());
            maxLevel = Math.max(maxLevel, room.floor());
        }

        int width = maxX - minX;
        int height = maxY - minY;
        int originX = minX;
        int originY = minY;

        // Floors above the top room hold ceilings, roofs and other building tiles.
        for (int extra = 0; extra < maxExtraLevels; extra++) {
            if (!hasTiles(source, originX, originY, width, height, maxLevel + 1)) {
                break;
            }

            maxLevel++;
        }

        Building building = new Building(width, height);
        Map<Integer, Integer> roomIndexById = new LinkedHashMap<>();
        // Rooms are numbered by position, not by compiled id, so identical buildings give identical files.
        List<RoomRect> ordered = new ArrayList<>(roomRects);
        ordered.sort(Comparator.comparingInt(RoomRect::floor)
            .thenComparingInt(RoomRect::y)
            .thenComparingInt(RoomRect::x)
            .thenComparing(RoomRect::name, Text.CULTURE_ORDER)
            .thenComparingInt(RoomRect::sourceRoomId));
        for (RoomRect rect : ordered) {
            roomIndexById.computeIfAbsent(rect.sourceRoomId(), id -> {
                building.rooms.add(new Room(rect.name(), rect.name(), roomColor(rect), id));
                return building.rooms.size() - 1;
            });
        }

        List<List<String>[][]> observed = new ArrayList<>();
        for (int level = minLevel; level <= maxLevel; level++) {
            Floor floor = new Floor(level - minLevel, level, width, height);
            for (RoomRect rect : roomRects) {
                if (rect.floor() != level) {
                    continue;
                }

                int index = roomIndexById.get(rect.sourceRoomId());
                for (int x = rect.x() - originX; x < rect.right() - originX; x++) {
                    for (int y = rect.y() - originY; y < rect.bottom() - originY; y++) {
                        floor.roomAt[x][y] = index;
                    }
                }
            }

            building.floors.add(floor);
            @SuppressWarnings("unchecked")
            List<String>[][] stacks = new List[width + 1][height + 1];
            for (int x = 0; x <= width; x++) {
                for (int y = 0; y <= height; y++) {
                    List<String> stack = source.stack(originX + x, originY + y, level);
                    List<String> canonical = new ArrayList<>(stack.size());
                    for (String tile : stack) {
                        canonical.add(TbxModel.canonicalTile(tile));
                    }

                    stacks[x][y] = canonical;
                }
            }

            observed.add(stacks);
        }

        Stats stats = new Stats();
        // Without tile entries or furniture (no BuildingEd config) the building keeps its rooms and
        // every compiled tile as a user tile.
        boolean rules = config.entryCount() > 0 || config.furnitureCount() > 0;
        if (rules) {
            List<WallSlot> slots = chooseWalls(building, observed);
            chooseFloorsAndCeilings(building, observed);
            chooseGrime(building, observed, slots);
            addDoorsAndWindows(building, observed, stats);
            addStairs(building, observed, stats);
            addFurniture(building, observed, stats);
            chooseFurnitureLayers(building, observed);
            chooseBuildingDefaults(building);
        }

        keepUnexplainedTiles(building, observed, stats);
        // Furniture whose layer breaks the compiled stacking order is drawn twice: by the furniture
        // object and by the user tile that restores the order. Such furniture becomes user tiles.
        for (int pass = 0; pass < 4 && dropMisorderedFurniture(building, observed, stats); pass++) {
            for (Floor floor : building.floors) {
                floor.userTiles.clear();
            }

            stats.userTiles = 0;
            keepUnexplainedTiles(building, observed, stats);
        }

        if (rules) {
            chooseRoofDefaults(building);
        }
        boolean[][][][] reproduced = verify(building, observed, stats);
        return new Result(building, stats, originX, originY, minLevel, reproduced);
    }

    private static boolean hasTiles(StackSource source, int originX, int originY, int width, int height, int level) {
        for (int x = 0; x <= width; x++) {
            for (int y = 0; y <= height; y++) {
                for (String tile : source.stack(originX + x, originY + y, level)) {
                    if (TbxTileFilter.shouldInclude(tile)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ---------------------------------------------------------------- walls

    /** A wall slot produced by BuildingEd's own rules for a square: owner placeholder + enum. */
    private record WallSlot(int floor, int x, int y, Object owner, String enumName) {
    }

    /**
     * Runs the layout with one placeholder entry per wall owner (the building exterior, each room) so
     * that BuildingEd's wall rules tell exactly which wall pieces each owner draws, then votes.
     */
    private List<WallSlot> chooseWalls(Building building, List<List<String>[][]> observed) {
        Map<Object, TileEntry> placeholders = new LinkedHashMap<>();
        TileEntry exterior = placeholder("exterior_walls", "ext");
        placeholders.put("ext", exterior);
        building.tiles.put("ExteriorWall", exterior);
        for (int i = 0; i < building.rooms.size(); i++) {
            TileEntry interior = placeholder("interior_walls", "room" + i);
            placeholders.put(i, interior);
            building.rooms.get(i).tiles.put("InteriorWall", interior);
        }

        List<WallSlot> slots = wallSlots(building, placeholders);
        Map<Object, TileEntry> walls = new HashMap<>();
        Map<Object, TileEntry> trims = new HashMap<>();
        for (Object owner : placeholders.keySet()) {
            String category = owner.equals("ext") ? WALL_CATEGORIES[0] : WALL_CATEGORIES[1];
            String trimCategory = owner.equals("ext") ? TRIM_CATEGORIES[0] : TRIM_CATEGORIES[1];
            Map<TileEntry, Integer> wallVotes = new HashMap<>();
            Map<TileEntry, Integer> wallHits = new HashMap<>();
            Map<TileEntry, Integer> trimVotes = new HashMap<>();
            Map<TileEntry, Integer> trimHits = new HashMap<>();
            int wallSlots = 0;
            int trimSlots = 0;
            for (WallSlot slot : slots) {
                if (!slot.owner().equals(owner)) {
                    continue;
                }

                List<String> stack = observed.get(slot.floor())[slot.x()][slot.y()];
                wallSlots++;
                vote(wallVotes, wallHits, stack, category, slot.enumName());
                if (!owner.equals("ext") || slot.floor() == 0) {
                    trimSlots++;
                    vote(trimVotes, trimHits, stack, trimCategory, slot.enumName());
                }
            }

            TileEntry wall = covered(winner(wallVotes), wallHits, wallSlots);
            if (wall == null && wallVotes.isEmpty()) {
                wall = customWall(category, owner, slots, observed, wallSlots);
            }

            walls.put(owner, wall);
            trims.put(owner, covered(winner(trimVotes), trimHits, trimSlots));
        }

        building.tiles.put("ExteriorWall", walls.get("ext"));
        building.tiles.put("ExteriorWallTrim", trims.get("ext"));
        for (int i = 0; i < building.rooms.size(); i++) {
            building.rooms.get(i).tiles.put("InteriorWall", walls.get(i));
            building.rooms.get(i).tiles.put("InteriorWallTrim", trims.get(i));
        }

        return slots;
    }

    /** The entry if it matched at least {@link #MIN_COVERAGE} of its slots, otherwise null. */
    private static TileEntry covered(TileEntry entry, Map<TileEntry, Integer> hits, int slots) {
        if (entry == null || slots == 0) {
            return null;
        }

        return hits.getOrDefault(entry, 0) >= MIN_COVERAGE * slots ? entry : null;
    }

    private static TileEntry placeholder(String category, String name) {
        TileEntry entry = new TileEntry(category, -1);
        for (String e : new String[] {"West", "North", "NorthWest", "SouthEast"}) {
            entry.tiles.put(e, "?" + name + ":" + e);
        }

        return entry;
    }

    private List<WallSlot> wallSlots(Building building, Map<Object, TileEntry> placeholders) {
        Map<String, Object> ownerByTile = new HashMap<>();
        placeholders.forEach((owner, entry) -> entry.tiles.values().forEach(tile -> ownerByTile.put(tile, owner)));
        List<WallSlot> slots = new ArrayList<>();
        for (Floor floor : building.floors) {
            RenderedFloor rendered = layout.render(building, floor);
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    for (String tile : rendered.tiles[x][y]) {
                        if (tile != null && tile.startsWith("?")) {
                            Object owner = ownerByTile.get(tile);
                            slots.add(new WallSlot(floor.index, x, y, owner, tile.substring(tile.lastIndexOf(':') + 1)));
                        }
                    }
                }
            }
        }

        return slots;
    }

    /** Wall enums that can appear where BuildingEd places a wall of the given base enum. */
    private static boolean wallEnumFits(String baseEnum, String observedEnum) {
        return switch (baseEnum) {
            case "West" -> TbxLayout.isWallWest(observedEnum);
            case "North" -> TbxLayout.isWallNorth(observedEnum);
            case "NorthWest" -> observedEnum.equals("NorthWest") || TbxLayout.isWallWest(observedEnum) || TbxLayout.isWallNorth(observedEnum);
            default -> observedEnum.equals(baseEnum);
        };
    }

    private void vote(Map<TileEntry, Integer> votes, Map<TileEntry, Integer> hits, List<String> stack, String category, String baseEnum) {
        Map<TileEntry, Integer> best = new HashMap<>();
        for (String tile : stack) {
            for (EntryUse use : config.entriesUsing(tile)) {
                if (use.entry().category.equals(category) && wallEnumFits(baseEnum, use.enumName())) {
                    int weight = use.enumName().equals(baseEnum) ? 2 : 1;
                    best.merge(use.entry(), weight, Math::max);
                }
            }
        }

        best.forEach((entry, weight) -> {
            votes.merge(entry, weight, Integer::sum);
            hits.merge(entry, 1, Integer::sum);
        });
    }

    private static TileEntry winner(Map<TileEntry, Integer> votes) {
        TileEntry best = null;
        int bestVotes = 0;
        for (Map.Entry<TileEntry, Integer> vote : votes.entrySet()) {
            TileEntry entry = vote.getKey();
            int count = vote.getValue();
            if (count > bestVotes || (count == bestVotes && best != null && entry.configIndex < best.configIndex)) {
                best = entry;
                bestVotes = count;
            }
        }

        return best;
    }

    /**
     * Builds a wall entry for walls that are not in BuildingTiles.txt, using the game tile properties
     * (WallW, WallN, WallNW, WallSE) to recognise which tile of a stack is the wall.
     */
    private TileEntry customWall(String category, Object owner, List<WallSlot> slots, List<List<String>[][]> observed, int slotCount) {
        Map<String, Map<String, Integer>> tilesByEnum = new LinkedHashMap<>();
        int matched = 0;
        for (WallSlot slot : slots) {
            if (!slot.owner().equals(owner)) {
                continue;
            }

            for (String tile : observed.get(slot.floor())[slot.x()][slot.y()]) {
                Map<String, String> props = config.tileProperties(tile);
                String e = props.containsKey("WallNW") ? "NorthWest"
                    : props.containsKey("WallW") ? "West"
                    : props.containsKey("WallN") ? "North"
                    : props.containsKey("WallSE") ? "SouthEast" : null;
                if (e != null && e.equals(slot.enumName())) {
                    tilesByEnum.computeIfAbsent(e, ignored -> new HashMap<>()).merge(tile, 1, Integer::sum);
                    matched++;
                    break;
                }
            }
        }

        if (tilesByEnum.isEmpty() || matched < MIN_COVERAGE * slotCount) {
            return null;
        }

        TileEntry entry = new TileEntry(category, -1);
        for (String e : new String[] {"West", "North", "NorthWest", "SouthEast"}) {
            Map<String, Integer> counts = tilesByEnum.get(e);
            if (counts != null) {
                entry.tiles.put(e, majority(counts));
            }
        }

        return intern(entry);
    }

    // ------------------------------------------------------------ floors

    private void chooseFloorsAndCeilings(Building building, List<List<String>[][]> observed) {
        for (int r = 0; r < building.rooms.size(); r++) {
            Room room = building.rooms.get(r);
            Map<String, Integer> floorVotes = new HashMap<>();
            Map<String, Integer> ceilingVotes = new HashMap<>();
            int floorSquares = 0;
            int ceilingSquares = 0;
            for (Floor floor : building.floors) {
                Floor above = floor.index + 1 < building.floors.size() ? building.floors.get(floor.index + 1) : null;
                for (int x = 0; x < building.width; x++) {
                    for (int y = 0; y < building.height; y++) {
                        if (floor.roomAt[x][y] != r) {
                            continue;
                        }

                        floorSquares++;
                        String floorTile = floorCandidate(observed.get(floor.index)[x][y], "floors");
                        if (floorTile != null && !room.isEmptyOutside()) {
                            floorVotes.merge(floorTile, 1, Integer::sum);
                        }

                        if (above != null && above.roomAt[x][y] < 0) {
                            ceilingSquares++;
                            String ceilingTile = floorCandidate(observed.get(above.index)[x][y], "ceiling");
                            if (ceilingTile != null) {
                                ceilingVotes.merge(ceilingTile, 1, Integer::sum);
                            }
                        }
                    }
                }
            }

            // BuildingEd lays the room's floor on every square (and the ceiling above it), so the
            // chosen tile must cover most of them.
            room.tiles.put("Floor", singleTileEntry("floors", "Floor", majorityCovering(floorVotes, floorSquares)));
            room.tiles.put("Ceiling", singleTileEntry("ceiling", "Ceiling", majorityCovering(ceilingVotes, ceilingSquares)));
        }
    }

    /** Categories whose tiles are never drawn in the Floor layer. */
    private static final java.util.Set<String> NON_FLOOR_CATEGORIES = java.util.Set.of(
        "exterior_walls", "interior_walls", "exterior_wall_trim", "interior_wall_trim", "doors", "door_frames",
        "windows", "curtains", "shutters", "stairs", "grime_floor", "grime_wall", "roof_caps", "roof_slopes");

    /**
     * The bottom tile of a stack, which is the Floor layer tile unless the square has no floor and the
     * bottom tile is clearly a wall, door, window, stair, grime or furniture tile.
     */
    private String floorCandidate(List<String> stack, String category) {
        if (stack.isEmpty()) {
            return null;
        }

        String tile = stack.get(0);
        if (config.isInCategory(tile, category) || config.isInCategory(tile, "floors")) {
            return tile;
        }

        for (EntryUse use : config.entriesUsing(tile)) {
            if (NON_FLOOR_CATEGORIES.contains(use.entry().category)) {
                return null;
            }
        }

        Map<String, String> props = config.tileProperties(tile);
        if (props.containsKey("WallW") || props.containsKey("WallN") || props.containsKey("WallNW") || props.containsKey("WallSE")) {
            return null;
        }

        for (FurnitureUse use : config.furnitureUsing(tile)) {
            if (!use.def().layer.equals("FloorFurniture")) {
                return null;
            }
        }

        return tile;
    }

    /** A config entry whose only tile is {@code tile} in the given enum, or a custom one-tile entry. */
    private TileEntry singleTileEntry(String category, String enumName, String tile) {
        if (tile == null) {
            return null;
        }

        TileEntry best = null;
        for (EntryUse use : config.entriesUsing(tile)) {
            if (use.entry().category.equals(category) && use.enumName().equals(enumName)
                && (best == null || use.entry().configIndex < best.configIndex)) {
                best = use.entry();
            }
        }

        if (best != null) {
            return best;
        }

        TileEntry entry = new TileEntry(category, -1);
        entry.tiles.put(enumName, tile);
        return intern(entry);
    }

    // ------------------------------------------------------------- grime

    /**
     * Wall grime follows walls and floor grime follows walls inside rooms, so grime entries are voted
     * on the owner's wall squares: the building exterior on the bottom floor, and each room.
     */
    private void chooseGrime(Building building, List<List<String>[][]> observed, List<WallSlot> slots) {
        Map<Object, Map<TileEntry, Integer>> wallGrime = new HashMap<>();
        Map<Object, Map<TileEntry, Integer>> floorGrime = new HashMap<>();
        Map<Object, Integer> squares = new HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WallSlot slot : slots) {
            boolean exterior = slot.owner().equals("ext");
            if (exterior && slot.floor() != 0) {
                continue;
            }

            if (!seen.add(slot.owner() + "@" + slot.floor() + "," + slot.x() + "," + slot.y())) {
                continue;
            }

            squares.merge(slot.owner(), 1, Integer::sum);
            List<String> stack = observed.get(slot.floor())[slot.x()][slot.y()];
            countCategory(wallGrime.computeIfAbsent(slot.owner(), ignored -> new HashMap<>()), stack, "grime_wall");
            if (!exterior) {
                countCategory(floorGrime.computeIfAbsent(slot.owner(), ignored -> new HashMap<>()), stack, "grime_floor");
            }
        }

        building.tiles.put("GrimeWall", coveredVotes(wallGrime.get("ext"), squares.get("ext")));
        for (int r = 0; r < building.rooms.size(); r++) {
            building.rooms.get(r).tiles.put("GrimeWall", coveredVotes(wallGrime.get(r), squares.get(r)));
            building.rooms.get(r).tiles.put("GrimeFloor", coveredVotes(floorGrime.get(r), squares.get(r)));
        }
    }

    private static TileEntry coveredVotes(Map<TileEntry, Integer> votes, Integer squares) {
        if (votes == null || squares == null) {
            return null;
        }

        return covered(winner(votes), votes, squares);
    }

    private void countCategory(Map<TileEntry, Integer> votes, List<String> stack, String category) {
        java.util.Set<TileEntry> seen = new java.util.HashSet<>();
        for (String tile : stack) {
            for (EntryUse use : config.entriesUsing(tile)) {
                if (use.entry().category.equals(category) && seen.add(use.entry())) {
                    votes.merge(use.entry(), 1, Integer::sum);
                }
            }
        }
    }

    // ----------------------------------------------------- doors, windows

    private void addDoorsAndWindows(Building building, List<List<String>[][]> observed, Stats stats) {
        for (Floor floor : building.floors) {
            List<String>[][] stacks = observed.get(floor.index);
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    for (String dir : new String[] {"W", "N"}) {
                        TileEntry wall = wallEntryFor(building, floor, x, y, dir);
                        if (wall == null) {
                            continue;
                        }

                        String side = dir.equals("W") ? "West" : "North";
                        List<String> stack = stacks[x][y];
                        String doorWall = wall.tile(side + "Door");
                        if (doorWall != null && stack.contains(doorWall)) {
                            TileEntry door = findEntry(stack, "doors", side, side + "Open");
                            TileEntry frame = findEntry(stack, "door_frames", side);
                            floor.objects.add(new Door(x, y, dir, door, frame));
                            stats.doors++;
                            continue;
                        }

                        if (hasWindowVariant(wall, side, stack)) {
                            TileEntry window = findEntry(stack, "windows", side);
                            boolean exteriorSquare = building.roomAt(floor, x, y) == null
                                || building.roomAt(floor, x, y).isEmptyOutside();
                            TileEntry curtains;
                            if (exteriorSquare) {
                                int cx = dir.equals("W") ? x - 1 : x;
                                int cy = dir.equals("N") ? y - 1 : y;
                                curtains = cx >= 0 && cy >= 0 ? findEntry(stacks[cx][cy], "curtains", dir.equals("W") ? "East" : "South") : null;
                            } else {
                                curtains = findEntry(stack, "curtains", side);
                            }

                            TileEntry shutters = exteriorSquare
                                ? findEntry(stack, "shutters", dir.equals("W") ? "WestAbove" : "NorthLeft", dir.equals("W") ? "WestBelow" : "NorthRight")
                                : null;
                            floor.objects.add(new Window(x, y, dir, window, curtains, shutters));
                            stats.windows++;
                        }
                    }
                }
            }
        }
    }

    /** The wall entry BuildingEd places on the given side of a square, or null. */
    private TileEntry wallEntryFor(Building building, Floor floor, int x, int y, String dir) {
        int width = building.width;
        int height = building.height;
        int here = indexAt(building, floor, x, y);
        if (dir.equals("N")) {
            if (x >= width) {
                return null;
            }

            int above = y > 0 ? indexAt(building, floor, x, y - 1) : -1;
            if (y == height) {
                return above >= 0 ? building.tile("ExteriorWall") : null;
            }

            if (here < 0 && y > 0 && above != here) {
                return building.tile("ExteriorWall");
            }

            if ((y == 0 || above != here) && here >= 0) {
                return building.rooms.get(here).tile("InteriorWall");
            }

            return null;
        }

        if (y >= height) {
            return null;
        }

        int left = x > 0 ? indexAt(building, floor, x - 1, y) : -1;
        if (x == width) {
            return left >= 0 ? building.tile("ExteriorWall") : null;
        }

        if (here < 0 && x > 0 && left != here) {
            return building.tile("ExteriorWall");
        }

        if (here >= 0 && (x == 0 || left != here)) {
            return building.rooms.get(here).tile("InteriorWall");
        }

        return null;
    }

    private static int indexAt(Building building, Floor floor, int x, int y) {
        if (x < 0 || y < 0 || x >= building.width || y >= building.height) {
            return -1;
        }

        Room room = building.roomAt(floor, x, y);
        return room == null || room.isEmptyOutside() ? -1 : floor.roomAt[x][y];
    }

    private static boolean hasWindowVariant(TileEntry wall, String side, List<String> stack) {
        for (Map.Entry<String, String> tile : wall.tiles.entrySet()) {
            String e = tile.getKey();
            if (e.startsWith(side + "Window") && !tile.getValue().isEmpty() && stack.contains(tile.getValue())) {
                return true;
            }
        }

        return false;
    }

    /** Finds a config entry of the category using one of the stack's tiles at one of the enums. */
    private TileEntry findEntry(List<String> stack, String category, String... enums) {
        TileEntry best = null;
        for (String tile : stack) {
            for (EntryUse use : config.entriesUsing(tile)) {
                if (!use.entry().category.equals(category)) {
                    continue;
                }

                for (String e : enums) {
                    if (use.enumName().equals(e) && (best == null || use.entry().configIndex < best.configIndex)) {
                        best = use.entry();
                    }
                }
            }
        }

        return best;
    }

    // ---------------------------------------------------------------- stairs

    private void addStairs(Building building, List<List<String>[][]> observed, Stats stats) {
        for (Floor floor : building.floors) {
            List<String>[][] stacks = observed.get(floor.index);
            for (int x = 0; x < building.width; x++) {
                for (int y = 0; y < building.height; y++) {
                    for (String tile : stacks[x][y]) {
                        // The first entry (in config order) whose three stair tiles line up wins.
                        for (EntryUse use : config.entriesUsing(tile)) {
                            if (!use.entry().category.equals("stairs")) {
                                continue;
                            }

                            TileEntry entry = use.entry();
                            if (use.enumName().equals("West3") && x + 2 < building.width && x >= 1
                                && stacks[x + 1][y].contains(entry.tile("West2")) && stacks[x + 2][y].contains(entry.tile("West1"))) {
                                floor.objects.add(new Stairs(x - 1, y, "W", entry));
                                stats.stairs++;
                                break;
                            }

                            if (use.enumName().equals("North3") && y + 2 < building.height && y >= 1
                                && stacks[x][y + 1].contains(entry.tile("North2")) && stacks[x][y + 2].contains(entry.tile("North1"))) {
                                floor.objects.add(new Stairs(x, y - 1, "N", entry));
                                stats.stairs++;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ furniture

    private record Placement(int x, int y, FurnitureDef def, String orient, int size, int order) {
    }

    private void addFurniture(Building building, List<List<String>[][]> observed, Stats stats) {
        for (Floor floor : building.floors) {
            List<String>[][] stacks = observed.get(floor.index);
            RenderedFloor rendered = layout.render(building, floor);
            int w = building.width + 1;
            int h = building.height + 1;
            // Tiles the building already reproduces are not furniture.
            @SuppressWarnings("unchecked")
            List<String>[][] remaining = new List[w][h];
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    List<String> stack = new ArrayList<>(stacks[x][y]);
                    int[] match = align(stack, rendered.stack(x, y));
                    List<String> left = new ArrayList<>();
                    for (int i = 0; i < stack.size(); i++) {
                        if (match[i] < 0) {
                            left.add(stack.get(i));
                        }
                    }

                    remaining[x][y] = left;
                }
            }

            List<Placement> placements = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (String tile : remaining[x][y]) {
                        for (FurnitureUse use : config.furnitureUsing(tile)) {
                            Placement placement = placementFor(use, x, y, remaining, building);
                            if (placement != null && seen.add(placement.x() + "," + placement.y() + "," + placement.def().configIndex + placement.orient())) {
                                placements.add(placement);
                            }
                        }
                    }
                }
            }

            placements.sort(Comparator.comparingInt(Placement::size).reversed()
                .thenComparingInt(p -> p.def().configIndex)
                .thenComparingInt(Placement::order));
            List<Object[]> accepted = new ArrayList<>();
            for (Placement placement : placements) {
                // A square may hold the same tile several times (stacked furniture), so keep matching.
                while (consume(placement, remaining, building)) {
                    accepted.add(new Object[] {placement, firstTileIndex(placement, stacks)});
                    stats.furniture++;
                }
            }

            accepted.sort(Comparator.comparingInt(item -> (Integer) item[1]));
            for (Object[] item : accepted) {
                Placement placement = (Placement) item[0];
                floor.objects.add(new Furniture(placement.x(), placement.y(), placement.def(), placement.orient()));
            }
        }
    }

    /**
     * BuildingFurniture often defines the same piece in several layers (a wall shelf as
     * WallFurniture and as Furniture, a wall cabinet as Roof and as WallFurniture). The layer decides
     * where the piece sits in the square's tile stack, so for each furniture object that is part of a
     * stacking mismatch, the same-shaped definitions in other layers are tried and the one reproducing
     * the most compiled tiles is kept.
     */
    private void chooseFurnitureLayers(Building building, List<List<String>[][]> observed) {
        for (Floor floor : building.floors) {
            List<String>[][] stacks = observed.get(floor.index);
            for (int round = 0; round < 4; round++) {
                boolean[][] mismatched = new boolean[building.width + 1][building.height + 1];
                if (matchedTiles(building, floor, stacks, mismatched) < 0) {
                    break;
                }

                boolean improved = false;
                for (int i = 0; i < floor.objects.size(); i++) {
                    if (!(floor.objects.get(i) instanceof Furniture item) || !touches(item, mismatched)) {
                        continue;
                    }

                    List<Furniture> alternatives = layerAlternatives(item);
                    if (alternatives.isEmpty()) {
                        continue;
                    }

                    int best = matchedTiles(building, floor, stacks, null);
                    Furniture chosen = item;
                    for (Furniture alternative : alternatives) {
                        floor.objects.set(i, alternative);
                        int score = matchedTiles(building, floor, stacks, null);
                        if (score > best) {
                            best = score;
                            chosen = alternative;
                        }
                    }

                    floor.objects.set(i, chosen);
                    improved |= chosen != item;
                }

                if (!improved) {
                    break;
                }
            }
        }
    }

    /**
     * Compiled tiles of a floor reproduced by the building as it renders now. Squares with a
     * stacking mismatch are flagged in {@code mismatched} when given; returns -1 when there is none.
     */
    private int matchedTiles(Building building, Floor floor, List<String>[][] stacks, boolean[][] mismatched) {
        RenderedFloor rendered = layout.render(building, floor);
        int matched = 0;
        boolean any = false;
        for (int x = 0; x <= building.width; x++) {
            for (int y = 0; y <= building.height; y++) {
                List<Object[]> renderedStack = rendered.stack(x, y);
                int squareMatched = 0;
                for (int m : align(stacks[x][y], renderedStack)) {
                    if (m >= 0) {
                        squareMatched++;
                    }
                }

                matched += squareMatched;
                if (mismatched != null && squareMatched < renderedStack.size()) {
                    mismatched[x][y] = true;
                    any = true;
                }
            }
        }

        return mismatched != null && !any ? -1 : matched;
    }

    /** Whether a furniture object covers one of the flagged squares. */
    private static boolean touches(Furniture item, boolean[][] squares) {
        for (TbxModel.Point point : item.resolved().tiles.keySet()) {
            int x = item.x + point.x();
            int y = item.y + point.y();
            if (x >= 0 && y >= 0 && x < squares.length && y < squares[0].length && squares[x][y]) {
                return true;
            }
        }

        return false;
    }

    /** The same piece (same tiles on the same squares) from definitions in other layers, one per layer. */
    private List<Furniture> layerAlternatives(Furniture item) {
        FurnitureEntry entry = item.resolved();
        if (entry.isEmpty() || shiftsEastSouth(item.def.layer, item.orient)) {
            return List.of();
        }

        Map<String, Furniture> byLayer = new LinkedHashMap<>();
        String tile = entry.tiles.values().iterator().next();
        for (FurnitureUse use : config.furnitureUsing(tile)) {
            FurnitureDef def = use.def();
            if (def.layer.equals(item.def.layer) || def.layer.equals("Walls") || byLayer.containsKey(def.layer)
                || shiftsEastSouth(def.layer, item.orient) || !def.resolved(item.orient).tiles.equals(entry.tiles)) {
                continue;
            }

            byLayer.put(def.layer, new Furniture(item.x, item.y, def, item.orient));
        }

        return new ArrayList<>(byLayer.values());
    }

    /** Layers whose E and S orientations are drawn one square further (see TbxLayout.placeFurniture). */
    private static boolean shiftsEastSouth(String layer, String orient) {
        return (orient.equals("E") || orient.equals("S")) && (layer.equals("RoofCap") || layer.equals("Frames") || layer.equals("Doors"));
    }

    private Placement placementFor(FurnitureUse use, int x, int y, List<String>[][] remaining, Building building) {
        FurnitureDef def = use.def();
        if (def.layer.equals("Walls")) {
            return null;
        }

        boolean shifted = def.layer.equals("RoofCap") || def.layer.equals("Frames") || def.layer.equals("Doors");
        int dx = shifted && use.orient().equals("E") ? 1 : 0;
        int dy = shifted && use.orient().equals("S") ? 1 : 0;
        int anchorX = x - use.x() - dx;
        int anchorY = y - use.y() - dy;
        FurnitureEntry entry = def.entry(use.orient());
        for (Map.Entry<TbxModel.Point, String> tile : entry.tiles.entrySet()) {
            int sx = anchorX + tile.getKey().x() + dx;
            int sy = anchorY + tile.getKey().y() + dy;
            if (sx < 0 || sy < 0 || sx >= building.width + dx || sy >= building.height + dy
                || !remaining[sx][sy].contains(tile.getValue())) {
                return null;
            }
        }

        return new Placement(anchorX, anchorY, def, use.orient(), entry.tiles.size(), def.configIndex);
    }

    private boolean consume(Placement placement, List<String>[][] remaining, Building building) {
        FurnitureEntry entry = placement.def().entry(placement.orient());
        boolean shifted = placement.def().layer.equals("RoofCap") || placement.def().layer.equals("Frames")
            || placement.def().layer.equals("Doors");
        int dx = shifted && placement.orient().equals("E") ? 1 : 0;
        int dy = shifted && placement.orient().equals("S") ? 1 : 0;
        for (Map.Entry<TbxModel.Point, String> tile : entry.tiles.entrySet()) {
            int sx = placement.x() + tile.getKey().x() + dx;
            int sy = placement.y() + tile.getKey().y() + dy;
            if (sx < 0 || sy < 0 || sx >= building.width + dx || sy >= building.height + dy
                || !remaining[sx][sy].contains(tile.getValue())) {
                return false;
            }
        }

        for (Map.Entry<TbxModel.Point, String> tile : entry.tiles.entrySet()) {
            remaining[placement.x() + tile.getKey().x() + dx][placement.y() + tile.getKey().y() + dy].remove(tile.getValue());
        }

        return true;
    }

    private static int firstTileIndex(Placement placement, List<String>[][] stacks) {
        FurnitureEntry entry = placement.def().entry(placement.orient());
        Map.Entry<TbxModel.Point, String> first = entry.tiles.entrySet().iterator().next();
        int sx = Math.max(0, placement.x() + first.getKey().x());
        int sy = Math.max(0, placement.y() + first.getKey().y());
        if (sx >= stacks.length || sy >= stacks[0].length) {
            return 0;
        }

        return Math.max(0, stacks[sx][sy].indexOf(first.getValue()));
    }

    // ------------------------------------------------------- building slots

    private void chooseBuildingDefaults(Building building) {
        Map<String, Map<TileEntry, Integer>> counts = new HashMap<>();
        for (Floor floor : building.floors) {
            for (BuildingObject object : floor.objects) {
                if (object instanceof Door door) {
                    count(counts, "Door", door.tile);
                    count(counts, "DoorFrame", door.frame);
                } else if (object instanceof Window window) {
                    count(counts, "Window", window.tile);
                    count(counts, "Curtains", window.curtains);
                    count(counts, "Shutters", window.shutters);
                } else if (object instanceof Stairs stairs) {
                    count(counts, "Stairs", stairs.tile);
                }
            }
        }

        for (String slot : new String[] {"Door", "DoorFrame", "Window", "Curtains", "Shutters", "Stairs"}) {
            Map<TileEntry, Integer> votes = counts.get(slot);
            building.tiles.put(slot, votes == null ? null : winner(votes));
        }
    }

    /**
     * Roof objects are not rebuilt (their tiles stay user tiles), but the building's default roof
     * entries are set from the roof tiles drawn in the roof layers so BuildingEd offers that style.
     */
    private void chooseRoofDefaults(Building building) {
        Map<String, Map<TileEntry, Integer>> counts = new HashMap<>();
        String[][] slots = {{"RoofCap", "roof_caps", "RoofCap"}, {"RoofSlope", "roof_slopes", "Roof"}, {"RoofTop", "roof_tops", "RoofTop"}};
        for (Floor floor : building.floors) {
            for (String[] slot : slots) {
                for (String layerName : floor.userTiles.keySet()) {
                    if (!layerName.startsWith(slot[2])) {
                        continue;
                    }

                    for (String[] column : floor.userTiles.get(layerName)) {
                        for (String tile : column) {
                            if (tile != null) {
                                countCategoryInto(counts, slot[0], List.of(tile), slot[1]);
                            }
                        }
                    }
                }
            }
        }

        for (String[] slot : slots) {
            Map<TileEntry, Integer> votes = counts.get(slot[0]);
            building.tiles.put(slot[0], votes == null ? null : winner(votes));
        }
    }

    private static void count(Map<String, Map<TileEntry, Integer>> counts, String slot, TileEntry entry) {
        if (entry != null) {
            counts.computeIfAbsent(slot, ignored -> new HashMap<>()).merge(entry, 1, Integer::sum);
        }
    }

    private void countCategoryInto(Map<String, Map<TileEntry, Integer>> counts, String slot, List<String> stack, String category) {
        for (String tile : stack) {
            for (EntryUse use : config.entriesUsing(tile)) {
                if (use.entry().category.equals(category)) {
                    count(counts, slot, use.entry());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------ user tiles

    /**
     * Re-renders the building and keeps every compiled tile it does not reproduce as a user-drawn
     * tile, in a layer that preserves the compiled stacking order. Map ground under exterior squares
     * of world level 0 is left to the map.
     */
    private void keepUnexplainedTiles(Building building, List<List<String>[][]> observed, Stats stats) {
        List<String> layers = config.layers();
        for (Floor floor : building.floors) {
            RenderedFloor rendered = layout.render(building, floor);
            List<String>[][] stacks = observed.get(floor.index);
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    List<String> stack = stacks[x][y];
                    List<Object[]> renderedStack = rendered.stack(x, y);
                    int[] match = align(stack, renderedStack);
                    boolean[] renderedMatched = new boolean[renderedStack.size()];
                    int[] layerOf = new int[stack.size()];
                    java.util.Arrays.fill(layerOf, -1);
                    for (int i = 0; i < stack.size(); i++) {
                        if (match[i] >= 0) {
                            renderedMatched[match[i]] = true;
                            layerOf[i] = (Integer) renderedStack.get(match[i])[0];
                        }
                    }

                    Room room = building.roomAt(floor, x, y);
                    boolean exterior = room == null || room.isEmptyOutside();
                    boolean[] occupied = new boolean[layers.size()];
                    for (Object[] tile : renderedStack) {
                        occupied[(Integer) tile[0]] = true;
                    }

                    for (int i = 0; i < stack.size(); i++) {
                        if (match[i] >= 0) {
                            continue;
                        }

                        String tile = stack.get(i);
                        if (exterior && floor.worldLevel == 0 && (i == 0 || !TbxTileFilter.shouldInclude(tile))) {
                            stats.mapTiles++;
                            continue;
                        }

                        int previous = -1;
                        for (int k = i - 1; k >= 0; k--) {
                            if (layerOf[k] >= 0) {
                                previous = layerOf[k];
                                break;
                            }
                        }

                        int next = layers.size();
                        for (int k = i + 1; k < stack.size(); k++) {
                            if (layerOf[k] >= 0) {
                                next = layerOf[k];
                                break;
                            }
                        }

                        int layer = chooseUserLayer(tile, i == 0 ? "Floor" : preferredLayer(tile), previous, next, occupied,
                            renderedStack, renderedMatched);
                        if (layer < 0) {
                            continue;
                        }

                        occupied[layer] = true;
                        layerOf[i] = layer;
                        for (int r = 0; r < renderedStack.size(); r++) {
                            if (!renderedMatched[r] && (Integer) renderedStack.get(r)[0] == layer) {
                                renderedMatched[r] = true;
                            }
                        }

                        floor.setUserTile(layers.get(layer), x, y, tile, building.width, building.height);
                        stats.userTiles++;
                    }
                }
            }
        }
    }

    /**
     * Removes furniture objects that draw a tile the compiled square does not hold at that position
     * of its stack (an "extra" tile); returns whether any was removed.
     */
    private boolean dropMisorderedFurniture(Building building, List<List<String>[][]> observed, Stats stats) {
        boolean dropped = false;
        for (Floor floor : building.floors) {
            RenderedFloor rendered = layout.render(building, floor);
            List<String>[][] stacks = observed.get(floor.index);
            java.util.Set<BuildingObject> drop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    List<Object[]> renderedStack = rendered.stack(x, y);
                    int[] match = align(stacks[x][y], renderedStack);
                    boolean[] matched = new boolean[renderedStack.size()];
                    for (int m : match) {
                        if (m >= 0) {
                            matched[m] = true;
                        }
                    }

                    for (int r = 0; r < renderedStack.size(); r++) {
                        if (matched[r]) {
                            continue;
                        }

                        String tile = (String) renderedStack.get(r)[1];
                        for (BuildingObject object : floor.objects) {
                            if (object instanceof Furniture item && TbxLayout.furnitureTileAt(item, x, y, tile)) {
                                drop.add(item);
                            }
                        }
                    }
                }
            }

            if (!drop.isEmpty()) {
                floor.objects.removeIf(drop::contains);
                stats.furniture -= drop.size();
                dropped = true;
            }
        }

        return dropped;
    }

    /**
     * Renders the finished building (with user tiles), compares it with the compiled tiles and
     * returns which compiled tiles it reproduces.
     */
    private boolean[][][][] verify(Building building, List<List<String>[][]> observed, Stats stats) {
        boolean[][][][] reproduced = new boolean[building.floors.size()][building.width + 1][building.height + 1][];
        stats.mapTiles = 0;
        for (Floor floor : building.floors) {
            RenderedFloor rendered = layout.render(building, floor);
            List<String>[][] stacks = observed.get(floor.index);
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    List<String> stack = stacks[x][y];
                    List<Object[]> renderedStack = rendered.stack(x, y);
                    int[] match = align(stack, renderedStack);
                    Room room = building.roomAt(floor, x, y);
                    boolean exterior = room == null || room.isEmptyOutside();
                    int matched = 0;
                    boolean[] squareReproduced = new boolean[stack.size()];
                    reproduced[floor.index][x][y] = squareReproduced;
                    for (int i = 0; i < stack.size(); i++) {
                        if (match[i] >= 0) {
                            squareReproduced[i] = true;
                            matched++;
                        } else if (exterior && floor.worldLevel == 0 && (i == 0 || !TbxTileFilter.shouldInclude(stack.get(i)))) {
                            stats.mapTiles++;
                        } else {
                            stats.unmatched++;
                        }
                    }

                    stats.reproduced += matched;
                    stats.extra += renderedStack.size() - matched;
                }
            }
        }

        return reproduced;
    }

    private int chooseUserLayer(String tile, String preferredLayer, int previous, int next, boolean[] occupied,
                                List<Object[]> renderedStack, boolean[] renderedMatched) {
        List<String> layers = config.layers();
        int preferred = preferredLayer == null ? -1 : layers.indexOf(preferredLayer);
        // 1. The tile's natural layer, if it keeps the stacking order and is free.
        if (preferred > previous && preferred < next && !occupied[preferred]) {
            return preferred;
        }

        // 2. Replace a generated tile that is not in the compiled data (a differing wall, floor...).
        for (int r = 0; r < renderedStack.size(); r++) {
            int layer = (Integer) renderedStack.get(r)[0];
            if (!renderedMatched[r] && layer > previous && layer < next) {
                return layer;
            }
        }

        // 3. Any free layer between its neighbours, then any free layer above the previous tile.
        for (int layer = previous + 1; layer < next; layer++) {
            if (!occupied[layer]) {
                return layer;
            }
        }

        for (int layer = previous + 1; layer < layers.size(); layer++) {
            if (!occupied[layer]) {
                return layer;
            }
        }

        return -1;
    }

    private String preferredLayer(String tile) {
        String roofLayer = null;
        for (EntryUse use : config.entriesUsing(tile)) {
            String layer = switch (use.entry().category) {
                case "floors", "ceiling" -> "Floor";
                case "exterior_walls", "interior_walls" -> "Walls";
                case "exterior_wall_trim", "interior_wall_trim" -> "WallTrim";
                case "grime_floor" -> "FloorGrime";
                case "grime_wall" -> "WallGrime";
                case "doors" -> "Doors";
                case "door_frames" -> "Frames";
                case "windows" -> "Windows";
                case "curtains" -> "Curtains";
                case "shutters" -> "WallFurniture";
                case "stairs" -> "Furniture";
                default -> null;
            };
            if (layer != null) {
                return layer;
            }

            // Roof entries reuse wall and floor tiles, so they are only a last resort.
            if (roofLayer == null) {
                roofLayer = switch (use.entry().category) {
                    case "roof_caps" -> "RoofCap";
                    case "roof_slopes" -> "Roof";
                    case "roof_tops" -> "RoofTop";
                    default -> null;
                };
            }
        }

        for (FurnitureUse use : config.furnitureUsing(tile)) {
            return use.def().layer;
        }

        return roofLayer;
    }

    // ------------------------------------------------------------- helpers

    /** Longest-common-subsequence alignment of a compiled stack with a rendered stack. */
    static int[] align(List<String> observedStack, List<Object[]> renderedStack) {
        int n = observedStack.size();
        int m = renderedStack.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = Objects.equals(observedStack.get(i), renderedStack.get(j)[1])
                    ? lcs[i + 1][j + 1] + 1
                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        int[] match = new int[n];
        java.util.Arrays.fill(match, -1);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (Objects.equals(observedStack.get(i), renderedStack.get(j)[1])) {
                match[i] = j;
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }

        return match;
    }

    private static String majorityCovering(Map<String, Integer> counts, int squares) {
        String best = majority(counts);
        return best != null && counts.get(best) >= MIN_COVERAGE * squares ? best : null;
    }

    private static String majority(Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() > bestCount || (count.getValue() == bestCount && best != null && count.getKey().compareTo(best) < 0)) {
                best = count.getKey();
                bestCount = count.getValue();
            }
        }

        return best;
    }

    private TileEntry intern(TileEntry entry) {
        return customEntries.computeIfAbsent(entry.key(), ignored -> entry);
    }

    /** Deterministic room colour from the room name, so a room looks the same in every building. */
    private static int[] roomColor(RoomRect room) {
        int hash = Objects.hashCode(room.name()) * 31 + 7;
        return new int[] {64 + (hash & 0x7f), 64 + ((hash >> 8) & 0x7f), 64 + ((hash >> 16) & 0x7f)};
    }
}
