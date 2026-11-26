package pzreversemapper.converter;

import java.util.List;

/** Decoded contents of one {@code X_Y.lotheader} file. */
public record LotHeaderData(
    int cellX,
    int cellY,
    int version,
    int chunkDim,
    int chunksPerCell,
    int cellDim,
    int minLevel,
    int maxLevel,
    List<String> tilesUsed,
    List<RoomRect> rooms,
    List<BuildingDef> buildings,
    byte[] zombieDensity) {

    public int minSquareX() {
        return cellX * cellDim;
    }

    public int minSquareY() {
        return cellY * cellDim;
    }

    public int maxSquareX() {
        return (cellX + 1) * cellDim - 1;
    }

    public int maxSquareY() {
        return (cellY + 1) * cellDim - 1;
    }
}
