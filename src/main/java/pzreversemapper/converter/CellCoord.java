package pzreversemapper.converter;

/** A cell coordinate on either the source or the target grid. */
public record CellCoord(int x, int y) {
    @Override
    public String toString() {
        return x + "_" + y;
    }
}
