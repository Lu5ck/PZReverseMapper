package pzreversemapper.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cell of the output grid with its reprojected tiles and rooms. */
final class TargetCell {
    private final CellCoord coord;
    private final Map<LayerKey, TileLayer> tilesByLayer = new LinkedHashMap<>();
    private final List<RoomRect> rooms = new ArrayList<>();

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

    /** Layers sorted by floor, then layer. */
    List<Map.Entry<LayerKey, TileLayer>> orderedLayers() {
        List<Map.Entry<LayerKey, TileLayer>> layers = new ArrayList<>(tilesByLayer.entrySet());
        layers.sort(Map.Entry.comparingByKey(LayerKey.ORDER));
        return layers;
    }
}
