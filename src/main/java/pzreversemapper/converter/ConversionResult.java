package pzreversemapper.converter;

import java.time.Duration;

/** Summary of a finished export. */
public record ConversionResult(
    int sourceCellCount,
    int targetCellCount,
    int objectCount,
    int imageCount,
    int tileImageCount,
    int tbxCount,
    int buildingTbxCount,
    int tileSetCount,
    Duration elapsed,
    String outputDirectory,
    String projectFile) {
}
