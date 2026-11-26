package pzreversemapper.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pzreversemapper.converter.TbxModel.Building;
import pzreversemapper.converter.TbxModel.Floor;
import pzreversemapper.converter.TbxModel.FurnitureDef;
import pzreversemapper.converter.TbxModel.Room;
import pzreversemapper.converter.TbxModel.TileEntry;

/**
 * A small BuildingEd configuration in the PZ_Mapping_Tools file formats and an L-shaped, two-floor
 * sample building that uses every reconstructed feature: exterior and interior walls, wall trim,
 * floors, ceilings, wall and floor grime, doors, windows with curtains and shutters, stairs,
 * multi-tile and wall furniture, and one user-drawn tile.
 */
final class TbxFixture {
    static final String GROUND = "blends_natural_01_16";
    static final String DECOR = "misc_decor_01_5";

    private TbxFixture() {
    }

    static Path writeConfig(Path directory) throws IOException {
        Files.createDirectories(directory);
        StringBuilder tiles = new StringBuilder("version = 4\nrevision = 1\nsource_revision = 0\n");
        category(tiles, "Exterior Walls", "exterior_walls", walls("walls_exterior_house_01"), walls("walls_exterior_house_02"));
        category(tiles, "Interior Walls", "interior_walls", walls("walls_interior_house_01"), walls("walls_interior_house_02"));
        category(tiles, "Trim - Interior Walls", "interior_wall_trim", walls("walls_interior_trim_01"));
        category(tiles, "Floors", "floors", "Floor = floors_interior_tilesandwood_01_040", "Floor = floors_interior_carpet_01_005");
        category(tiles, "Doors", "doors",
            "West = fixtures_doors_01_000\nNorth = fixtures_doors_01_001\nWestOpen = fixtures_doors_01_002\nNorthOpen = fixtures_doors_01_003");
        category(tiles, "Door Frames", "door_frames", "West = fixtures_doors_frames_01_000\nNorth = fixtures_doors_frames_01_001");
        category(tiles, "Windows", "windows", "West = fixtures_windows_01_000\nNorth = fixtures_windows_01_001");
        category(tiles, "Curtains", "curtains", "West = fixtures_windows_curtains_01_032\nEast = fixtures_windows_curtains_01_033\n"
            + "North = fixtures_windows_curtains_01_034\nSouth = fixtures_windows_curtains_01_035");
        category(tiles, "Shutters", "shutters", "WestBelow = fixtures_windows_detailing_01_016\nWestAbove = fixtures_windows_detailing_01_017\n"
            + "NorthLeft = fixtures_windows_detailing_01_018\nNorthRight = fixtures_windows_detailing_01_019");
        category(tiles, "Stairs", "stairs", "West1 = fixtures_stairs_01_000\nWest2 = fixtures_stairs_01_001\nWest3 = fixtures_stairs_01_002\n"
            + "North1 = fixtures_stairs_01_008\nNorth2 = fixtures_stairs_01_009\nNorth3 = fixtures_stairs_01_010");
        category(tiles, "Grime - Floors", "grime_floor", "West = overlay_grime_floor_01_000\nNorth = overlay_grime_floor_01_001\n"
            + "East = overlay_grime_floor_01_002\nSouth = overlay_grime_floor_01_003\nSouthWest = overlay_grime_floor_01_004\n"
            + "NorthWest = overlay_grime_floor_01_005\nNorthEast = overlay_grime_floor_01_006\nSouthEast = overlay_grime_floor_01_007");
        category(tiles, "Grime - Walls", "grime_wall", "West = overlay_grime_wall_01_000\nNorth = overlay_grime_wall_01_001\n"
            + "NorthWest = overlay_grime_wall_01_002\nSouthEast = overlay_grime_wall_01_003\nWestWindow = overlay_grime_wall_01_008\n"
            + "NorthWindow = overlay_grime_wall_01_009\nWestDoor = overlay_grime_wall_01_010\nNorthDoor = overlay_grime_wall_01_011");
        category(tiles, "Roof Tops", "roof_tops", "West3 = floors_interior_carpet_01_005");
        category(tiles, "Ceilings", "ceiling", "Ceiling = ceilings_01_000");
        Files.writeString(directory.resolve(BuildingEdConfig.BUILDING_TILES), tiles, StandardCharsets.UTF_8);

        String furniture = "version = 0\nrevision = 1\nsource_revision = 0\n"
            + "group\n{\n    label = Lighting\n    furniture\n    {\n        layer = WallFurniture\n"
            + entry("W", "0,0 = lighting_indoor_01_001") + entry("N", "0,0 = lighting_indoor_01_000")
            + entry("E", "0,0 = lighting_indoor_01_002") + entry("S", "0,0 = lighting_indoor_01_003") + "    }\n}\n"
            + "group\n{\n    label = Bedroom\n    furniture\n    {\n"
            + entry("W", "0,0 = furniture_bedding_01_000\n            0,1 = furniture_bedding_01_001")
            + entry("N", "0,0 = furniture_bedding_01_002\n            1,0 = furniture_bedding_01_003") + "    }\n"
            + "    furniture\n    {\n" + entry("W", "0,0 = furniture_tables_01_000") + entry("N", "0,0 = furniture_tables_01_001") + "    }\n}\n";
        Files.writeString(directory.resolve(BuildingEdConfig.BUILDING_FURNITURE), furniture, StandardCharsets.UTF_8);

        StringBuilder tmx = new StringBuilder("version = 1\nrevision = 1\nsource_revision = 0\nlayers\n{\n");
        for (String layer : BuildingEdConfig.DEFAULT_LAYERS) {
            tmx.append("    tile = ").append(layer).append('\n');
        }

        tmx.append("}\n");
        Files.writeString(directory.resolve(BuildingEdConfig.TMX_CONFIG), tmx, StandardCharsets.UTF_8);
        return directory;
    }

    private static String walls(String sheet) {
        String[] enums = {"West", "North", "NorthWest", "SouthEast", "WestWindow", "NorthWindow", "WestDoor", "NorthDoor"};
        int[] ids = {0, 1, 2, 3, 8, 9, 10, 11};
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < enums.length; i++) {
            builder.append(enums[i]).append(" = ").append(sheet).append(String.format("_%03d", ids[i])).append('\n');
        }

        return builder.toString().trim();
    }

    private static void category(StringBuilder out, String label, String name, String... entries) {
        out.append("category\n{\n    label = ").append(label).append("\n    name = ").append(name).append('\n');
        for (String entry : entries) {
            out.append("    entry\n    {\n");
            for (String line : entry.split("\n")) {
                out.append("        ").append(line.trim()).append('\n');
            }

            out.append("    }\n");
        }

        out.append("}\n");
    }

    private static String entry(String orient, String tiles) {
        return "        entry\n        {\n            orient = " + orient + "\n            " + tiles + "\n        }\n";
    }

    /** The sample building: kitchen (L) + bedroom on floor 0, a bedroom on floor 1. */
    static Building sampleBuilding(BuildingEdConfig config) {
        Building building = new Building(8, 6);
        TileEntry exterior = config.entries("exterior_walls").get(0);
        TileEntry interior1 = config.entries("interior_walls").get(0);
        TileEntry interior2 = config.entries("interior_walls").get(1);
        TileEntry trim = config.entries("interior_wall_trim").get(0);
        TileEntry wood = config.entries("floors").get(0);
        TileEntry carpet = config.entries("floors").get(1);
        TileEntry door = config.entries("doors").get(0);
        TileEntry frame = config.entries("door_frames").get(0);
        TileEntry window = config.entries("windows").get(0);
        TileEntry curtains = config.entries("curtains").get(0);
        TileEntry shutters = config.entries("shutters").get(0);
        TileEntry stairs = config.entries("stairs").get(0);
        TileEntry grimeFloor = config.entries("grime_floor").get(0);
        TileEntry grimeWall = config.entries("grime_wall").get(0);
        TileEntry ceiling = config.entries("ceiling").get(0);

        building.tiles.put("ExteriorWall", exterior);
        building.tiles.put("GrimeWall", grimeWall);

        Room kitchen = new Room("kitchen", "kitchen", new int[] {200, 100, 50}, 0);
        kitchen.tiles.put("InteriorWall", interior1);
        kitchen.tiles.put("InteriorWallTrim", trim);
        kitchen.tiles.put("Floor", wood);
        kitchen.tiles.put("GrimeFloor", grimeFloor);
        kitchen.tiles.put("GrimeWall", grimeWall);
        kitchen.tiles.put("Ceiling", ceiling);
        Room bedroom = new Room("bedroom", "bedroom", new int[] {50, 100, 200}, 1);
        bedroom.tiles.put("InteriorWall", interior2);
        bedroom.tiles.put("Floor", carpet);
        bedroom.tiles.put("Ceiling", ceiling);
        Room upstairs = new Room("bedroom", "bedroom", new int[] {50, 200, 100}, 2);
        upstairs.tiles.put("InteriorWall", interior1);
        upstairs.tiles.put("Floor", carpet);
        building.rooms.add(kitchen);
        building.rooms.add(bedroom);
        building.rooms.add(upstairs);

        Floor ground = new Floor(0, 0, 8, 6);
        fill(ground, 0, 0, 0, 5, 6);
        fill(ground, 1, 5, 0, 3, 3);
        ground.objects.add(new TbxModel.Door(5, 1, "W", door, frame));
        ground.objects.add(new TbxModel.Door(2, 6, "N", door, frame));
        ground.objects.add(new TbxModel.Window(0, 2, "W", window, curtains, null));
        ground.objects.add(new TbxModel.Window(6, 3, "N", window, curtains, shutters));
        ground.objects.add(new TbxModel.Stairs(0, 4, "W", stairs));
        FurnitureDef lighting = furniture(config, 0);
        FurnitureDef bed = furniture(config, 1);
        FurnitureDef table = furniture(config, 2);
        ground.objects.add(new TbxModel.Furniture(0, 0, lighting, "W"));
        ground.objects.add(new TbxModel.Furniture(3, 1, bed, "W"));
        ground.objects.add(new TbxModel.Furniture(6, 1, table, "N"));
        ground.setUserTile("FloorOverlay", 1, 1, DECOR, 8, 6);
        building.floors.add(ground);

        Floor first = new Floor(1, 1, 8, 6);
        fill(first, 2, 0, 0, 5, 6);
        first.objects.add(new TbxModel.Furniture(2, 2, table, "W"));
        building.floors.add(first);
        return building;
    }

    private static FurnitureDef furniture(BuildingEdConfig config, int index) {
        for (FurnitureDef def : allFurniture(config)) {
            if (def.configIndex == index) {
                return def;
            }
        }

        throw new IllegalStateException("furniture " + index);
    }

    private static List<FurnitureDef> allFurniture(BuildingEdConfig config) {
        List<FurnitureDef> defs = new ArrayList<>();
        for (String tile : new String[] {"lighting_indoor_01_1", "furniture_bedding_01_0", "furniture_tables_01_0"}) {
            for (BuildingEdConfig.FurnitureUse use : config.furnitureUsing(tile)) {
                if (!defs.contains(use.def())) {
                    defs.add(use.def());
                }
            }
        }

        return defs;
    }

    private static void fill(Floor floor, int room, int x0, int y0, int width, int height) {
        for (int x = x0; x < x0 + width; x++) {
            for (int y = y0; y < y0 + height; y++) {
                floor.roomAt[x][y] = room;
            }
        }
    }

    /** Room rectangles of the sample building at a world origin, one per room (rooms are rectangles). */
    static List<RoomRect> roomRects(Building building, int originX, int originY) {
        List<RoomRect> rects = new ArrayList<>();
        for (Floor floor : building.floors) {
            for (int r = 0; r < building.rooms.size(); r++) {
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = -1;
                int maxY = -1;
                for (int x = 0; x < building.width; x++) {
                    for (int y = 0; y < building.height; y++) {
                        if (floor.roomAt[x][y] == r) {
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }

                if (maxX >= 0) {
                    rects.add(new RoomRect(building.rooms.get(r).sourceRoomId, building.rooms.get(r).internalName, floor.worldLevel,
                        originX + minX, originY + minY, maxX - minX + 1, maxY - minY + 1));
                }
            }
        }

        return rects;
    }

    /**
     * The compiled stacks WorldEd would produce for the building on a map: the building's tiles with
     * map ground under every square of world level 0 that the building leaves without a floor.
     */
    static List<String>[][][] compiledStacks(Building building, BuildingEdConfig config) {
        TbxLayout layout = new TbxLayout(config);
        @SuppressWarnings("unchecked")
        List<String>[][][] stacks = new List[building.floors.size()][building.width + 1][building.height + 1];
        for (Floor floor : building.floors) {
            TbxLayout.RenderedFloor rendered = layout.render(building, floor);
            for (int x = 0; x <= building.width; x++) {
                for (int y = 0; y <= building.height; y++) {
                    List<String> stack = new ArrayList<>();
                    List<Object[]> tiles = rendered.stack(x, y);
                    boolean hasFloor = !tiles.isEmpty() && (Integer) tiles.get(0)[0] == 0;
                    if (floor.worldLevel == 0 && !hasFloor) {
                        stack.add(GROUND);
                    }

                    for (Object[] tile : tiles) {
                        stack.add((String) tile[1]);
                    }

                    stacks[floor.index][x][y] = stack;
                }
            }
        }

        return stacks;
    }
}
