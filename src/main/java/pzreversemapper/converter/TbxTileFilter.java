package pzreversemapper.converter;

/** Excludes ground filler and vegetation tiles from TBX exports. */
final class TbxTileFilter {
    private TbxTileFilter() {
    }

    static boolean shouldInclude(String tileName) {
        return !tileName.equalsIgnoreCase("blends_natural_01")
            && !Text.startsWithIgnoreCase(tileName, "blends_natural_01_")
            && !Text.startsWithIgnoreCase(tileName, "vegetation_trees_01")
            && !Text.startsWithIgnoreCase(tileName, "vegetation_foliage_01")
            && !Text.startsWithIgnoreCase(tileName, "vegetation_groundcover_01")
            && !Text.startsWithIgnoreCase(tileName, "d_plants_01")
            && !Text.startsWithIgnoreCase(tileName, "blends_grassoverlays_01");
    }
}
