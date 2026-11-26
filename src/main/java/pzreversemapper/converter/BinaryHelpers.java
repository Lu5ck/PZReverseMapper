package pzreversemapper.converter;

/** Integer helpers shared by the readers and writers. */
public final class BinaryHelpers {
    private BinaryHelpers() {
    }

    public static int floorDiv(int value, int divisor) {
        return Math.floorDiv(value, divisor);
    }

    public static int positiveMod(int value, int divisor) {
        int result = value % divisor;
        return result < 0 ? result + Math.abs(divisor) : result;
    }
}
