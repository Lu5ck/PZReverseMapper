package pzreversemapper.converter;

import java.util.List;
import java.util.Map;

/** An object parsed from {@code objects.lua}. Coordinates are world squares. */
record MapObject(
    String name,
    String type,
    Integer x,
    Integer y,
    Integer z,
    Integer width,
    Integer height,
    String geometry,
    Integer lineWidth,
    List<ObjectPoint> points,
    Map<String, String> properties) {

    boolean isGeometry() {
        return !Text.isNullOrWhiteSpace(geometry) && !points.isEmpty();
    }

    boolean canWrite() {
        return isGeometry() || (x != null && y != null);
    }

    int level() {
        return z == null ? 0 : z;
    }

    int anchorX() {
        return isGeometry() ? points.get(0).x() : (x == null ? 0 : x);
    }

    int anchorY() {
        return isGeometry() ? points.get(0).y() : (y == null ? 0 : y);
    }

    CellCoord getCell(int targetCellSize) {
        return new CellCoord(
            BinaryHelpers.floorDiv(anchorX(), targetCellSize),
            BinaryHelpers.floorDiv(anchorY(), targetCellSize));
    }
}
