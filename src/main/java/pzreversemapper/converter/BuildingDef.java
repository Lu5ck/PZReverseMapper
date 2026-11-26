package pzreversemapper.converter;

import java.util.List;

/** A compiled building: the room ids that belong to it inside one source cell. */
record BuildingDef(int cellX, int cellY, int sourceBuildingId, List<Integer> roomIds) {
}
