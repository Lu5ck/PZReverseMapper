package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads {@code X_Y.lotheader} files (legacy v0 and Build 42 v1). */
public final class LotHeaderReader {
    private static final Pattern FILE_NAME = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.lotheader$", Pattern.CASE_INSENSITIVE);

    private LotHeaderReader() {
    }

    /** Reads every lotheader in the folder, keyed by source cell. */
    public static Map<CellCoord, LotHeaderData> readAll(String inputDirectory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(Paths.get(inputDirectory))) {
            entries
                .filter(Files::isRegularFile)
                .filter(file -> Text.endsWithIgnoreCase(file.getFileName().toString(), ".lotheader"))
                .forEach(files::add);
        }

        files.sort((a, b) -> Text.CULTURE_ORDER.compare(a.toString(), b.toString()));

        Map<CellCoord, LotHeaderData> headers = new LinkedHashMap<>();
        for (Path file : files) {
            Matcher match = FILE_NAME.matcher(file.getFileName().toString());
            if (!match.matches()) {
                continue;
            }

            int cellX = Integer.parseInt(match.group(1));
            int cellY = Integer.parseInt(match.group(2));
            headers.put(new CellCoord(cellX, cellY), read(file, cellX, cellY));
        }

        return headers;
    }

    public static LotHeaderData read(Path file, int cellX, int cellY) throws IOException {
        try (LittleEndianReader reader = new LittleEndianReader(file)) {
            reader.tryReadMagic("LOTH");

            int version = reader.readInt();
            if (version < 0 || version > 1) {
                throw new InvalidDataException(file.getFileName() + " has unsupported lotheader version " + version);
            }

            int tileCount = reader.readInt();
            List<String> tilesUsed = new ArrayList<>(Math.max(0, Math.min(tileCount, 1 << 16)));
            for (int i = 0; i < tileCount; i++) {
                tilesUsed.add(Text.trim(reader.readLineString()));
            }

            if (version == 0) {
                reader.readUnsignedByte();
            }

            int width = reader.readInt();
            reader.readInt();
            int minLevel = version == 0 ? 0 : reader.readInt();
            int maxLevel = reader.readInt();

            int chunkDim = width > 0 ? width : (version >= 1 ? 8 : 10);
            int chunksPerCell = chunkDim == 8 ? 32 : 30;
            int cellDim = chunkDim * chunksPerCell;

            List<RoomRect> rooms = readRooms(reader, cellX, cellY, cellDim);
            List<BuildingDef> buildings = readBuildings(reader, cellX, cellY);
            byte[] zombieDensity = readZombieDensity(reader, chunksPerCell);

            return new LotHeaderData(
                cellX,
                cellY,
                version,
                chunkDim,
                chunksPerCell,
                cellDim,
                minLevel,
                version == 0 ? maxLevel - 1 : maxLevel,
                List.copyOf(tilesUsed),
                List.copyOf(rooms),
                List.copyOf(buildings),
                zombieDensity);
        }
    }

    private static List<RoomRect> readRooms(LittleEndianReader reader, int cellX, int cellY, int cellDim) throws IOException {
        int roomCount = reader.readInt();
        List<RoomRect> rooms = new ArrayList<>();

        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            String name = reader.readLineString();
            if (Text.isNullOrWhiteSpace(name)) {
                name = "empty";
            }

            String safeName = Text.sanitizeFileName(name);
            int floor = reader.readInt();
            int rectCount = reader.readInt();
            for (int rectIndex = 0; rectIndex < rectCount; rectIndex++) {
                int x = reader.readInt();
                int y = reader.readInt();
                int width = reader.readInt();
                int height = reader.readInt();

                rooms.add(new RoomRect(
                    roomIndex,
                    safeName,
                    floor,
                    x + cellX * cellDim,
                    y + cellY * cellDim,
                    width,
                    height));
            }

            int objectCount = reader.readInt();
            for (int i = 0; i < objectCount; i++) {
                reader.readInt();
                reader.readInt();
                reader.readInt();
            }
        }

        return rooms;
    }

    private static List<BuildingDef> readBuildings(LittleEndianReader reader, int cellX, int cellY) throws IOException {
        int buildingCount = reader.readInt();
        List<BuildingDef> buildings = new ArrayList<>(Math.max(0, Math.min(buildingCount, 1 << 16)));
        for (int buildingIndex = 0; buildingIndex < buildingCount; buildingIndex++) {
            int roomCount = reader.readInt();
            List<Integer> roomIds = new ArrayList<>(Math.max(0, Math.min(roomCount, 1 << 16)));
            for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
                roomIds.add(reader.readInt());
            }

            buildings.add(new BuildingDef(cellX, cellY, buildingIndex, List.copyOf(roomIds)));
        }

        return buildings;
    }

    private static byte[] readZombieDensity(LittleEndianReader reader, int chunksPerCell) throws IOException {
        int length = chunksPerCell * chunksPerCell;
        long remaining = reader.length() - reader.position();
        return remaining >= length ? reader.readBytes(length) : new byte[0];
    }
}
