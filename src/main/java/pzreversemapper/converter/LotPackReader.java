package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Streams the tiles of a {@code world_X_Y.lotpack} file. */
final class LotPackReader {
    private final LotHeaderData header;
    private final Path file;

    /** Receives one decoded tile: cell-local square, floor, layer index (1-based) and tile name. */
    @FunctionalInterface
    interface TileSink {
        void accept(int localX, int localY, int floor, int layer, String tileName);
    }

    LotPackReader(LotHeaderData header, String inputDirectory) {
        this.header = header;
        this.file = Paths.get(inputDirectory, "world_" + header.cellX() + "_" + header.cellY() + ".lotpack");
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    void read(TileSink onTile) throws IOException {
        if (!exists()) {
            return;
        }

        try (LittleEndianReader reader = new LittleEndianReader(file)) {
            boolean hasMagic = reader.tryReadMagic("LOTP");
            int version = 0;
            int tableStart = 4;

            if (hasMagic) {
                version = reader.readInt();
                if (version < 0 || version > 1) {
                    throw new InvalidDataException(file.getFileName() + " has unsupported lotpack version " + version);
                }

                if (version >= 1) {
                    int headerValue = reader.readInt();
                    int expectedChunkCount = header.chunksPerCell() * header.chunksPerCell();
                    if (headerValue != header.chunkDim() && headerValue != expectedChunkCount) {
                        System.out.println("Warning: " + file + " lotpack header value is " + headerValue
                            + ", expected chunk size " + header.chunkDim() + " or chunk count " + expectedChunkCount);
                    }

                    tableStart = 12;
                }
            } else {
                reader.position(0);
            }

            for (int chunkX = 0; chunkX < header.chunksPerCell(); chunkX++) {
                for (int chunkY = 0; chunkY < header.chunksPerCell(); chunkY++) {
                    readChunk(reader, version, tableStart, chunkX, chunkY, onTile);
                }
            }
        }
    }

    private void readChunk(LittleEndianReader reader, int version, int tableStart, int chunkX, int chunkY, TileSink onTile)
        throws IOException {
        int index = chunkX * header.chunksPerCell() + chunkY;
        reader.position(tableStart + index * 8L);
        long chunkOffset = version >= 1 ? reader.readLong() : reader.readInt();
        if (chunkOffset <= 0 || chunkOffset >= reader.length()) {
            return;
        }

        reader.position(chunkOffset);
        int skip = 0;
        int chunkDim = header.chunkDim();
        int tileCount = header.tilesUsed().size();

        for (int z = header.minLevel(); z <= header.maxLevel(); z++) {
            for (int x = 0; x < chunkDim; x++) {
                for (int y = 0; y < chunkDim; y++) {
                    int localX = chunkX * chunkDim + x;
                    int localY = chunkY * chunkDim + y;

                    if (skip > 0) {
                        skip--;
                        continue;
                    }

                    int count = reader.readInt();
                    if (count == -1) {
                        skip = reader.readInt();
                        if (skip > 0) {
                            skip--;
                            continue;
                        }
                    }

                    if (count <= 1) {
                        continue;
                    }

                    reader.readInt();
                    for (int layer = 1; layer < count; layer++) {
                        int tileIndex = reader.readInt();
                        if (tileIndex < 0 || tileIndex >= tileCount) {
                            System.out.println("Warning: tile index " + tileIndex + " is outside "
                                + header.cellX() + "_" + header.cellY() + ".lotheader");
                            continue;
                        }

                        onTile.accept(localX, localY, z, layer, header.tilesUsed().get(tileIndex));
                    }
                }
            }
        }
    }
}
