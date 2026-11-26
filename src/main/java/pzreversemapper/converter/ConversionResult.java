package pzreversemapper.converter;

import java.time.Duration;

/** Summary of a finished export. */
public record ConversionResult(
    int sourceCellCount,
    int targetCellCount,
    int objectCount,
    int imageCount,
    int tileImageCount,
    /** Distinct building TBX files under {@code tbx_buildings/}. */
    int tbxCount,
    /** Compiled buildings exported; identical buildings share a TBX file. */
    int buildingCount,
    /** Buildings placed on the TMX cells as WorldEd lots. */
    int lotCount,
    int tileSetCount,
    Duration elapsed,
    String outputDirectory,
    String projectFile) {
}
