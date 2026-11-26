package pzreversemapper.converter;

import java.util.Arrays;

/**
 * Memory-compact map from a cell-local square index to a tile name, for one (floor, layer) of a
 * target cell. Iteration follows insertion order, like the {@code Dictionary<int, string>} used by
 * the original converter.
 *
 * <p>Sparse layers use an open-addressing hash table; once a layer is dense enough the table is
 * replaced by a flat array covering the whole cell.
 */
final class TileLayer {
    private static final int EMPTY = -1;
    private static final int INITIAL_CAPACITY = 16;

    private final int slotCount;
    private int[] keys;
    private String[] values;
    private String[] dense;
    private int[] order;
    private int size;

    TileLayer(int slotCount) {
        this.slotCount = slotCount;
        keys = new int[INITIAL_CAPACITY];
        Arrays.fill(keys, EMPTY);
        values = new String[INITIAL_CAPACITY];
        order = new int[INITIAL_CAPACITY];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void put(int index, String value) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative tile index: " + index);
        }

        if (dense != null) {
            if (dense[index] == null) {
                appendOrder(index);
            }

            dense[index] = value;
            return;
        }

        int mask = keys.length - 1;
        int slot = mix(index) & mask;
        while (keys[slot] != EMPTY) {
            if (keys[slot] == index) {
                values[slot] = value;
                return;
            }

            slot = (slot + 1) & mask;
        }

        keys[slot] = index;
        values[slot] = value;
        appendOrder(index);
        if (size * 5 > keys.length * 3) {
            grow();
        }
    }

    /** Returns the tile at the given index, or {@code null} when the square is empty. */
    String get(int index) {
        if (dense != null) {
            return index >= 0 && index < dense.length ? dense[index] : null;
        }

        int mask = keys.length - 1;
        int slot = mix(index) & mask;
        while (true) {
            int key = keys[slot];
            if (key == index) {
                return values[slot];
            }

            if (key == EMPTY) {
                return null;
            }

            slot = (slot + 1) & mask;
        }
    }

    /** Visits every tile in insertion order. */
    void forEach(TileVisitor visitor) {
        for (int i = 0; i < size; i++) {
            int index = order[i];
            visitor.visit(index, get(index));
        }
    }

    @FunctionalInterface
    interface TileVisitor {
        void visit(int index, String tileName);
    }

    private void appendOrder(int index) {
        if (size == order.length) {
            order = Arrays.copyOf(order, Math.min(Math.max(order.length * 2, INITIAL_CAPACITY), Math.max(slotCount, size + 1)));
        }

        order[size++] = index;
    }

    private void grow() {
        int newCapacity = keys.length * 2;
        if ((long) newCapacity * 2 >= slotCount) {
            String[] flat = new String[slotCount];
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != EMPTY) {
                    flat[keys[i]] = values[i];
                }
            }

            dense = flat;
            keys = null;
            values = null;
            return;
        }

        int[] oldKeys = keys;
        String[] oldValues = values;
        keys = new int[newCapacity];
        Arrays.fill(keys, EMPTY);
        values = new String[newCapacity];
        int mask = newCapacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            int key = oldKeys[i];
            if (key == EMPTY) {
                continue;
            }

            int slot = mix(key) & mask;
            while (keys[slot] != EMPTY) {
                slot = (slot + 1) & mask;
            }

            keys[slot] = key;
            values[slot] = oldValues[i];
        }
    }

    private static int mix(int value) {
        int h = value * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
}
