package pzreversemapper.converter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects tileset definitions and assigns TMX first gids. */
final class TileSetCatalog {
    /** Case-folded sheet name to definition. */
    private final Map<String, TileSetDefinition> definitions = new HashMap<>();
    /** Case-folded sheet name to the highest local tile id observed in the map. */
    private final Map<String, ObservedSheet> observedMaxTile = new LinkedHashMap<>();
    private final List<String> definitionOrder = new ArrayList<>();
    private final Set<String> definitionOrderKeys = new HashSet<>();
    private final Set<String> observedTileNames = new HashSet<>();
    private final Map<String, Integer> gidCache = new HashMap<>();
    /** Tile name to the game properties BuildingEd's grime and window rules read. */
    private final Map<String, Map<String, String>> tileProperties = new HashMap<>();
    private boolean built;

    /** Property names kept from .tiles files (used to rebuild BuildingEd TBX files). */
    private static final Set<String> KEPT_PROPERTIES = Set.of(
        "GrimeType", "WallW", "WallN", "WallNW", "WallSE", "DoorWallW", "DoorWallN", "WallWTrans", "WallNTrans",
        "windowW", "windowN", "WindowShape");

    private static final class ObservedSheet {
        private final String name;
        private int maxTile;

        private ObservedSheet(String name, int maxTile) {
            this.name = name;
            this.maxTile = maxTile;
        }
    }

    /** Tilesets that received a first gid, ordered by first gid. */
    List<TileSetDefinition> tileSets() {
        List<TileSetDefinition> result = new ArrayList<>();
        for (TileSetDefinition definition : definitions.values()) {
            if (definition.firstGid > 0) {
                result.add(definition);
            }
        }

        result.sort((a, b) -> Integer.compare(a.firstGid, b.firstGid));
        return result;
    }

    void loadTilesPath(String path) throws IOException {
        loadTilesPath(path, false);
    }

    void loadTilesPath(String path, boolean overrideExisting) throws IOException {
        if (Text.isNullOrWhiteSpace(path)) {
            return;
        }

        Path root = Paths.get(path);
        if (Files.isRegularFile(root)) {
            if (Text.extension(path).equalsIgnoreCase(".tiles")) {
                loadTilesFile(root, overrideExisting);
            }

            return;
        }

        if (!Files.isDirectory(root)) {
            throw new FileNotFoundException("Tiles path not found: " + path);
        }

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && Text.endsWithIgnoreCase(file.getFileName().toString(), ".tiles")) {
                    files.add(file);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort((a, b) -> Text.CULTURE_ORDER.compare(a.toString(), b.toString()));
        for (Path file : files) {
            loadTilesFile(file, overrideExisting);
        }
    }

    Map<String, Map<String, String>> tileProperties() {
        return tileProperties;
    }

    void observe(String tileName) {
        if (!observedTileNames.add(tileName)) {
            return;
        }

        int index = splitIndex(tileName);
        if (index < 0) {
            return;
        }

        Integer localId = Text.tryParseInt(tileName.substring(index + 1));
        if (localId == null) {
            return;
        }

        String sheetName = tileName.substring(0, index);
        String key = Text.foldCase(sheetName);
        ObservedSheet current = observedMaxTile.get(key);
        if (current == null) {
            observedMaxTile.put(key, new ObservedSheet(sheetName, localId));
        } else if (localId > current.maxTile) {
            current.maxTile = localId;
        }
    }

    void build() {
        int firstGid = 1;
        for (String sheetName : definitionOrder) {
            if (observedMaxTile.containsKey(Text.foldCase(sheetName))) {
                firstGid = assignFirstGid(sheetName, firstGid);
            }
        }

        List<ObservedSheet> observed = new ArrayList<>(observedMaxTile.values());
        observed.sort((a, b) -> Text.compareOrdinalIgnoreCase(a.name, b.name));
        for (ObservedSheet sheet : observed) {
            TileSetDefinition existing = definitions.get(Text.foldCase(sheet.name));
            if (existing != null && existing.firstGid > 0) {
                continue;
            }

            firstGid = assignFirstGid(sheet.name, firstGid);
        }

        gidCache.clear();
        built = true;
    }

    int getGid(String tileName) {
        if (!built) {
            throw new IllegalStateException("Tileset catalog must be built before gid lookup.");
        }

        Integer cached = gidCache.get(tileName);
        if (cached != null) {
            return cached;
        }

        int gid = computeGid(tileName);
        gidCache.put(tileName, gid);
        return gid;
    }

    private int computeGid(String tileName) {
        int index = splitIndex(tileName);
        if (index < 0) {
            return 0;
        }

        Integer localId = Text.tryParseInt(tileName.substring(index + 1));
        if (localId == null) {
            return 0;
        }

        TileSetDefinition definition = definitions.get(Text.foldCase(tileName.substring(0, index)));
        return definition == null ? 0 : definition.firstGid + localId;
    }

    private int assignFirstGid(String sheetName, int firstGid) {
        String key = Text.foldCase(sheetName);
        ObservedSheet observed = observedMaxTile.get(key);
        TileSetDefinition definition = definitions.get(key);
        if (definition == null) {
            int maxTile = observed == null ? 0 : observed.maxTile;
            definition = new TileSetDefinition(sheetName, 8, Math.max(1, (maxTile + 8) / 8));
            definitions.put(key, definition);
        }

        int observedMax = observed == null ? definition.width * definition.height - 1 : observed.maxTile;
        if (observedMax >= definition.width * definition.height) {
            definition.height = Math.max(definition.height, (observedMax + definition.width) / definition.width);
        }

        definition.firstGid = firstGid;
        return firstGid + definition.width * definition.height;
    }

    private void loadTilesFile(Path file, boolean overrideExisting) {
        try (LittleEndianReader reader = new LittleEndianReader(file)) {
            reader.readInt();
            reader.readInt();
            int sheetCount = reader.readInt();

            for (int sheet = 0; sheet < sheetCount; sheet++) {
                reader.readLineString();
                String imageFile = reader.readLineString();
                int width = reader.readInt();
                int height = reader.readInt();
                reader.readInt();
                int tileCount = reader.readInt();

                String name = Text.fileNameWithoutExtension(imageFile);
                if (!Text.isNullOrWhiteSpace(name)) {
                    String key = Text.foldCase(name);
                    TileSetDefinition existing = definitions.get(key);
                    if (existing != null) {
                        if (overrideExisting) {
                            existing.width = width;
                            existing.height = height;
                        }
                    } else {
                        definitions.put(key, new TileSetDefinition(name, width, height));
                    }

                    if (definitionOrderKeys.add(key)) {
                        definitionOrder.add(name);
                    }
                }

                for (int tile = 0; tile < tileCount; tile++) {
                    int propertyCount = reader.readInt();
                    for (int prop = 0; prop < propertyCount; prop++) {
                        String key = reader.readLineString();
                        String value = reader.readLineString();
                        if (name != null && KEPT_PROPERTIES.contains(key)) {
                            tileProperties.computeIfAbsent(name + "_" + tile, ignored -> new HashMap<>(4)).put(key, value);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            System.out.println("Warning: failed to parse tiles file " + file + ": " + Text.messageOf(ex));
        }
    }

    /** Index of the '_' separating sheet name and tile id, or -1 when the name has no usable suffix. */
    private static int splitIndex(String tileName) {
        int index = tileName.lastIndexOf('_');
        return index <= 0 || index == tileName.length() - 1 ? -1 : index;
    }

    /** A TMX tileset: name, size in tiles and assigned first gid. */
    static final class TileSetDefinition {
        final String name;
        int width;
        int height;
        int firstGid;

        TileSetDefinition(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }
}
