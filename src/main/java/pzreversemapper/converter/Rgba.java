package pzreversemapper.converter;

/** An 8-bit RGBA colour. Components are stored as 0..255 ints. */
record Rgba(int r, int g, int b, int a) {
    static final Rgba TRANSPARENT = new Rgba(0, 0, 0, 0);
    static final Rgba BLACK = new Rgba(0, 0, 0);
    static final Rgba RED = new Rgba(255, 0, 0);
    static final Rgba MAGENTA = new Rgba(255, 0, 255);

    Rgba(int r, int g, int b) {
        this(r, g, b, 255);
    }

    Rgba {
        r &= 0xff;
        g &= 0xff;
        b &= 0xff;
        a &= 0xff;
    }

    static Rgba fromHex(String value) {
        String text = value.startsWith("#") ? value.substring(1) : value;
        return new Rgba(
            Integer.parseInt(text.substring(0, 2), 16),
            Integer.parseInt(text.substring(2, 4), 16),
            Integer.parseInt(text.substring(4, 6), 16));
    }
}
