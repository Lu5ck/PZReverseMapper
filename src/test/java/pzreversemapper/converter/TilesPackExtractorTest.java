package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilesPackExtractorTest {
    @TempDir
    Path fixture;

    @TempDir
    Path output;

    @Test
    void extractsRawAtlasesSingleTilesAndEightColumnSheets() throws Exception {
        SyntheticMap.write(fixture);
        ConverterOptions options = ConverterOptions.parse(new String[] {
            "--output", output.toString(),
            "--tiles", fixture.resolve("media").toString(),
            "--mod-tiles", fixture.resolve("modmedia").toString(),
            "--tiles-only"});
        ConversionResult result = new MapConverter(options).run();

        // Physical packs keep separate raw atlas folders even though the atlas names collide.
        assertTrue(Files.isRegularFile(output.resolve("TilesRaw/Tiles2x.pack/Tiles2x0.png")));
        assertTrue(Files.isRegularFile(output.resolve("TilesRaw/Tiles2x.floor.pack/Tiles2x0.png")));
        assertTrue(Files.isRegularFile(output.resolve("TilesRaw/UI.pack/UI0.png")), "old pack format");
        assertTrue(Files.isRegularFile(output.resolve("TilesRaw/ModTiles.pack/ModAtlas.png")), "TexturePacks folder found case-insensitively");

        // Floor tiles merge into the logical pack after cutting.
        Path single = output.resolve("TilesSingle/Tiles2x.pack/blends_natural_01/blends_natural_01_17.png");
        assertTrue(Files.isRegularFile(single));
        PngImage tile = PngImage.load(single);
        assertEquals(32, tile.width());
        assertEquals(64, tile.height());
        assertEquals(0, tile.pixels()[3], "outside the packed rectangle stays transparent");
        assertTrue((tile.pixels()[(3 + 7 * 32) * 4 + 3] & 0xff) >= 128, "offset (3,7) holds atlas pixels");

        PngImage sheet = PngImage.load(output.resolve("Tiles/Tiles2x.pack/blends_natural_01.png"));
        assertEquals(8 * 32, sheet.width());
        assertEquals(3 * 64, sheet.height(), "tile id 17 needs three rows");

        PngImage modSheet = PngImage.load(output.resolve("Tiles/ModTiles.pack/mod_sheet_01.png"));
        assertEquals(256, modSheet.width());
        assertEquals(128, modSheet.height());

        assertEquals(4 + 13 + 6, result.tileImageCount(), "4 raw atlases, 13 single tiles, 6 tilesheets");
        assertEquals(6, result.tileSetCount());
    }
}
