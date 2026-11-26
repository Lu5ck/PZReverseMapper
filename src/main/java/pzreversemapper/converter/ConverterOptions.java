package pzreversemapper.converter;

import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Export settings shared by the command line and Studio. */
public final class ConverterOptions {
    public static final String DEFAULT_PROJECT_NAME = "ConvertedMap";

    private final String inputDirectory;
    private final String outputDirectory;
    private final String projectName;
    private final String tilesPath;
    private final String modTilesPath;
    private final Integer expectedSourceCellSize;
    private final Integer targetCellSize;
    private final boolean cleanOutput;
    private boolean cleanOutputConfirmed;
    private final boolean exportImages;
    private final boolean exportTilePacks;
    private final boolean exportObjects;
    private final boolean exportBuildingTbx;
    private final boolean tbxOnly;
    private final boolean tilesOnly;
    private final Set<CellCoord> includeCells;
    private final String tbxConfigPath;

    private ConverterOptions(Builder builder) {
        if (builder.inputDirectory == null || builder.outputDirectory == null) {
            throw new IllegalArgumentException("Input and output directories are required.");
        }

        inputDirectory = builder.inputDirectory;
        outputDirectory = builder.outputDirectory;
        projectName = builder.projectName;
        tilesPath = builder.tilesPath;
        modTilesPath = builder.modTilesPath;
        expectedSourceCellSize = builder.expectedSourceCellSize;
        targetCellSize = builder.targetCellSize;
        cleanOutput = builder.cleanOutput;
        exportImages = builder.exportImages;
        exportTilePacks = builder.exportTilePacks;
        exportObjects = builder.exportObjects;
        exportBuildingTbx = builder.exportBuildingTbx;
        tbxOnly = builder.tbxOnly;
        tilesOnly = builder.tilesOnly;
        includeCells = builder.includeCells == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(builder.includeCells));
        tbxConfigPath = builder.tbxConfigPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String inputDirectory() {
        return inputDirectory;
    }

    public String outputDirectory() {
        return outputDirectory;
    }

    public String projectName() {
        return projectName;
    }

    public String tilesPath() {
        return tilesPath;
    }

    public String modTilesPath() {
        return modTilesPath;
    }

    public Integer expectedSourceCellSize() {
        return expectedSourceCellSize;
    }

    /** Requested output cell size, or {@code null} to match the source grid (see {@link MapConverter#resolveTargetCellSize}). */
    public Integer targetCellSize() {
        return targetCellSize;
    }

    public boolean cleanOutput() {
        return cleanOutput;
    }

    public boolean cleanOutputConfirmed() {
        return cleanOutputConfirmed;
    }

    public void setCleanOutputConfirmed(boolean confirmed) {
        cleanOutputConfirmed = confirmed;
    }

    public boolean exportImages() {
        return exportImages;
    }

    public boolean exportTilePacks() {
        return exportTilePacks;
    }

    public boolean exportObjects() {
        return exportObjects;
    }

    public boolean exportBuildingTbx() {
        return exportBuildingTbx;
    }

    public boolean tbxOnly() {
        return tbxOnly;
    }

    public boolean tilesOnly() {
        return tilesOnly;
    }

    /** Optional source-cell filter, or {@code null} to export every cell. */
    public Set<CellCoord> includeCells() {
        return includeCells;
    }

    /**
     * Folder with the BuildingEd config files (BuildingTiles.txt, BuildingFurniture.txt, TMXConfig.txt)
     * from PZ_Mapping_Tools, used to rebuild building TBX files; {@code null} keeps building tiles as user tiles.
     */
    public String tbxConfigPath() {
        return tbxConfigPath;
    }

    /** The vanilla tiles path followed by the optional mod assets path. */
    public List<String> enumerateTilesAssetPaths() {
        List<String> paths = new ArrayList<>(2);
        if (!Text.isNullOrWhiteSpace(tilesPath)) {
            paths.add(tilesPath);
        }

        if (!Text.isNullOrWhiteSpace(modTilesPath)) {
            paths.add(modTilesPath);
        }

        return paths;
    }

    /** Equivalent of {@code Path.GetFullPath}. */
    public static String fullPath(String path) {
        return Paths.get(path).toAbsolutePath().normalize().toString();
    }

    /**
     * Parses command-line arguments.
     *
     * @return the options, or {@code null} when required arguments are missing (usage should be shown)
     * @throws IllegalArgumentException for malformed arguments
     */
    public static ConverterOptions parse(String[] args) {
        if (args.length == 0) {
            return null;
        }

        String input = null;
        String output = null;
        String name = null;
        String tiles = null;
        String modTiles = null;
        Integer expectedSourceCellSize = null;
        Integer targetCellSize = null;
        boolean clean = false;
        boolean exportImages = true;
        boolean exportTilePacks = false;
        boolean exportObjects = true;
        boolean exportBuildingTbx = true;
        boolean tbxOnly = false;
        boolean tilesOnly = false;
        Set<CellCoord> includeCells = null;
        String tbxConfig = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg.toLowerCase(Locale.ROOT)) {
                case "--input", "-i" -> input = args[nextIndex(args, i++, arg)];
                case "--output", "-o" -> output = args[nextIndex(args, i++, arg)];
                case "--name", "-n" -> name = args[nextIndex(args, i++, arg)];
                case "--tiles", "-t" -> tiles = args[nextIndex(args, i++, arg)];
                case "--mod-tiles", "--mod-media" -> modTiles = args[nextIndex(args, i++, arg)];
                case "--target-cell-size" -> {
                    String text = args[nextIndex(args, i++, arg)];
                    if (Text.trim(text).equalsIgnoreCase("auto")) {
                        targetCellSize = null;
                    } else {
                        Integer value = Text.tryParseInt(text);
                        if (value == null || value <= 0) {
                            throw new IllegalArgumentException("--target-cell-size must be auto or a positive integer");
                        }

                        targetCellSize = value;
                    }
                }
                case "--source-cell-size" -> {
                    Integer value = Text.tryParseInt(args[nextIndex(args, i++, arg)]);
                    if (value == null || value <= 0) {
                        throw new IllegalArgumentException("--source-cell-size must be a positive integer");
                    }

                    expectedSourceCellSize = value;
                }
                case "--cells" -> includeCells = parseCells(args[nextIndex(args, i++, arg)]);
                case "--tbx-config", "--buildinged-config" -> tbxConfig = args[nextIndex(args, i++, arg)];
                case "--clean" -> clean = true;
                case "--no-objects" -> exportObjects = false;
                case "--no-images" -> exportImages = false;
                case "--extract-tiles" -> exportTilePacks = true;
                case "--tiles-only" -> {
                    tilesOnly = true;
                    exportTilePacks = true;
                    exportImages = false;
                    exportObjects = false;
                    exportBuildingTbx = false;
                    tbxOnly = false;
                }
                // RoomDef TBX files are no longer written (buildings are in tbx_buildings/); kept for old scripts.
                case "--no-room-tbx" -> {
                }
                case "--no-building-tbx" -> exportBuildingTbx = false;
                case "--tbx-only" -> {
                    tbxOnly = true;
                    exportImages = false;
                    exportObjects = false;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (Text.isNullOrWhiteSpace(output) || (!tilesOnly && Text.isNullOrWhiteSpace(input))) {
            return null;
        }

        if (tilesOnly && Text.isNullOrWhiteSpace(tiles) && Text.isNullOrWhiteSpace(modTiles)) {
            throw new IllegalArgumentException("--tiles-only requires --tiles and/or --mod-tiles");
        }

        return builder()
            .inputDirectory(Text.isNullOrWhiteSpace(input) ? "" : fullPath(input))
            .outputDirectory(fullPath(output))
            .projectName(Text.isNullOrWhiteSpace(name) ? DEFAULT_PROJECT_NAME : name)
            .tilesPath(Text.isNullOrWhiteSpace(tiles) ? null : fullPath(tiles))
            .modTilesPath(Text.isNullOrWhiteSpace(modTiles) ? null : fullPath(modTiles))
            .expectedSourceCellSize(expectedSourceCellSize)
            .targetCellSize(targetCellSize)
            .cleanOutput(clean)
            .exportImages(exportImages)
            .exportTilePacks(exportTilePacks)
            .exportObjects(exportObjects)
            .exportBuildingTbx(exportBuildingTbx)
            .tbxOnly(tbxOnly)
            .tilesOnly(tilesOnly)
            .includeCells(includeCells)
            .tbxConfigPath(Text.isNullOrWhiteSpace(tbxConfig) ? null : fullPath(tbxConfig))
            .build();
    }

    private static int nextIndex(String[] args, int index, String arg) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + arg);
        }

        return index + 1;
    }

    /** Splits a cell list on ',', ';' and ' ', trimming entries and dropping empty ones. */
    public static List<String> splitCellTokens(String value) {
        return splitTrimmed(value, ",; ");
    }

    /** Parses one {@code X_Y} token, or returns {@code null} when it is malformed. */
    public static CellCoord parseCellToken(String token) {
        List<String> parts = splitTrimmed(token, "_");
        if (parts.size() != 2) {
            return null;
        }

        Integer x = Text.tryParseInt(parts.get(0));
        Integer y = Text.tryParseInt(parts.get(1));
        return x == null || y == null ? null : new CellCoord(x, y);
    }

    private static Set<CellCoord> parseCells(String value) {
        Set<CellCoord> cells = new LinkedHashSet<>();
        for (String token : splitCellTokens(value)) {
            CellCoord cell = parseCellToken(token);
            if (cell == null) {
                throw new IllegalArgumentException("Invalid cell coordinate: " + token + ". Expected X_Y.");
            }

            cells.add(cell);
        }

        if (cells.isEmpty()) {
            throw new IllegalArgumentException("--cells must contain at least one X_Y coordinate.");
        }

        return cells;
    }

    private static List<String> splitTrimmed(String value, String separators) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || separators.indexOf(value.charAt(i)) >= 0) {
                String token = Text.trim(value.substring(start, i));
                if (!token.isEmpty()) {
                    tokens.add(token);
                }

                start = i + 1;
            }
        }

        return tokens;
    }

    public static void printUsage(PrintStream out) {
        String command = AppInfo.cliCommand();
        out.println("PZ Reverse Mapper CLI");
        out.println("Project Zomboid compiled map reverse exporter");
        out.println();
        out.println("Usage:");
        out.println("  " + command + " --input <map-folder> --output <export-folder> [options]");
        out.println("  " + command + " --output <export-folder> --tiles <media-folder> --tiles-only");
        out.println("  " + command + "                (no arguments) start the Studio window");
        out.println();
        out.println("Options:");
        out.println("  --tiles <file-or-folder>       Vanilla game media folder, .tiles file, or folder containing .tiles files");
        out.println("  --mod-tiles <folder>           Optional mod asset root read after --tiles; alias: --mod-media");
        out.println("  --name <project-name>          PZW filename without extension (default: ConvertedMap)");
        out.println("  --source-cell-size <size>      Optional input validation, for example 300 or 256");
        out.println("  --target-cell-size <size>      Output cell size: auto (default, matches the source), 300 or 256;");
        out.println("                                 Build 42 256x256 maps always stay 256");
        out.println("  --cells X_Y[,X_Y...]           Optional source-cell filter, for example 46_26,46_27");
        out.println("  --clean                        Move existing output contents to the Recycle Bin/Trash after confirmation");
        out.println("  --tbx-only                     Write the building TBX files only; skip TMX, PZW, and objects.lua");
        out.println("  --no-images                    Do not write preview map images");
        out.println("  --extract-tiles                Extract .pack atlases from --tiles and optional --mod-tiles");
        out.println("  --tiles-only                   Extract tiles without reading map headers or lotpacks");
        out.println("  --no-objects                   Do not import objects.lua into the PZW");
        out.println("  --no-building-tbx              Do not write building TBX files or lots; the TMX keeps every tile");
        out.println("  --tbx-config <folder>          BuildingEd config folder (BuildingTiles.txt, BuildingFurniture.txt,");
        out.println("                                 TMXConfig.txt) from PZ_Mapping_Tools: rebuild building TBX files with");
        out.println("                                 rooms, walls, floors, doors, windows, stairs and furniture");
        out.println("  --gui                          Start the Studio window");
    }

    /** Mutable builder; {@code inputDirectory} and {@code outputDirectory} are required. */
    public static final class Builder {
        private String inputDirectory;
        private String outputDirectory;
        private String projectName = DEFAULT_PROJECT_NAME;
        private String tilesPath;
        private String modTilesPath;
        private Integer expectedSourceCellSize;
        private Integer targetCellSize;
        private boolean cleanOutput;
        private boolean exportImages = true;
        private boolean exportTilePacks;
        private boolean exportObjects = true;
        private boolean exportBuildingTbx = true;
        private boolean tbxOnly;
        private boolean tilesOnly;
        private Set<CellCoord> includeCells;
        private String tbxConfigPath;

        private Builder() {
        }

        public Builder inputDirectory(String value) {
            inputDirectory = value;
            return this;
        }

        public Builder outputDirectory(String value) {
            outputDirectory = value;
            return this;
        }

        public Builder projectName(String value) {
            projectName = value;
            return this;
        }

        public Builder tilesPath(String value) {
            tilesPath = value;
            return this;
        }

        public Builder modTilesPath(String value) {
            modTilesPath = value;
            return this;
        }

        public Builder expectedSourceCellSize(Integer value) {
            expectedSourceCellSize = value;
            return this;
        }

        /** Output cell size, or {@code null} for auto (match the source grid). */
        public Builder targetCellSize(Integer value) {
            targetCellSize = value;
            return this;
        }

        public Builder cleanOutput(boolean value) {
            cleanOutput = value;
            return this;
        }

        public Builder exportImages(boolean value) {
            exportImages = value;
            return this;
        }

        public Builder exportTilePacks(boolean value) {
            exportTilePacks = value;
            return this;
        }

        public Builder exportObjects(boolean value) {
            exportObjects = value;
            return this;
        }

        public Builder exportBuildingTbx(boolean value) {
            exportBuildingTbx = value;
            return this;
        }

        public Builder tbxOnly(boolean value) {
            tbxOnly = value;
            return this;
        }

        public Builder tilesOnly(boolean value) {
            tilesOnly = value;
            return this;
        }

        public Builder includeCells(Set<CellCoord> value) {
            includeCells = value;
            return this;
        }

        public Builder tbxConfigPath(String value) {
            tbxConfigPath = value;
            return this;
        }

        public ConverterOptions build() {
            return new ConverterOptions(this);
        }
    }
}
