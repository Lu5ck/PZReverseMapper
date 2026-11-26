package pzreversemapper.converter;

/** A (floor, layer) pair identifying one tile layer inside a cell. */
record LayerKey(int floor, int layer) {
    static final java.util.Comparator<LayerKey> ORDER =
        java.util.Comparator.comparingInt(LayerKey::floor).thenComparingInt(LayerKey::layer);
}
