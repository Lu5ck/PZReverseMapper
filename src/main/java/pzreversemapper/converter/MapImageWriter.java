package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Writes per-cell and merged map previews, the zombie spawn map and the Build 42 biome map. */
final class MapImageWriter {
    private static final Pattern BIOME_FILE_NAME = Pattern.compile("^biomemap_(-?\\d+)_(-?\\d+)\\.png$", Pattern.CASE_INSENSITIVE);
    private static final Rgba DEFAULT_GROUND = new Rgba(145, 135, 60);

    private final String outputDirectory;
    private final int cellSize;
    private final Map<MapImageKind, Map<String, Rgba>> colorCache = new EnumMap<>(MapImageKind.class);
    private final Map<String, Boolean> largeVegetationCache = new HashMap<>();

    MapImageWriter(String outputDirectory, int cellSize) {
        this.outputDirectory = outputDirectory;
        this.cellSize = cellSize;
        for (MapImageKind kind : MapImageKind.values()) {
            colorCache.put(kind, new HashMap<>());
        }
    }

    int write(Collection<TargetCell> targetCells, Collection<LotHeaderData> headers) throws IOException {
        if (targetCells.isEmpty()) {
            return 0;
        }

        Path imageDirectory = Paths.get(outputDirectory, "maps_img");
        Files.createDirectories(imageDirectory);

        List<TargetCell> orderedCells = new ArrayList<>(targetCells);
        orderedCells.sort(Comparator.comparingInt((TargetCell cell) -> cell.coord().x()).thenComparingInt(cell -> cell.coord().y()));
        int written = 0;

        for (TargetCell cell : orderedCells) {
            String prefix = cell.coord().x() + "_" + cell.coord().y();
            renderCell(cell, MapImageKind.BASE).save(imageDirectory.resolve(prefix + "_base.png"));
            renderCell(cell, MapImageKind.FULL).save(imageDirectory.resolve(prefix + "_full.png"));
            renderCell(cell, MapImageKind.VEGETATION).save(imageDirectory.resolve(prefix + "_veg.png"));
            written += 3;
        }

        writeMerged(orderedCells, MapImageKind.BASE, "Map.png");
        writeMerged(orderedCells, MapImageKind.VEGETATION, "Map_veg.png");
        writeMerged(orderedCells, MapImageKind.FULL, "world.png");
        written += 3;

        if (writeZombieMap(headers)) {
            written++;
        }

        return written;
    }

    static int writeBiomeMapIfPresent(String inputDirectory, String outputDirectory) throws IOException {
        Path mapsDirectory = Paths.get(inputDirectory, "maps");
        if (!Files.isDirectory(mapsDirectory)) {
            return 0;
        }

        List<BiomeTile> tiles = new ArrayList<>();
        try (Stream<Path> entries = Files.list(mapsDirectory)) {
            entries.filter(Files::isRegularFile).forEach(file -> {
                Matcher match = BIOME_FILE_NAME.matcher(file.getFileName().toString());
                if (match.matches()) {
                    tiles.add(new BiomeTile(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), file));
                }
            });
        }

        tiles.sort(Comparator.comparingInt(BiomeTile::y).thenComparingInt(BiomeTile::x));
        if (tiles.isEmpty()) {
            return 0;
        }

        PngImage first = PngImage.load(tiles.get(0).file());
        int tileWidth = first.width();
        int tileHeight = first.height();
        if (tileWidth <= 0 || tileHeight <= 0) {
            return 0;
        }

        Map<CellCoord, Path> byCoord = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BiomeTile tile : tiles) {
            byCoord.putIfAbsent(new CellCoord(tile.x(), tile.y()), tile.file());
            minX = Math.min(minX, tile.x());
            minY = Math.min(minY, tile.y());
            maxX = Math.max(maxX, tile.x());
            maxY = Math.max(maxY, tile.y());
        }

        int outputWidth = Math.multiplyExact(maxX - minX + 1, tileWidth);
        int outputHeight = Math.multiplyExact(maxY - minY + 1, tileHeight);
        int gridMinX = minX;
        int gridMaxX = maxX;
        int gridMinY = minY;
        BiomeRowCache cache = new BiomeRowCache();

        PngImage.saveRows(Paths.get(outputDirectory, "biomemap.png"), outputWidth, outputHeight, (rowIndex, row) -> {
            fillOpaqueBlack(row);

            int gridY = gridMinY + rowIndex / tileHeight;
            int localY = rowIndex % tileHeight;
            if (cache.gridY != gridY) {
                cache.tiles = loadBiomeRow(byCoord, gridMinX, gridMaxX, gridY);
                cache.gridY = gridY;
            }

            PngImage[] cachedRow = cache.tiles;
            for (int x = 0; x < cachedRow.length; x++) {
                PngImage tile = cachedRow[x];
                if (tile == null || localY >= tile.height()) {
                    continue;
                }

                tile.copyRowTo(localY, row, x * tileWidth * 4);
            }
        });

        return 1;
    }

    private static PngImage[] loadBiomeRow(Map<CellCoord, Path> byCoord, int minX, int maxX, int y) throws IOException {
        PngImage[] row = new PngImage[maxX - minX + 1];
        for (int x = minX; x <= maxX; x++) {
            Path file = byCoord.get(new CellCoord(x, y));
            if (file != null) {
                row[x - minX] = PngImage.load(file);
            }
        }

        return row;
    }

    private static void fillOpaqueBlack(byte[] row) {
        java.util.Arrays.fill(row, (byte) 0);
        for (int i = 3; i < row.length; i += 4) {
            row[i] = (byte) 255;
        }
    }

    private record BiomeTile(int x, int y, Path file) {
    }

    /** The decoded biome tiles of the grid row currently being streamed. */
    private static final class BiomeRowCache {
        private int gridY = Integer.MIN_VALUE;
        private PngImage[] tiles = new PngImage[0];
    }

    private PngImage renderCell(TargetCell cell, MapImageKind kind) {
        PngImage image = new PngImage(cellSize, cellSize);
        image.clear(kind == MapImageKind.VEGETATION ? Rgba.BLACK : DEFAULT_GROUND);
        Map<String, Rgba> colors = colorCache.get(kind);

        for (Map.Entry<LayerKey, TileLayer> entry : cell.orderedLayers()) {
            int layer = entry.getKey().layer();
            if (kind == MapImageKind.BASE && layer > 1) {
                continue;
            }

            entry.getValue().forEach((index, tileName) -> {
                int x = index % cellSize;
                int y = index / cellSize;
                if (x < 0 || y < 0 || x >= cellSize || y >= cellSize) {
                    return;
                }

                Rgba color = colors.computeIfAbsent(tileName, name -> colorFor(kind, name));
                switch (kind) {
                    case BASE, VEGETATION -> image.setPixel(x, y, color);
                    case FULL -> drawTile(image, x, y, color,
                        largeVegetationCache.computeIfAbsent(tileName, MapImageWriter::isLargeVegetation));
                }
            });
        }

        return image;
    }

    private static Rgba colorFor(MapImageKind kind, String tileName) {
        return switch (kind) {
            case BASE -> getBaseColor(tileName);
            case FULL -> getFullColor(tileName);
            case VEGETATION -> getVegetationColor(tileName);
        };
    }

    /**
     * Writes a merged image of all cells. Rows of cells are rendered and streamed one band at a
     * time, so the full map never has to fit in a single buffer.
     */
    private void writeMerged(List<TargetCell> cells, MapImageKind kind, String fileName) throws IOException {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        TreeMap<Integer, List<TargetCell>> cellsByRow = new TreeMap<>();
        for (TargetCell cell : cells) {
            minX = Math.min(minX, cell.coord().x());
            minY = Math.min(minY, cell.coord().y());
            maxX = Math.max(maxX, cell.coord().x());
            maxY = Math.max(maxY, cell.coord().y());
            cellsByRow.computeIfAbsent(cell.coord().y(), y -> new ArrayList<>()).add(cell);
        }

        int width = Math.multiplyExact(maxX - minX + 1, cellSize);
        int height = Math.multiplyExact(maxY - minY + 1, cellSize);
        int originX = minX;
        int originY = minY;
        int[] bandY = {Integer.MIN_VALUE};
        List<int[]> bandOffsets = new ArrayList<>();
        List<PngImage> bandImages = new ArrayList<>();

        PngImage.saveRows(Paths.get(outputDirectory, fileName), width, height, (rowIndex, row) -> {
            int gridY = originY + rowIndex / cellSize;
            int localY = rowIndex % cellSize;
            if (bandY[0] != gridY) {
                bandOffsets.clear();
                bandImages.clear();
                for (TargetCell cell : cellsByRow.getOrDefault(gridY, List.of())) {
                    bandImages.add(renderCell(cell, kind));
                    bandOffsets.add(new int[] {(cell.coord().x() - originX) * cellSize * 4});
                }

                bandY[0] = gridY;
            }

            for (int i = 0; i < bandImages.size(); i++) {
                bandImages.get(i).copyRowTo(localY, row, bandOffsets.get(i)[0]);
            }
        });
    }

    private boolean writeZombieMap(Collection<LotHeaderData> headers) throws IOException {
        List<LotHeaderData> withDensity = new ArrayList<>();
        for (LotHeaderData header : headers) {
            if (header.zombieDensity().length > 0) {
                withDensity.add(header);
            }
        }

        if (withDensity.isEmpty()) {
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxChunks = Integer.MIN_VALUE;
        for (LotHeaderData header : withDensity) {
            minX = Math.min(minX, header.cellX());
            minY = Math.min(minY, header.cellY());
            maxX = Math.max(maxX, header.cellX());
            maxY = Math.max(maxY, header.cellY());
            maxChunks = Math.max(maxChunks, header.chunksPerCell());
        }

        PngImage image = new PngImage((maxX - minX + 1) * maxChunks, (maxY - minY + 1) * maxChunks);
        image.clear(Rgba.BLACK);

        for (LotHeaderData header : withDensity) {
            for (int x = 0; x < header.chunksPerCell(); x++) {
                for (int y = 0; y < header.chunksPerCell(); y++) {
                    int index = x * header.chunksPerCell() + y;
                    if (index >= header.zombieDensity().length) {
                        continue;
                    }

                    int value = header.zombieDensity()[index] & 0xff;
                    image.setPixel((header.cellX() - minX) * maxChunks + x, (header.cellY() - minY) * maxChunks + y,
                        new Rgba(value, value, value));
                }
            }
        }

        image.save(Paths.get(outputDirectory, "Map_ZombieSpawnMap.png"));
        return true;
    }

    private void drawTile(PngImage image, int x, int y, Rgba color, boolean large) {
        if (!large) {
            image.setPixel(x, y, color);
            return;
        }

        int left = Math.max(0, x - 2);
        int top = Math.max(0, y - 2);
        image.fillRect(left, top, Math.min(5, cellSize - left), Math.min(5, cellSize - top), color);
    }

    private static Rgba getBaseColor(String tileName) {
        Rgba color = getTerrainColor(tileName);
        if (isInteriorOrStructure(tileName)) {
            color = new Rgba(100, 100, 100);
        }

        return applyCommonOverlays(tileName, color);
    }

    private static Rgba getFullColor(String tileName) {
        Rgba color = getTerrainColor(tileName);
        if (Text.containsIgnoreCase(tileName, "roofs_") || Text.containsIgnoreCase(tileName, "floors_interior_")) {
            color = Rgba.fromHex("#47484a");
        } else if (Text.containsIgnoreCase(tileName, "electricity_") || isInteriorOrStructure(tileName)) {
            color = Rgba.fromHex("#2c2d2e");
        }

        if (Text.containsIgnoreCase(tileName, "grassoverlay")) {
            color = Rgba.fromHex("#75752f");
        }

        return applyCommonOverlays(tileName, color);
    }

    private static Rgba getTerrainColor(String tileName) {
        return switch (tileName) {
            case "blends_natural_01_16", "blends_natural_01_21", "blends_natural_01_22", "blends_natural_01_23" -> new Rgba(90, 100, 35);
            case "blends_natural_01_32", "blends_natural_01_37", "blends_natural_01_38", "blends_natural_01_39" -> new Rgba(117, 117, 47);
            case "blends_natural_01_48", "blends_natural_01_53", "blends_natural_01_54", "blends_natural_01_55" -> new Rgba(145, 135, 60);
            case "blends_natural_01_0", "blends_natural_01_5", "blends_natural_01_6", "blends_natural_01_7",
                "floors_exterior_natural_01_24", "floors_exterior_natural_01_32", "floors_exterior_natural_01_35" -> new Rgba(210, 200, 160);
            case "blends_street_01_48", "blends_street_01_53", "blends_street_01_54", "blends_street_01_55" -> new Rgba(165, 160, 140);
            case "blends_street_01_80", "blends_street_01_85", "blends_street_01_86", "blends_street_01_87",
                "floors_exterior_street_01_0", "floors_exterior_street_01_8", "floors_exterior_street_01_14",
                "floors_exterior_street_01_16", "floors_exterior_street_01_17" -> new Rgba(100, 100, 100);
            case "blends_street_01_96", "blends_street_01_101", "blends_street_01_102", "blends_street_01_103" -> new Rgba(120, 120, 120);
            case "floors_exterior_natural_01_12", "floors_exterior_natural_01_13" -> new Rgba(140, 70, 15);
            case "blends_natural_01_64", "blends_natural_01_69", "blends_natural_01_70", "blends_natural_01_71" -> new Rgba(120, 70, 20);
            case "blends_natural_01_80", "blends_natural_01_85", "blends_natural_01_86", "blends_natural_01_87" -> new Rgba(80, 55, 20);
            case "blends_street_01_0" -> new Rgba(110, 100, 100);
            case "blends_street_01_16", "blends_street_01_21" -> new Rgba(130, 120, 120);
            case "blends_natural_02_0", "blends_natural_02_5", "blends_natural_02_6", "blends_natural_02_7" -> new Rgba(0, 138, 255);
            case "carpentry_02_12", "carpentry_02_13", "carpentry_02_14", "carpentry_02_15", "carpentry_02_51", "carpentry_02_56" ->
                new Rgba(152, 126, 102);
            default -> DEFAULT_GROUND;
        };
    }

    private static Rgba applyCommonOverlays(String tileName, Rgba color) {
        if (Text.containsIgnoreCase(tileName, "trees_") || Text.containsIgnoreCase(tileName, "jumbo")) {
            return Rgba.fromHex("#263516");
        }

        if (Text.containsIgnoreCase(tileName, "vegetation_farm")) {
            return Rgba.fromHex("#daa520");
        }

        if (Text.containsIgnoreCase(tileName, "_railroad")) {
            return Rgba.fromHex("#493a2b");
        }

        if (Text.containsIgnoreCase(tileName, "fencing")) {
            return Rgba.fromHex("#525a53");
        }

        if (Text.containsIgnoreCase(tileName, "traffic")) {
            return Rgba.fromHex("#cccccc");
        }

        if (Text.containsIgnoreCase(tileName, "street_decoration_") || Text.containsIgnoreCase(tileName, "street_curbs_")) {
            return Rgba.fromHex("#3b3b3b");
        }

        if (Text.containsIgnoreCase(tileName, "floors_exterior_tilesandstone_01_")) {
            return new Rgba(210, 200, 160);
        }

        if (Text.containsIgnoreCase(tileName, "floors_exterior_natural_01_0")) {
            return Rgba.fromHex("#75752f");
        }

        return color;
    }

    private static Rgba getVegetationColor(String tileName) {
        switch (tileName) {
            case "jumbo_tree_01_0":
                return Rgba.RED;
            case "blends_natural_02_0", "blends_natural_02_5", "blends_natural_02_6", "blends_natural_02_7":
                return Rgba.BLACK;
            case "vegetation_trees_01_8", "vegetation_trees_01_9", "vegetation_trees_01_10", "vegetation_trees_01_11":
                return Rgba.RED;
            default:
                break;
        }

        if (isDarkGrassOverlay(tileName)) {
            return new Rgba(127, 0, 0);
        }

        if (isMediumGrassOverlay(tileName)) {
            return new Rgba(64, 0, 0);
        }

        if (isShortGrassOverlay(tileName)) {
            return new Rgba(0, 255, 0);
        }

        if (Text.startsWithIgnoreCase(tileName, "vegetation_foliage_01_")) {
            return Rgba.MAGENTA;
        }

        return Rgba.BLACK;
    }

    private static boolean isDarkGrassOverlay(String tileName) {
        Integer id = tryGetSuffix(tileName, "blends_grassoverlays_01_");
        return id != null && id >= 0 && id <= 21;
    }

    private static boolean isMediumGrassOverlay(String tileName) {
        Integer id = tryGetSuffix(tileName, "blends_grassoverlays_01_");
        return id != null && id >= 24 && id <= 45;
    }

    private static boolean isShortGrassOverlay(String tileName) {
        Integer id = tryGetSuffix(tileName, "blends_grassoverlays_01_");
        Integer groundId = tryGetSuffix(tileName, "vegetation_groundcover_01_");
        return (id != null && id >= 48 && id <= 69) || (groundId != null && groundId >= 18 && groundId <= 23);
    }

    private static Integer tryGetSuffix(String tileName, String prefix) {
        return Text.startsWithIgnoreCase(tileName, prefix) ? Text.tryParseInt(tileName.substring(prefix.length())) : null;
    }

    private static boolean isInteriorOrStructure(String tileName) {
        return Text.containsIgnoreCase(tileName, "wall")
            || Text.containsIgnoreCase(tileName, "fixture")
            || Text.containsIgnoreCase(tileName, "appliance")
            || Text.containsIgnoreCase(tileName, "furniture")
            || Text.containsIgnoreCase(tileName, "location")
            || Text.containsIgnoreCase(tileName, "lighting_indoor");
    }

    private static boolean isLargeVegetation(String tileName) {
        return Text.containsIgnoreCase(tileName, "jumbo") || Text.containsIgnoreCase(tileName, "_tree");
    }

    private enum MapImageKind {
        BASE,
        FULL,
        VEGETATION
    }
}
