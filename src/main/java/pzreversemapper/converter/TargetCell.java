package pzreversemapper.converter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cell of the output grid with its reprojected tiles and rooms. */
final class TargetCell {
    private final CellCoord coord;
    private final Map<LayerKey, TileLayer> tilesByLayer = new LinkedHashMap<>();
    private final List<RoomRect> rooms = new ArrayList<>();
    private final Map<LayerKey, BitSet> lotTiles = new HashMap<>();
    private final List<Lot> lots = new ArrayList<>();

    /**
     * A building lot placed in this cell: a shared TBX drawn at a cell-local square and level.
     * Width and height include the building's east/south wall row and column, like WorldEd lots.
     */
    record Lot(String tbxPath, int x, int y, int level, int width, int height) {
    }

    TargetCell(CellCoord coord) {
        this.coord = coord;
    }

    CellCoord coord() {
        return coord;
    }

    Map<LayerKey, TileLayer> tilesByLayer() {
        return tilesByLayer;
    }

    List<RoomRect> rooms() {
        return rooms;
    }

    void addTile(int localX, int localY, int floor, int layer, String tileName, int targetCellSize) {
        LayerKey key = new LayerKey(floor, layer);
        TileLayer tiles = tilesByLayer.get(key);
        if (tiles == null) {
            tiles = new TileLayer(targetCellSize * targetCellSize);
            tilesByLayer.put(key, tiles);
        }

        tiles.put(localX + localY * targetCellSize, tileName);
    }

    List<Lot> lots() {
        return lots;
    }

    void addLot(Lot lot) {
        lots.add(lot);
    }

    /** Marks a compiled tile as drawn by a lot, so the TMX leaves it out. */
    void coverByLot(LayerKey key, int index) {
        lotTiles.computeIfAbsent(key, ignored -> new BitSet()).set(index);
    }

    boolean coveredByLot(LayerKey key, int index) {
        BitSet covered = lotTiles.get(key);
        return covered != null && covered.get(index);
    }

    boolean hasLotTiles(LayerKey key) {
        BitSet covered = lotTiles.get(key);
        return covered != null && !covered.isEmpty();
    }

    /** Layers sorted by floor, then layer. */
    List<Map.Entry<LayerKey, TileLayer>> orderedLayers() {
        List<Map.Entry<LayerKey, TileLayer>> layers = new ArrayList<>(tilesByLayer.entrySet());
        layers.sort(Map.Entry.comparingByKey(LayerKey.ORDER));
        return layers;
    }
}
