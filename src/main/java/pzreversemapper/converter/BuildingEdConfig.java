package pzreversemapper.converter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pzreversemapper.converter.TbxModel.FurnitureDef;
import pzreversemapper.converter.TbxModel.FurnitureEntry;
import pzreversemapper.converter.TbxModel.Point;
import pzreversemapper.converter.TbxModel.TileEntry;

/**
 * BuildingEd configuration from PZ_Mapping_Tools: {@code BuildingTiles.txt} (tile entries per
 * category), {@code BuildingFurniture.txt} (furniture definitions) and {@code TMXConfig.txt}
 * (building layer order), plus game tile properties used by BuildingEd's grime and window rules.
 */
public final class BuildingEdConfig {
    static final String BUILDING_TILES = "BuildingTiles.txt";
    static final String BUILDING_FURNITURE = "BuildingFurniture.txt";
    static final String TMX_CONFIG = "TMXConfig.txt";

    /** Layer order used when TMXConfig.txt is not present (the PZ_Mapping_Tools default). */
    static final List<String> DEFAULT_LAYERS = List.of(
        "Floor", "FloorOverlay", "FloorGrime", "FloorGrime2", "FloorFurniture", "0_Vegetation", "Walls", "WallTrim",
        "Walls2", "WallTrim2", "RoofCap", "RoofCap2", "WallOverlay", "WallOverlay2", "WallGrime", "WallGrime2",
        "WallFurniture", "WallFurniture2", "Frames", "Doors", "Windows", "Curtains", "Furniture", "Furniture2",
        "Furniture3", "Furniture4", "Curtains2", "WallFurniture3", "WallFurniture4", "WallOverlay3", "WallOverlay4",
        "Roof", "Roof2", "RoofTop");

    private final Path directory;
    private final Map<String, List<TileEntry>> categories = new LinkedHashMap<>();
    private final List<FurnitureDef> furniture = new ArrayList<>();
    private final List<String> layers;
    private final Map<String, List<EntryUse>> entriesByTile = new HashMap<>();
    private final Map<String, List<FurnitureUse>> furnitureByTile = new HashMap<>();
    private Map<String, Map<String, String>> tileProperties = Map.of();

    /** Where a tile appears in BuildingTiles.txt. */
    record EntryUse(TileEntry entry, String enumName) {
    }

    /** Where a tile appears in BuildingFurniture.txt. */
    record FurnitureUse(FurnitureDef def, String orient, int x, int y) {
    }

    private BuildingEdConfig(Path directory, List<String> layers) {
        this.directory = directory;
        this.layers = Collections.unmodifiableList(layers);
    }

    /**
     * A configuration without tile entries or furniture: rebuilt buildings then hold their rooms and
     * keep every compiled tile as a user-drawn tile.
     */
    static BuildingEdConfig empty() {
        BuildingEdConfig config = new BuildingEdConfig(null, new ArrayList<>(DEFAULT_LAYERS));
        config.buildIndexes();
        return config;
    }

    /**
     * Loads the configuration from a folder containing {@code BuildingTiles.txt} (searched up to a few
     * levels deep, so the PZ_Mapping_Tools checkout or install folder can be given directly).
     */
    public static BuildingEdConfig load(String path) throws IOException {
        Path root = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new FileNotFoundException("BuildingEd config folder not found: " + root);
        }

        Path tilesFile = find(root, BUILDING_TILES);
        if (tilesFile == null) {
            throw new FileNotFoundException(BUILDING_TILES + " was not found in " + root
                + ". Select the folder holding the BuildingEd config files from PZ_Mapping_Tools.");
        }

        Path folder = tilesFile.getParent();
        Path tmxConfig = findSibling(folder, TMX_CONFIG);
        List<String> layers = tmxConfig == null ? new ArrayList<>(DEFAULT_LAYERS) : readLayers(tmxConfig);
        BuildingEdConfig config = new BuildingEdConfig(folder, layers);
        config.readTiles(tilesFile);
        Path furnitureFile = findSibling(folder, BUILDING_FURNITURE);
        if (furnitureFile != null) {
            config.readFurniture(furnitureFile);
        }

        config.buildIndexes();
        return config;
    }

    public Path directory() {
        return directory;
    }

    public int entryCount() {
        int count = 0;
        for (List<TileEntry> entries : categories.values()) {
            count += entries.size();
        }

        return count;
    }

    public int furnitureCount() {
        return furniture.size();
    }

    List<String> layers() {
        return layers;
    }

    List<TileEntry> entries(String category) {
        return categories.getOrDefault(category, List.of());
    }

    List<EntryUse> entriesUsing(String canonicalTile) {
        return canonicalTile == null ? List.of() : entriesByTile.getOrDefault(Text.foldCase(canonicalTile), List.of());
    }

    List<FurnitureUse> furnitureUsing(String canonicalTile) {
        return canonicalTile == null ? List.of() : furnitureByTile.getOrDefault(Text.foldCase(canonicalTile), List.of());
    }

    /** True when the tile belongs to an entry of the given category. */
    boolean isInCategory(String canonicalTile, String category) {
        for (EntryUse use : entriesUsing(canonicalTile)) {
            if (use.entry().category.equals(category)) {
                return true;
            }
        }

        return false;
    }

    /** Game tile properties (from .tiles files) keyed by canonical tile name; used for grime and window rules. */
    void setTileProperties(Map<String, Map<String, String>> properties) {
        Map<String, Map<String, String>> folded = new HashMap<>();
        properties.forEach((tile, props) -> folded.put(Text.foldCase(canonicalOrSelf(tile)), props));
        tileProperties = folded;
    }

    Map<String, String> tileProperties(String canonicalTile) {
        return canonicalTile == null ? Map.of() : tileProperties.getOrDefault(Text.foldCase(canonicalTile), Map.of());
    }

    private static String canonicalOrSelf(String tile) {
        String canonical = TbxModel.canonicalTile(tile);
        return canonical == null ? tile : canonical;
    }

    private void readTiles(Path file) throws IOException {
        Block root = Block.parse(file);
        int index = 0;
        for (Block category : root.children("category")) {
            String name = category.value("name");
            if (Text.isNullOrWhiteSpace(name)) {
                continue;
            }

            List<TileEntry> entries = categories.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (Block block : category.children("entry")) {
                TileEntry entry = new TileEntry(name, index++);
                for (String[] pair : block.values) {
                    if (pair[0].equals("offset")) {
                        String[] parts = Text.trim(pair[1]).split("\\s+");
                        if (parts.length == 3) {
                            Integer x = Text.tryParseInt(parts[1]);
                            Integer y = Text.tryParseInt(parts[2]);
                            if (x != null && y != null) {
                                entry.offsets.put(parts[0], new int[] {x, y});
                            }
                        }

                        continue;
                    }

                    String tile = TbxModel.canonicalTile(pair[1]);
                    entry.tiles.put(pair[0], tile == null ? "" : tile);
                }

                entries.add(entry);
            }
        }
    }

    private void readFurniture(Path file) throws IOException {
        Block root = Block.parse(file);
        int index = 0;
        for (Block group : root.children("group")) {
            String label = group.value("label");
            for (Block block : group.children("furniture")) {
                FurnitureDef def = new FurnitureDef(block.value("layer"), "true".equalsIgnoreCase(block.value("corners")), index++, label);
                for (Block entryBlock : block.children("entry")) {
                    String orient = entryBlock.value("orient");
                    if (orient == null) {
                        continue;
                    }

                    FurnitureEntry entry = new FurnitureEntry(orient);
                    entry.allowGrime = !"false".equalsIgnoreCase(entryBlock.value("grime"));
                    for (String[] pair : entryBlock.values) {
                        int comma = pair[0].indexOf(',');
                        if (comma <= 0) {
                            continue;
                        }

                        Integer x = Text.tryParseInt(pair[0].substring(0, comma));
                        Integer y = Text.tryParseInt(pair[0].substring(comma + 1));
                        String tile = TbxModel.canonicalTile(pair[1]);
                        if (x != null && y != null && tile != null) {
                            entry.tiles.put(new Point(x, y), tile);
                        }
                    }

                    def.entries.put(orient, entry);
                }

                furniture.add(def);
            }
        }
    }

    private void buildIndexes() {
        for (List<TileEntry> entries : categories.values()) {
            for (TileEntry entry : entries) {
                entry.tiles.forEach((enumName, tile) -> {
                    if (!tile.isEmpty()) {
                        entriesByTile.computeIfAbsent(Text.foldCase(tile), ignored -> new ArrayList<>()).add(new EntryUse(entry, enumName));
                    }
                });
            }
        }

        for (FurnitureDef def : furniture) {
            def.entries.forEach((orient, entry) -> entry.tiles.forEach((point, tile) ->
                furnitureByTile.computeIfAbsent(Text.foldCase(tile), ignored -> new ArrayList<>())
                    .add(new FurnitureUse(def, orient, point.x(), point.y()))));
        }
    }

    private static List<String> readLayers(Path file) throws IOException {
        Block root = Block.parse(file);
        List<String> layers = new ArrayList<>();
        for (Block block : root.children("layers")) {
            for (String[] pair : block.values) {
                if (pair[0].equals("tile") && !Text.isNullOrWhiteSpace(pair[1])) {
                    layers.add(Text.trim(pair[1]));
                }
            }
        }

        return layers.isEmpty() ? new ArrayList<>(DEFAULT_LAYERS) : layers;
    }

    private static Path findSibling(Path folder, String name) throws IOException {
        Path exact = folder.resolve(name);
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        try (var entries = Files.list(folder)) {
            return entries.filter(path -> path.getFileName().toString().equalsIgnoreCase(name) && Files.isRegularFile(path))
                .findFirst().orElse(null);
        }
    }

    private static Path find(Path root, String name) throws IOException {
        Path direct = findSibling(root, name);
        if (direct != null) {
            return direct;
        }

        Path[] found = new Path[1];
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 5, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dirName.startsWith(".") && !dir.equals(root) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equalsIgnoreCase(name)) {
                    found[0] = file;
                    return FileVisitResult.TERMINATE;
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /** A block of BuildingEd's simple text format: {@code key = value} lines and named child blocks. */
    static final class Block {
        final String name;
        final List<String[]> values = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();

        Block(String name) {
            this.name = name;
        }

        String value(String key) {
            for (String[] pair : values) {
                if (pair[0].equals(key)) {
                    return pair[1];
                }
            }

            return null;
        }

        List<Block> children(String childName) {
            List<Block> result = new ArrayList<>();
            for (Block block : blocks) {
                if (block.name.equals(childName)) {
                    result.add(block);
                }
            }

            return result;
        }

        static Block parse(Path file) throws IOException {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Block root = new Block("");
            List<Block> stack = new ArrayList<>();
            stack.add(root);
            String pendingName = null;
            for (String raw : lines) {
                String line = Text.trim(raw);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                Block current = stack.get(stack.size() - 1);
                if (line.equals("{")) {
                    Block child = new Block(pendingName == null ? "" : pendingName);
                    current.blocks.add(child);
                    stack.add(child);
                    pendingName = null;
                } else if (line.equals("}")) {
                    if (stack.size() > 1) {
                        stack.remove(stack.size() - 1);
                    }
                } else {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        current.values.add(new String[] {Text.trim(line.substring(0, equals)), Text.trim(line.substring(equals + 1))});
                        pendingName = null;
                    } else {
                        pendingName = line;
                    }
                }
            }

            return root;
        }
    }
}
