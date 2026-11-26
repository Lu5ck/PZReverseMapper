package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts Project Zomboid {@code .pack} texture packs: raw atlases, individual tiles restored to
 * their real canvas, and rebuilt eight-column tilesets.
 */
final class TilesPackExtractor {
    private static final int NEW_PACK_MAGIC = 1263557200;
    private static final int TILES_PER_ROW = 8;
    private static final long PNG_SENTINEL = 0xDEADBEEFL;
    private static final long PNG_IEND_SENTINEL = 0xAE444E45L;

    private TilesPackExtractor() {
    }

    /** Counts of images written by one extraction. */
    record Result(int rawImageCount, int individualTileCount, int tileSheetCount) {
        static final Result EMPTY = new Result(0, 0, 0);

        int totalImageCount() {
            return rawImageCount + individualTileCount + tileSheetCount;
        }
    }

    static Result extract(Collection<String> tilesPaths, String outputDirectory) throws IOException {
        List<String> paths = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (String path : tilesPaths) {
            if (Text.isNullOrWhiteSpace(path)) {
                continue;
            }

            String fullPath = ConverterOptions.fullPath(path);
            if (seenPaths.add(Text.foldCase(fullPath))) {
                paths.add(fullPath);
            }
        }

        if (paths.isEmpty()) {
            return Result.EMPTY;
        }

        List<Path> packFiles = new ArrayList<>();
        Set<String> seenPacks = new HashSet<>();
        for (String path : paths) {
            for (Path file : resolveFiles(Paths.get(path), ".pack", "texturepacks", "texturespack", "texturepack")) {
                if (seenPacks.add(Text.foldCase(file.toString()))) {
                    packFiles.add(file);
                }
            }
        }

        if (packFiles.isEmpty()) {
            return Result.EMPTY;
        }

        Path rawRoot = Paths.get(outputDirectory, "TilesRaw");
        Path singleRoot = Paths.get(outputDirectory, "TilesSingle");
        Path tilesRoot = Paths.get(outputDirectory, "Tiles");
        Files.createDirectories(rawRoot);
        Files.createDirectories(singleRoot);
        Files.createDirectories(tilesRoot);

        List<PackedTile> packedTiles = new ArrayList<>();
        Set<String> usedPackDirectoryNames = new HashSet<>();
        Set<String> usedRawPackDirectoryNames = new HashSet<>();
        Map<String, String> logicalPackDirectories = new HashMap<>();
        int rawImageCount = 0;
        for (Path packFile : packFiles) {
            String logicalPackIdentity = Text.foldCase(getLogicalPackIdentity(packFile));
            String packDirectoryName = logicalPackDirectories.get(logicalPackIdentity);
            if (packDirectoryName == null) {
                packDirectoryName = reserveUniqueDirectoryName(getLogicalPackFileName(packFile), usedPackDirectoryNames);
                logicalPackDirectories.put(logicalPackIdentity, packDirectoryName);
            }

            String rawPackDirectoryName = reserveUniqueDirectoryName(packFile.getFileName().toString(), usedRawPackDirectoryNames);
            rawImageCount += extractPack(packFile, rawPackDirectoryName, packDirectoryName, rawRoot, packedTiles);
        }

        List<ParsedPackedTile> parsedTiles = parsePackedTiles(packedTiles);
        int individualTileCount = extractIndividualTiles(parsedTiles, rawRoot, singleRoot);
        int tileSheetCount = reconstructTileSheets(parsedTiles, singleRoot, tilesRoot);

        return new Result(rawImageCount, individualTileCount, tileSheetCount);
    }

    private static List<Path> resolveFiles(Path path, String extension, String... preferredChildren) throws IOException {
        List<Path> result = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            if (Text.extension(path.toString()).equalsIgnoreCase(extension)) {
                result.add(path.toAbsolutePath().normalize());
            }

            return result;
        }

        if (!Files.isDirectory(path)) {
            return result;
        }

        List<Path> childRoots = new ArrayList<>();
        for (String child : preferredChildren) {
            Path childRoot = findChildDirectory(path, child);
            if (childRoot != null) {
                childRoots.add(childRoot);
            }
        }

        List<Path> searchRoots = childRoots.isEmpty() ? List.of(path) : childRoots;
        List<Path> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path root : searchRoots) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()
                        && Text.endsWithIgnoreCase(file.getFileName().toString(), extension)
                        && seen.add(Text.foldCase(file.toString()))) {
                        files.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        files.sort((a, b) -> Text.compareOrdinalIgnoreCase(a.toString(), b.toString()));
        result.addAll(files);
        return result;
    }

    /** Finds a direct child directory by name, ignoring case so Linux matches Windows behaviour. */
    private static Path findChildDirectory(Path parent, String name) throws IOException {
        Path exact = parent.resolve(name);
        if (Files.isDirectory(exact)) {
            return exact;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equalsIgnoreCase(name) && Files.isDirectory(entry)) {
                    return entry;
                }
            }
        }

        return null;
    }

    private static int extractPack(
        Path packFile,
        String rawPackDirectoryName,
        String packDirectoryName,
        Path rawRoot,
        List<PackedTile> packedTiles) throws IOException {
        int written = 0;
        try (LittleEndianReader reader = new LittleEndianReader(packFile)) {
            int sheetCount = reader.readInt();
            boolean newFormat = false;
            if (sheetCount == NEW_PACK_MAGIC) {
                reader.readInt();
                sheetCount = reader.readInt();
                newFormat = true;
            }

            Path packOutputDirectory = rawRoot.resolve(rawPackDirectoryName);
            Files.createDirectories(packOutputDirectory);

            for (int sheet = 0; sheet < sheetCount && reader.hasRemaining(); sheet++) {
                String atlasName = reader.readUtf8Chars(reader.readInt());
                int tileCount = reader.readInt();
                reader.readInt();

                for (int tile = 0; tile < tileCount && reader.hasRemaining(); tile++) {
                    int tileNameLength = reader.readInt();
                    if (tileNameLength == 0) {
                        break;
                    }

                    String tileName = reader.readUtf8Chars(tileNameLength);
                    int x = reader.readInt();
                    int y = reader.readInt();
                    int width = reader.readInt();
                    int height = reader.readInt();
                    int offsetX = reader.readInt();
                    int offsetY = reader.readInt();
                    int tileWidth = reader.readInt();
                    int tileHeight = reader.readInt();
                    packedTiles.add(new PackedTile(
                        tileName, atlasName, x, y, width, height, offsetX, offsetY, tileWidth, tileHeight,
                        rawPackDirectoryName, packDirectoryName));
                }

                ExtractedImage image = tryExtractImage(reader, newFormat);
                if (image != null) {
                    Path rawFile = packOutputDirectory.resolve(Text.sanitizeFileName(atlasName) + image.extension());
                    Files.write(rawFile, image.bytes());
                    written++;
                }
            }
        }

        return written;
    }

    private static String getLogicalPackIdentity(Path packFile) {
        Path parent = packFile.toAbsolutePath().normalize().getParent();
        String fileName = getLogicalPackFileName(packFile);
        return parent == null ? fileName : parent.resolve(fileName).toString();
    }

    private static String getLogicalPackFileName(Path packFile) {
        String floorSuffix = ".floor.pack";
        String fileName = packFile.getFileName().toString();
        return Text.endsWithIgnoreCase(fileName, floorSuffix)
            ? fileName.substring(0, fileName.length() - floorSuffix.length()) + ".pack"
            : fileName;
    }

    private record ExtractedImage(String extension, byte[] bytes) {
    }

    private static ExtractedImage tryExtractImage(LittleEndianReader reader, boolean newFormat) throws IOException {
        if (newFormat) {
            if (reader.position() + 4 > reader.length()) {
                return null;
            }

            int dataLength = reader.readInt();
            if (dataLength <= 0 || reader.position() + dataLength > reader.length()) {
                return null;
            }

            long start = reader.position();
            PackImageKind kind = peekImageKind(reader);
            reader.position(start);
            byte[] imageBytes = reader.readBytes(dataLength);
            return kind == PackImageKind.UNKNOWN
                ? null
                : new ExtractedImage(kind == PackImageKind.DDS ? ".dds" : ".png", imageBytes);
        }

        PackImageKind imageKind = peekImageKind(reader);
        if (imageKind == PackImageKind.PNG) {
            byte[] imageBytes = readPngBytes(reader);
            skipUntilSentinel(reader);
            return new ExtractedImage(".png", imageBytes);
        }

        if (imageKind == PackImageKind.DDS) {
            return new ExtractedImage(".dds", readDdsBytes(reader));
        }

        return null;
    }

    private static List<ParsedPackedTile> parsePackedTiles(List<PackedTile> packedTiles) {
        List<ParsedPackedTile> parsed = new ArrayList<>();
        for (int order = 0; order < packedTiles.size(); order++) {
            PackedTile tile = packedTiles.get(order);
            int index = tile.tileName().lastIndexOf('_');
            if (index <= 0 || index >= tile.tileName().length() - 1) {
                continue;
            }

            Integer tileId = Text.tryParseInt(tile.tileName().substring(index + 1));
            if (tileId == null || tileId < 0 || tile.tileWidth() <= 0 || tile.tileHeight() <= 0) {
                continue;
            }

            parsed.add(new ParsedPackedTile(tile, tile.tileName().substring(0, index), tileId, order));
        }

        return parsed;
    }

    private static int extractIndividualTiles(List<ParsedPackedTile> parsedTiles, Path rawRoot, Path singleRoot) throws IOException {
        Map<AtlasKey, List<ParsedPackedTile>> atlasGroups = new LinkedHashMap<>();
        for (ParsedPackedTile item : parsedTiles) {
            atlasGroups
                .computeIfAbsent(new AtlasKey(item.tile().rawPackDirectoryName(), item.tile().atlasName()), key -> new ArrayList<>())
                .add(item);
        }

        int written = 0;
        Set<String> createdDirectories = new HashSet<>();
        for (Map.Entry<AtlasKey, List<ParsedPackedTile>> atlasGroup : atlasGroups.entrySet()) {
            Path atlasPath = rawRoot
                .resolve(atlasGroup.getKey().rawPackDirectoryName())
                .resolve(Text.sanitizeFileName(atlasGroup.getKey().atlasName()) + ".png");
            if (!Files.isRegularFile(atlasPath)) {
                continue;
            }

            PngImage atlas;
            try {
                atlas = PngImage.load(atlasPath);
            } catch (IOException | RuntimeException ex) {
                System.out.println("Warning: failed to load atlas " + atlasPath + ": " + Text.messageOf(ex));
                continue;
            }

            for (ParsedPackedTile item : atlasGroup.getValue()) {
                PackedTile tile = item.tile();
                PngImage singleTile = new PngImage(tile.tileWidth(), tile.tileHeight());
                singleTile.drawImage(atlas, tile.x(), tile.y(), tile.width(), tile.height(), tile.offsetX(), tile.offsetY());

                Path tileDirectory = singleRoot
                    .resolve(tile.packDirectoryName())
                    .resolve(Text.sanitizeFileName(item.sheetName()));
                if (createdDirectories.add(Text.foldCase(tileDirectory.toString()))) {
                    Files.createDirectories(tileDirectory);
                }

                singleTile.save(tileDirectory.resolve(Text.sanitizeFileName(tile.tileName()) + ".png"));
                written++;
            }
        }

        return written;
    }

    private static int reconstructTileSheets(List<ParsedPackedTile> parsedTiles, Path singleRoot, Path tilesRoot) throws IOException {
        Map<String, SheetGroup> groups = new LinkedHashMap<>();
        for (ParsedPackedTile item : parsedTiles) {
            String key = Text.foldCase(item.tile().packDirectoryName()) + '\u0000' + Text.foldCase(item.sheetName());
            groups
                .computeIfAbsent(key, ignored -> new SheetGroup(item.tile().packDirectoryName(), item.sheetName()))
                .tiles
                .add(item);
        }

        List<SheetGroup> byPackAndSheet = new ArrayList<>(groups.values());
        byPackAndSheet.sort((a, b) -> {
            int compare = Text.compareOrdinalIgnoreCase(a.packDirectoryName, b.packDirectoryName);
            return compare != 0 ? compare : Text.compareOrdinalIgnoreCase(a.sheetName, b.sheetName);
        });

        int written = 0;
        int sheetsWithMultipleSizes = 0;
        for (SheetGroup group : byPackAndSheet) {
            List<ParsedPackedTile> candidates = group.tiles;
            PackedTile last = candidates.get(candidates.size() - 1).tile();
            int cellWidth = last.tileWidth();
            int cellHeight = last.tileHeight();

            List<ParsedPackedTile> matchingCandidates = new ArrayList<>();
            for (ParsedPackedTile item : candidates) {
                if (item.tile().tileWidth() == cellWidth && item.tile().tileHeight() == cellHeight) {
                    matchingCandidates.add(item);
                }
            }

            if (matchingCandidates.size() != candidates.size()) {
                sheetsWithMultipleSizes++;
            }

            Map<Integer, ParsedPackedTile> latestById = new LinkedHashMap<>();
            for (ParsedPackedTile item : matchingCandidates) {
                ParsedPackedTile existing = latestById.get(item.tileId());
                if (existing == null || item.order() > existing.order()) {
                    latestById.put(item.tileId(), item);
                }
            }

            List<ParsedPackedTile> sourceTiles = new ArrayList<>(latestById.values());
            sourceTiles.sort((a, b) -> Integer.compare(a.order(), b.order()));
            if (sourceTiles.isEmpty()) {
                continue;
            }

            int maxTileId = 0;
            for (ParsedPackedTile item : sourceTiles) {
                maxTileId = Math.max(maxTileId, item.tileId());
            }

            int rows = (maxTileId / TILES_PER_ROW) + 1;
            PngImage output = new PngImage(TILES_PER_ROW * cellWidth, rows * cellHeight);

            for (ParsedPackedTile item : sourceTiles) {
                Path singleTilePath = singleRoot
                    .resolve(group.packDirectoryName)
                    .resolve(Text.sanitizeFileName(group.sheetName))
                    .resolve(Text.sanitizeFileName(item.tile().tileName()) + ".png");
                if (!Files.isRegularFile(singleTilePath)) {
                    continue;
                }

                PngImage singleTile;
                try {
                    singleTile = PngImage.load(singleTilePath);
                } catch (IOException | RuntimeException ex) {
                    System.out.println("Warning: failed to load tile " + singleTilePath + ": " + Text.messageOf(ex));
                    continue;
                }

                if (singleTile.width() != cellWidth || singleTile.height() != cellHeight) {
                    System.out.println("Warning: skipped " + item.tile().tileName() + ": expected " + cellWidth + "x" + cellHeight
                        + ", found " + singleTile.width() + "x" + singleTile.height());
                    continue;
                }

                int left = (item.tileId() % TILES_PER_ROW) * cellWidth;
                int top = (item.tileId() / TILES_PER_ROW) * cellHeight;
                output.drawImage(singleTile, left, top);
            }

            Path packTilesDirectory = tilesRoot.resolve(group.packDirectoryName);
            Files.createDirectories(packTilesDirectory);
            output.save(packTilesDirectory.resolve(Text.sanitizeFileName(group.sheetName) + ".png"));
            written++;
        }

        if (sheetsWithMultipleSizes > 0) {
            System.out.println("Tile packs: selected one consistent tile size for " + sheetsWithMultipleSizes
                + " pack tileset(s) containing multiple resolutions.");
        }

        return written;
    }

    private static PackImageKind peekImageKind(LittleEndianReader reader) throws IOException {
        if (reader.position() + 4 > reader.length()) {
            return PackImageKind.UNKNOWN;
        }

        long start = reader.position();
        byte[] bytes = reader.readBytes(4);
        reader.position(start);

        if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return PackImageKind.PNG;
        }

        return bytes[0] == 0x44 && bytes[1] == 0x44 && bytes[2] == 0x53 && bytes[3] == 0x20
            ? PackImageKind.DDS
            : PackImageKind.UNKNOWN;
    }

    private static byte[] readPngBytes(LittleEndianReader reader) throws IOException {
        long start = reader.position();
        reader.skip(8);
        while (reader.position() + 12 <= reader.length()) {
            int length = readBigEndianInt(reader);
            byte[] typeBytes = reader.readBytes(4);
            String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
            if (length < 0) {
                throw new InvalidDataException("Invalid PNG chunk length inside texture pack");
            }

            reader.skip(length + 4L);
            if (type.equals("IEND")) {
                break;
            }
        }

        long end = reader.position();
        reader.position(start);
        return reader.readBytes(Math.toIntExact(end - start));
    }

    private static byte[] readDdsBytes(LittleEndianReader reader) throws IOException {
        long start = reader.position();
        skipUntilSentinel(reader);
        long end = reader.position();
        reader.position(start);
        return reader.readBytes(Math.toIntExact(end - start));
    }

    private static void skipUntilSentinel(LittleEndianReader reader) throws IOException {
        while (reader.position() + 4 <= reader.length()) {
            long value = reader.readUInt32();
            if (value == PNG_SENTINEL || value == PNG_IEND_SENTINEL) {
                break;
            }
        }
    }

    private static int readBigEndianInt(LittleEndianReader reader) throws IOException {
        byte[] bytes = reader.readBytes(4);
        if (bytes.length != 4) {
            throw new java.io.EOFException("Unable to read beyond the end of the stream.");
        }

        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    private static String reserveUniqueDirectoryName(String value, Set<String> usedNames) {
        String sanitized = Text.sanitizeFileName(value);
        if (usedNames.add(Text.foldCase(sanitized))) {
            return sanitized;
        }

        for (int suffix = 2; ; suffix++) {
            String candidate = sanitized + "_" + suffix;
            if (usedNames.add(Text.foldCase(candidate))) {
                return candidate;
            }
        }
    }

    private enum PackImageKind {
        UNKNOWN,
        PNG,
        DDS
    }

    private record AtlasKey(String rawPackDirectoryName, String atlasName) {
    }

    private static final class SheetGroup {
        private final String packDirectoryName;
        private final String sheetName;
        private final List<ParsedPackedTile> tiles = new ArrayList<>();

        private SheetGroup(String packDirectoryName, String sheetName) {
            this.packDirectoryName = Objects.requireNonNull(packDirectoryName);
            this.sheetName = Objects.requireNonNull(sheetName);
        }
    }

    private record ParsedPackedTile(PackedTile tile, String sheetName, int tileId, int order) {
    }

    private record PackedTile(
        String tileName,
        String atlasName,
        int x,
        int y,
        int width,
        int height,
        int offsetX,
        int offsetY,
        int tileWidth,
        int tileHeight,
        String rawPackDirectoryName,
        String packDirectoryName) {
    }
}
