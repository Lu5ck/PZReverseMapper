package pzreversemapper.converter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Runs a complete export: headers, lotpacks, rooms, objects, TBX, images, TMX and PZW. */
public final class MapConverter {
    private final ConverterOptions options;
    private final Consumer<ConversionProgress> progress;
    private final Map<CellCoord, TargetCell> targetCells = new LinkedHashMap<>();
    private int targetCellSize;

    public MapConverter(ConverterOptions options) {
        this(options, null);
    }

    public MapConverter(ConverterOptions options, Consumer<ConversionProgress> progress) {
        this.options = options;
        this.progress = progress;
    }

    public ConversionResult run() throws IOException {
        long start = System.nanoTime();
        if (options.tilesOnly()) {
            return runTilesOnly(start);
        }

        if (!Files.isDirectory(Paths.get(options.inputDirectory()))) {
            throw new FileNotFoundException("Input folder not found: " + options.inputDirectory());
        }

        validateOutputSelection();
        report("Prepare", "Preparing output folder");
        prepareOutput();

        report("Headers", "Reading lotheaders");
        Map<CellCoord, LotHeaderData> headers = LotHeaderReader.readAll(options.inputDirectory());
        if (options.includeCells() != null) {
            headers.keySet().retainAll(options.includeCells());
        }

        if (headers.isEmpty()) {
            throw new InvalidDataException("No matching .lotheader files found in " + options.inputDirectory());
        }

        Collection<LotHeaderData> headerList = headers.values();
        validateSourceCellSize(headerList);
        targetCellSize = resolveTargetCellSize(options.targetCellSize(), headerList);
        report("Headers", "Loaded " + headers.size() + " source cell(s), output grid " + targetCellSize + "x" + targetCellSize);

        TileSetCatalog tileSets = new TileSetCatalog();
        report("Tiles", "Loading tile definitions");
        tileSets.loadTilesPath(options.tilesPath());
        if (!Text.isNullOrWhiteSpace(options.modTilesPath())) {
            report("Tiles", "Loading modded tile definitions");
            tileSets.loadTilesPath(options.modTilesPath(), true);
        }

        BuildingEdConfig buildingConfig = null;
        if (options.exportBuildingTbx() && !Text.isNullOrWhiteSpace(options.tbxConfigPath())) {
            report("Building TBX", "Loading BuildingEd config");
            buildingConfig = BuildingEdConfig.load(options.tbxConfigPath());
            buildingConfig.setTileProperties(tileSets.tileProperties());
            report("Building TBX", "BuildingEd config: " + buildingConfig.entryCount() + " tile entries, "
                + buildingConfig.furnitureCount() + " furniture from " + buildingConfig.directory());
        }

        report("Tile images", options.exportTilePacks() ? "Extracting tilespack images" : "Skipping tilespack extraction");
        int tileImageCount = options.exportTilePacks()
            ? TilesPackExtractor.extract(options.enumerateTilesAssetPaths(), options.outputDirectory()).totalImageCount()
            : 0;

        report("Grid", "Building target cell grid");
        createTargetCellGrid(headerList);
        readLotPacks(headerList, tileSets);
        report("Tiles", "Finalizing tilesets");
        tileSets.build();
        // Buildings come first: rooms and tiles drawn by their lots are left out of the TMX.
        BuildingTbxWriter buildingTbxWriter = null;
        if (options.exportBuildingTbx()) {
            buildingTbxWriter = new BuildingTbxWriter(options.outputDirectory(), targetCellSize, targetCells, buildingConfig,
                !options.tbxOnly(), tileSets::tmxTileName);
            report("Building TBX", buildingConfig != null ? "Rebuilding BuildingEd buildings" : "Writing building TBX files");
            buildingTbxWriter.write(headerList);
            String summary = describeBuildings(buildingTbxWriter);
            report("TBX rebuild", summary);
            System.out.println(summary);
        } else {
            report("Building TBX", "Skipping building TBX files");
        }

        report("Rooms", "Reprojecting RoomDefs");
        reprojectRooms(headerList, buildingTbxWriter);
        boolean writeObjects = options.exportObjects() && !options.tbxOnly();
        report("Objects", writeObjects ? "Reading objects.lua" : "Skipping objects.lua");
        List<MapObject> objects = writeObjects ? readObjects() : List.of();
        boolean writeImages = options.exportImages() && !options.tbxOnly();
        report("Images", writeImages ? "Writing preview images" : "Skipping preview images");
        int imageCount = 0;
        if (writeImages) {
            imageCount += new MapImageWriter(options.outputDirectory(), targetCellSize).write(targetCells.values(), headerList);
            if (options.includeCells() == null) {
                report("Images", "Checking B42 biomemap tiles");
                imageCount += MapImageWriter.writeBiomeMapIfPresent(options.inputDirectory(), options.outputDirectory());
            } else {
                report("Images", "Skipping full biomemap for filtered source-cell export");
            }
        }

        if (!options.tbxOnly()) {
            TmxWriter tmxWriter = new TmxWriter(options.outputDirectory(), targetCellSize, tileSets, shouldWriteTmxLevelAttributes(headerList));
            List<TargetCell> orderedCells = new ArrayList<>(targetCells.values());
            orderedCells.sort(Comparator.comparingInt((TargetCell cell) -> cell.coord().x()).thenComparingInt(cell -> cell.coord().y()));
            for (int i = 0; i < orderedCells.size(); i++) {
                TargetCell cell = orderedCells.get(i);
                report("TMX", "Writing TMX " + cell.coord(), i + 1, orderedCells.size());
                tmxWriter.write(cell);
            }
        }

        String pzwFile = Paths.get(options.outputDirectory(), options.projectName() + ".pzw").toString();
        if (options.tbxOnly()) {
            report("PZW", "Skipping WorldEd project");
            pzwFile = "";
        } else {
            report("PZW", "Writing WorldEd project");
            if (!PzwWriter.isWorldEdCellSize(targetCellSize)) {
                System.out.println("Warning: WorldEd only supports 300x300 (legacy-300) and 256x256 (native-256) cells; "
                    + "the " + targetCellSize + "x" + targetCellSize + " project will open as legacy-300.");
            }

            PzwWriter.write(pzwFile, options.projectName(), targetCells.keySet(), objects, targetCellSize);
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        report("Done", "Export finished in " + formatElapsed(elapsed));

        return new ConversionResult(
            headers.size(),
            targetCells.size(),
            objects.size(),
            imageCount,
            tileImageCount,
            buildingTbxWriter == null ? 0 : buildingTbxWriter.uniqueCount(),
            buildingTbxWriter == null ? 0 : buildingTbxWriter.buildingCount(),
            buildingTbxWriter == null ? 0 : buildingTbxWriter.lotCount(),
            tileSets.tileSets().size(),
            elapsed,
            options.outputDirectory(),
            pzwFile);
    }

    private ConversionResult runTilesOnly(long start) throws IOException {
        validateOutputSelection();
        report("Prepare", "Preparing tiles-only output folder");
        prepareOutput();
        report("Tile images", "Extracting atlases, individual tiles and tilesets");
        TilesPackExtractor.Result extraction = TilesPackExtractor.extract(options.enumerateTilesAssetPaths(), options.outputDirectory());

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        report("Done", "Tiles-only export finished in " + formatElapsed(elapsed));
        return new ConversionResult(
            0,
            0,
            0,
            0,
            extraction.totalImageCount(),
            0,
            0,
            0,
            extraction.tileSheetCount(),
            elapsed,
            options.outputDirectory(),
            "");
    }

    private void validateOutputSelection() {
        if (!Text.isNullOrWhiteSpace(options.inputDirectory())
            && Text.equalsOrdinalIgnoreCase(Text.trimPath(options.inputDirectory()), Text.trimPath(options.outputDirectory()))) {
            throw new IllegalStateException("Input and output folders must be different.");
        }

        if (options.tilesOnly()) {
            if (!options.exportTilePacks()) {
                throw new IllegalStateException("Tiles-only mode requires tilespack extraction.");
            }

            if (options.enumerateTilesAssetPaths().isEmpty()) {
                throw new IllegalStateException("Tiles-only mode requires a tiles or mod-assets path.");
            }
        }

        if (options.tbxOnly() && !options.exportBuildingTbx()) {
            throw new IllegalStateException("TBX only requires building TBX export to be enabled.");
        }
    }

    private void report(String stage, String message) {
        report(stage, message, null, null);
    }

    private void report(String stage, String message, Integer completed, Integer total) {
        if (progress != null) {
            progress.accept(new ConversionProgress(stage, message, completed, total));
        }
    }

    private static String describeBuildings(BuildingTbxWriter writer) {
        String text = (writer.rebuilt() ? "Rebuilt " : "Wrote ") + writer.buildingCount() + " building(s) as " + writer.uniqueCount()
            + " unique TBX file(s)";
        if (writer.placesLots()) {
            text += ", " + writer.lotCount() + " placed as lots";
            List<String> kept = new ArrayList<>();
            if (writer.crossingCount() > 0) {
                kept.add(writer.crossingCount() + " crossing a TMX cell border");
            }

            if (writer.inexactCount() > 0) {
                kept.add(writer.inexactCount() + " not reproduced exactly");
            }

            if (!kept.isEmpty()) {
                text += " (kept as TMX tiles: " + String.join(", ", kept) + ")";
            }
        }

        if (!writer.rebuilt()) {
            return text;
        }

        TbxReconstructor.Stats stats = writer.stats();
        int total = stats.buildingTiles();
        String percent = total == 0 ? "100" : String.format(java.util.Locale.ROOT, "%.1f", stats.reproduced * 100.0 / total);
        return text + ": " + percent + "% of " + total + " building tiles reproduced ("
            + stats.userTiles + " kept as user tiles, " + stats.unmatched + " not reproduced, " + stats.extra + " extra); "
            + stats.doors + " doors, " + stats.windows + " windows, " + stats.stairs + " stairs, " + stats.furniture + " furniture";
    }

    /** Formats like the original {@code m:ss} / {@code h:mm:ss} output. */
    public static String formatElapsed(Duration elapsed) {
        long totalSeconds = elapsed.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours >= 1
            ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private boolean shouldWriteTmxLevelAttributes(Collection<LotHeaderData> headers) {
        // Native 256x256 output is a Build 42 style project, which uses TMX 2.0 level attributes.
        if (targetCellSize == BUILD42_CELL_SIZE
            || (options.expectedSourceCellSize() != null && options.expectedSourceCellSize() == 256)) {
            return true;
        }

        for (LotHeaderData header : headers) {
            if (header.cellDim() == 256 || header.minLevel() < 0) {
                return true;
            }
        }

        return false;
    }

    /** Throws when a header does not match the expected source cell size. */
    public static void validateSourceCellSize(Integer expectedSourceCellSize, Collection<LotHeaderData> headers) throws InvalidDataException {
        if (expectedSourceCellSize == null) {
            return;
        }

        List<String> unexpected = new ArrayList<>();
        for (LotHeaderData header : headers) {
            if (header.cellDim() != expectedSourceCellSize) {
                unexpected.add(header.cellX() + "_" + header.cellY() + "=" + header.cellDim());
            }
        }

        if (unexpected.isEmpty()) {
            return;
        }

        throw new InvalidDataException(
            "Source profile expected " + expectedSourceCellSize + "x" + expectedSourceCellSize + " cells, "
                + "but these headers differ: " + String.join(", ", unexpected.subList(0, Math.min(12, unexpected.size()))));
    }

    private void validateSourceCellSize(Collection<LotHeaderData> headers) throws InvalidDataException {
        validateSourceCellSize(options.expectedSourceCellSize(), headers);
    }

    private void prepareOutput() throws IOException {
        Path output = Paths.get(options.outputDirectory());
        if (options.cleanOutput() && Files.isDirectory(output)) {
            OutputCleaner.ensureSafeCleanTarget(options.outputDirectory());
            if (OutputCleaner.hasContent(options.outputDirectory()) && !options.cleanOutputConfirmed()) {
                throw new IllegalStateException(
                    "Clean output requires explicit confirmation before moving existing output files to the "
                        + OutputCleaner.trashName() + ".");
            }

            report("Prepare", "Moving existing output contents to " + OutputCleaner.trashName());
            int movedCount = OutputCleaner.cleanToRecycleBin(options.outputDirectory());
            report("Prepare", movedCount == 0
                ? "Output folder was already empty"
                : "Moved " + movedCount + " existing output item(s) to " + OutputCleaner.trashName());
        }

        Files.createDirectories(output);
        if (!options.tbxOnly() && !options.tilesOnly()) {
            Files.createDirectories(output.resolve("tmx"));
        }
    }

    /** Cell size of Build 42 compiled maps; they can only be exported to this native grid. */
    public static final int BUILD42_CELL_SIZE = 256;

    /**
     * Resolves the output grid. {@code null} (auto) matches the source: 256 when any source cell is a
     * Build 42 256x256 cell, otherwise 300. Build 42 cells can only be written to the native 256 grid;
     * converting them to the legacy 300 grid is not supported.
     */
    public static int resolveTargetCellSize(Integer requested, Collection<LotHeaderData> headers) throws InvalidDataException {
        boolean hasBuild42Cells = false;
        for (LotHeaderData header : headers) {
            if (header.cellDim() == BUILD42_CELL_SIZE) {
                hasBuild42Cells = true;
                break;
            }
        }

        if (requested == null) {
            return hasBuild42Cells ? BUILD42_CELL_SIZE : 300;
        }

        if (hasBuild42Cells && requested != BUILD42_CELL_SIZE) {
            throw new InvalidDataException("Build 42 maps (256x256 cells) can only be exported to native 256x256 cells; "
                + requested + "x" + requested + " output is not supported. Use --target-cell-size 256 or auto.");
        }

        return requested;
    }

    /** Number of target cells covered by the given source cells. */
    public static int estimateTargetCellCount(Collection<LotHeaderData> headers, int targetCellSize) {
        int[] bounds = targetBounds(headers, targetCellSize);
        return (bounds[2] - bounds[0] + 1) * (bounds[3] - bounds[1] + 1);
    }

    private static int[] targetBounds(Collection<LotHeaderData> headers, int targetCellSize) {
        int minWorldX = Integer.MAX_VALUE;
        int minWorldY = Integer.MAX_VALUE;
        int maxWorldX = Integer.MIN_VALUE;
        int maxWorldY = Integer.MIN_VALUE;
        for (LotHeaderData header : headers) {
            minWorldX = Math.min(minWorldX, header.minSquareX());
            minWorldY = Math.min(minWorldY, header.minSquareY());
            maxWorldX = Math.max(maxWorldX, header.maxSquareX());
            maxWorldY = Math.max(maxWorldY, header.maxSquareY());
        }

        return new int[] {
            BinaryHelpers.floorDiv(minWorldX, targetCellSize),
            BinaryHelpers.floorDiv(minWorldY, targetCellSize),
            BinaryHelpers.floorDiv(maxWorldX, targetCellSize),
            BinaryHelpers.floorDiv(maxWorldY, targetCellSize)
        };
    }

    private void createTargetCellGrid(Collection<LotHeaderData> headers) {
        int[] bounds = targetBounds(headers, targetCellSize);
        for (int x = bounds[0]; x <= bounds[2]; x++) {
            for (int y = bounds[1]; y <= bounds[3]; y++) {
                getTargetCell(new CellCoord(x, y));
            }
        }
    }

    private void readLotPacks(Collection<LotHeaderData> headers, TileSetCatalog tileSets) throws IOException {
        List<LotHeaderData> orderedHeaders = new ArrayList<>(headers);
        orderedHeaders.sort(Comparator.comparingInt(LotHeaderData::cellX).thenComparingInt(LotHeaderData::cellY));
        for (int i = 0; i < orderedHeaders.size(); i++) {
            LotHeaderData header = orderedHeaders.get(i);
            report("Lotpacks", "Reading world_" + header.cellX() + "_" + header.cellY() + ".lotpack", i + 1, orderedHeaders.size());
            LotPackReader reader = new LotPackReader(header, options.inputDirectory());
            if (!reader.exists()) {
                System.out.println("Warning: missing world_" + header.cellX() + "_" + header.cellY() + ".lotpack");
                continue;
            }

            TargetCellCache cache = new TargetCellCache();
            reader.read((localX, localY, floor, layer, tileName) -> {
                int worldX = header.minSquareX() + localX;
                int worldY = header.minSquareY() + localY;
                int targetX = BinaryHelpers.floorDiv(worldX, targetCellSize);
                int targetY = BinaryHelpers.floorDiv(worldY, targetCellSize);
                int targetLocalX = BinaryHelpers.positiveMod(worldX, targetCellSize);
                int targetLocalY = BinaryHelpers.positiveMod(worldY, targetCellSize);

                tileSets.observe(tileName);
                cache.get(targetX, targetY).addTile(targetLocalX, targetLocalY, floor, layer, tileName, targetCellSize);
            });
        }
    }

    /** Remembers the last target cell, since consecutive tiles almost always land in the same one. */
    private final class TargetCellCache {
        private int x = Integer.MIN_VALUE;
        private int y = Integer.MIN_VALUE;
        private TargetCell cell;

        TargetCell get(int targetX, int targetY) {
            if (cell == null || targetX != x || targetY != y) {
                cell = getTargetCell(new CellCoord(targetX, targetY));
                x = targetX;
                y = targetY;
            }

            return cell;
        }
    }

    /** Copies RoomDefs into the target cells, except rooms drawn by a building lot. */
    private void reprojectRooms(Collection<LotHeaderData> headers, BuildingTbxWriter buildings) {
        int size = this.targetCellSize;
        for (LotHeaderData header : headers) {
            for (RoomRect room : header.rooms()) {
                if (room.width() <= 0 || room.height() <= 0
                    || (buildings != null && buildings.isLotRoom(header.cellX(), header.cellY(), room.sourceRoomId()))) {
                    continue;
                }

                int minCellX = BinaryHelpers.floorDiv(room.x(), size);
                int minCellY = BinaryHelpers.floorDiv(room.y(), size);
                int maxCellX = BinaryHelpers.floorDiv(room.right() - 1, size);
                int maxCellY = BinaryHelpers.floorDiv(room.bottom() - 1, size);

                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                        int cellMinX = cellX * size;
                        int cellMinY = cellY * size;
                        int clipLeft = Math.max(room.x(), cellMinX);
                        int clipTop = Math.max(room.y(), cellMinY);
                        int clipRight = Math.min(room.right(), cellMinX + size);
                        int clipBottom = Math.min(room.bottom(), cellMinY + size);

                        if (clipRight <= clipLeft || clipBottom <= clipTop) {
                            continue;
                        }

                        getTargetCell(new CellCoord(cellX, cellY)).rooms().add(new RoomRect(
                            room.sourceRoomId(),
                            room.name(),
                            room.floor(),
                            clipLeft - cellMinX,
                            clipTop - cellMinY,
                            clipRight - clipLeft,
                            clipBottom - clipTop));
                    }
                }
            }
        }
    }

    private List<MapObject> readObjects() throws IOException {
        Path objectsFile = Paths.get(options.inputDirectory(), "objects.lua");
        if (!Files.isRegularFile(objectsFile)) {
            return List.of();
        }

        Set<CellCoord> outputCells = new HashSet<>(targetCells.keySet());
        List<MapObject> result = new ArrayList<>();
        for (MapObject mapObject : ObjectsLuaReader.read(objectsFile)) {
            if (mapObject.canWrite() && outputCells.contains(mapObject.getCell(targetCellSize))) {
                result.add(mapObject);
            }
        }

        return result;
    }

    private TargetCell getTargetCell(CellCoord coord) {
        return targetCells.computeIfAbsent(coord, TargetCell::new);
    }
}
