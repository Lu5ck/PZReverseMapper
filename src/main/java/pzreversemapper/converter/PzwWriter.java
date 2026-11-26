package pzreversemapper.converter;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Writes the WorldEd {@code .pzw} project with cells and objects. */
final class PzwWriter {
    private static final String[] KNOWN_OBJECT_TYPES = {
        "TownZone",
        "Forest",
        "DeepForest",
        "Nav",
        "Vegitation",
        "TrailerPark",
        "Farm",
        "ParkingStall",
        "FarmLand",
        "WaterFlow",
        "WaterZone",
        "Mannequin",
        "Ranch",
        "ZombiesType",
        "LootZone",
        "ZoneStory",
        "SpawnPoint",
        "RoomTone",
        "Basement",
        "WorldGen",
        "AnimalZone"
    };

    private static final Map<String, String> OBJECT_GROUP_COLORS = new HashMap<>();
    private static final Set<String> BUILT_IN_PROPERTY_DEFS = Set.of(
        "Direction",
        "FaceDirection",
        "WaterDirection",
        "WaterSpeed",
        "WaterGround",
        "WaterShore",
        "Female",
        "Pose",
        "Skin",
        "Professions",
        "Outfit",
        "Script",
        "RoomTone",
        "EntireBuilding",
        "StairDirection",
        "StairX",
        "StairY",
        "Access",
        "Rocks",
        "Action");

    static {
        OBJECT_GROUP_COLORS.put("ParkingStall", "#ff007f");
        OBJECT_GROUP_COLORS.put("TownZone", "#aa0000");
        OBJECT_GROUP_COLORS.put("Forest", "#00aa00");
        OBJECT_GROUP_COLORS.put("Nav", "#55aaff");
        OBJECT_GROUP_COLORS.put("DeepForest", "#003500");
        OBJECT_GROUP_COLORS.put("Vegitation", "#b3b300");
        OBJECT_GROUP_COLORS.put("TrailerPark", "#f50000");
        OBJECT_GROUP_COLORS.put("Farm", "#55ff7f");
        OBJECT_GROUP_COLORS.put("FarmLand", "#bcff7d");
        OBJECT_GROUP_COLORS.put("WaterFlow", "#0000ff");
        OBJECT_GROUP_COLORS.put("WaterZone", "#0000ff");
        OBJECT_GROUP_COLORS.put("Mannequin", "#0000ff");
        OBJECT_GROUP_COLORS.put("Ranch", "#ff8000");
        OBJECT_GROUP_COLORS.put("ZombiesType", "#ffffff");
        OBJECT_GROUP_COLORS.put("LootZone", "#80ff80");
        OBJECT_GROUP_COLORS.put("ZoneStory", "#8080ff");
        OBJECT_GROUP_COLORS.put("SpawnPoint", "#101010");
        OBJECT_GROUP_COLORS.put("RoomTone", "#0000ff");
        OBJECT_GROUP_COLORS.put("Basement", "#8040ff");
        OBJECT_GROUP_COLORS.put("WorldGen", "#808080");
        OBJECT_GROUP_COLORS.put("AnimalZone", "#ffaa00");
    }

    private PzwWriter() {
    }

    static void write(String file, String projectName, Collection<CellCoord> cells, Collection<MapObject> objects, int targetCellSize)
        throws IOException {
        List<CellCoord> cellList = new ArrayList<>(cells);
        if (cellList.isEmpty()) {
            return;
        }

        cellList.sort(Comparator.comparingInt(CellCoord::x).thenComparingInt(CellCoord::y));

        List<MapObject> objectList = new ArrayList<>();
        for (MapObject mapObject : objects) {
            if (mapObject.canWrite() && !Text.isNullOrWhiteSpace(mapObject.type())) {
                objectList.add(mapObject);
            }
        }

        Map<CellCoord, List<MapObject>> objectsByCell = new LinkedHashMap<>();
        for (MapObject mapObject : objectList) {
            objectsByCell.computeIfAbsent(mapObject.getCell(targetCellSize), cell -> new ArrayList<>()).add(mapObject);
        }

        Comparator<MapObject> objectOrder = Comparator
            .comparingInt(MapObject::anchorX)
            .thenComparingInt(MapObject::anchorY)
            .thenComparing(MapObject::type);
        for (List<MapObject> cellObjects : objectsByCell.values()) {
            cellObjects.sort(objectOrder);
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (CellCoord cell : cellList) {
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        try (TextFileWriter writer = new TextFileWriter(Paths.get(file))) {
            writer.writeLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.writeLine("<world version=\"1.0\" width=\"" + width + "\" height=\"" + height + "\">");
            writer.writeLine("<BMPToTMX>");
            writer.writeLine("<tmxexportdir path=\"tmx\"/>");
            writer.writeLine("<rulesfile path=\"\"/>");
            writer.writeLine("<blendsfile path=\"\"/>");
            writer.writeLine("<mapbasefile path=\"\"/>");
            writer.writeLine("<assign-maps-to-world checked=\"false\"/>");
            writer.writeLine("<warn-unknown-colors checked=\"true\"/>");
            writer.writeLine("<compress checked=\"true\"/>");
            writer.writeLine("<copy-pixels checked=\"true\"/>");
            writer.writeLine("<update-existing checked=\"true\"/>");
            writer.writeLine("</BMPToTMX>");
            writer.writeLine("<TMXToBMP>");
            writer.writeLine("<mainImage generate=\"true\"/>");
            writer.writeLine("<vegetationImage generate=\"true\"/>");
            writer.writeLine("<buildingsImage path=\"\" generate=\"false\"/>");
            writer.writeLine("</TMXToBMP>");
            writer.writeLine("<GenerateLots>");
            writer.writeLine("<exportdir path=\"lots\"/>");
            writer.writeLine("<ZombieSpawnMap path=\"Map_ZombieSpawnMap.png\"/>");
            writer.writeLine("<TileDefFolder path=\"\"/>");
            writer.writeLine("<worldOrigin origin=\"" + minX + "," + minY + "\"/>");
            writer.writeLine("</GenerateLots>");
            writer.writeLine("<LuaSettings>");
            writer.writeLine("<spawnPointsFile path=\"spawnpoints.lua\"/>");
            writer.writeLine("<worldObjectsFile path=\"objects.lua\"/>");
            writer.writeLine("</LuaSettings>");
            writer.writeLine("<otherworld path=\"\"/>");
            writeCommonTypes(writer, objectList);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    CellCoord coord = new CellCoord(x, y);
                    writer.writeLine("<cell x=\"" + (x - minX) + "\" y=\"" + (y - minY) + "\" map=\"tmx/" + x + "_" + y + ".tmx\">");

                    List<MapObject> cellObjects = objectsByCell.get(coord);
                    if (cellObjects != null) {
                        for (MapObject mapObject : cellObjects) {
                            writeObject(writer, mapObject, coord, targetCellSize);
                        }
                    }

                    writer.writeLine("</cell>");
                }
            }

            writer.writeLine("</world>");
        }
    }

    private static void writeCommonTypes(TextFileWriter writer, List<MapObject> objects) throws IOException {
        writer.writeLine("<propertyenum name=\"Direction\" choices=\"N,S,W,E\" multi=\"false\"/>");
        writer.writeLine("<propertyenum name=\"Pose\" choices=\"pose01,pose02,pose03\" multi=\"false\"/>");
        writer.writeLine("<propertyenum name=\"Skin\" choices=\"White,Black\" multi=\"false\"/>");
        writer.writeLine("<propertyenum name=\"Professions\" choices=\"unemployed,chef,constructionworker,doctor,fireofficer,parkranger,policeofficer,repairman,securityguard\" multi=\"false\"/>");
        writer.writeLine("<propertyenum name=\"RoomTone\" choices=\"Generic,Barn,Mall,Warehouse,Prison,Church,Office,Factory\" multi=\"false\"/>");
        writer.writeLine("<propertydef name=\"Direction\" default=\"N\" enum=\"Direction\"/>");
        writer.writeLine("<propertydef name=\"FaceDirection\" default=\"true\"/>");
        writer.writeLine("<propertydef name=\"WaterDirection\" default=\"0.0\"/>");
        writer.writeLine("<propertydef name=\"WaterSpeed\" default=\"0.0\"/>");
        writer.writeLine("<propertydef name=\"WaterGround\" default=\"false\"/>");
        writer.writeLine("<propertydef name=\"WaterShore\" default=\"true\"/>");
        writer.writeLine("<propertydef name=\"Female\" default=\"true\"/>");
        writer.writeLine("<propertydef name=\"Pose\" default=\"pose01\" enum=\"Pose\"/>");
        writer.writeLine("<propertydef name=\"Skin\" default=\"White\" enum=\"Skin\"/>");
        writer.writeLine("<propertydef name=\"Professions\" default=\"all\" enum=\"Professions\"/>");
        writer.writeLine("<propertydef name=\"Outfit\" default=\"\"/>");
        writer.writeLine("<propertydef name=\"Script\" default=\"\"/>");
        writer.writeLine("<propertydef name=\"RoomTone\" default=\"Generic\" enum=\"RoomTone\"/>");
        writer.writeLine("<propertydef name=\"EntireBuilding\" default=\"false\"/>");
        writer.writeLine("<propertydef name=\"StairDirection\" default=\"N\" enum=\"Direction\"/>");
        writer.writeLine("<propertydef name=\"StairX\" default=\"0\"/>");
        writer.writeLine("<propertydef name=\"StairY\" default=\"0\"/>");
        writer.writeLine("<propertydef name=\"Access\" default=\"\"/>");
        writer.writeLine("<propertydef name=\"Rocks\" default=\"\"/>");
        writer.writeLine("<propertydef name=\"Action\" default=\"\"/>");

        TreeSet<String> customProperties = new TreeSet<>();
        for (MapObject mapObject : objects) {
            for (String propertyName : mapObject.properties().keySet()) {
                if (!BUILT_IN_PROPERTY_DEFS.contains(propertyName)) {
                    customProperties.add(propertyName);
                }
            }
        }

        for (String propertyName : customProperties) {
            writer.writeLine("<propertydef name=\"" + Text.xmlEscape(propertyName) + "\" default=\"\"/>");
        }

        writeTemplates(writer);

        Set<String> types = new LinkedHashSet<>();
        for (String type : KNOWN_OBJECT_TYPES) {
            types.add(type);
        }

        TreeSet<String> objectTypes = new TreeSet<>();
        for (MapObject mapObject : objects) {
            if (!Text.isNullOrWhiteSpace(mapObject.type())) {
                objectTypes.add(mapObject.type());
            }
        }

        types.addAll(objectTypes);

        for (String type : types) {
            writer.writeLine("<objecttype name=\"" + Text.xmlEscape(type) + "\"/>");
        }

        for (String type : types) {
            String color = OBJECT_GROUP_COLORS.getOrDefault(type, "#808080");
            writer.writeLine("<objectgroup name=\"" + Text.xmlEscape(type) + "\" color=\"" + color + "\" defaulttype=\""
                + Text.xmlEscape(type) + "\"/>");
        }
    }

    private static void writeTemplates(TextFileWriter writer) throws IOException {
        writer.writeLine("<template name=\"ParkingStallN\">");
        writer.writeLine("<property name=\"Direction\" value=\"N\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"ParkingStallS\">");
        writer.writeLine("<property name=\"Direction\" value=\"S\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"ParkingStallW\">");
        writer.writeLine("<property name=\"Direction\" value=\"W\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"ParkingStallE\">");
        writer.writeLine("<property name=\"Direction\" value=\"E\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"WaterFlowN\">");
        writer.writeLine("<property name=\"WaterDirection\" value=\"0\"/>");
        writer.writeLine("<property name=\"WaterSpeed\" value=\"1.0\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"WaterFlowS\">");
        writer.writeLine("<property name=\"WaterDirection\" value=\"180\"/>");
        writer.writeLine("<property name=\"WaterSpeed\" value=\"1.0\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"WaterFlowE\">");
        writer.writeLine("<property name=\"WaterDirection\" value=\"90\"/>");
        writer.writeLine("<property name=\"WaterSpeed\" value=\"1.0\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"WaterFlowW\">");
        writer.writeLine("<property name=\"WaterDirection\" value=\"270\"/>");
        writer.writeLine("<property name=\"WaterSpeed\" value=\"1.0\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"WaterZone\">");
        writer.writeLine("<property name=\"WaterGround\" value=\"false\"/>");
        writer.writeLine("<property name=\"WaterShore\" value=\"true\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"SpawnPoint\">");
        writer.writeLine("<description></description>");
        writer.writeLine("<property name=\"Professions\" value=\"all\"/>");
        writer.writeLine("</template>");
        writer.writeLine("<template name=\"RoomTone\">");
        writer.writeLine("<property name=\"RoomTone\" value=\"Generic\"/>");
        writer.writeLine("<property name=\"EntireBuilding\" value=\"false\"/>");
        writer.writeLine("</template>");
    }

    private static void writeObject(TextFileWriter writer, MapObject mapObject, CellCoord cell, int targetCellSize) throws IOException {
        if (mapObject.isGeometry()) {
            writeGeometryObject(writer, mapObject, cell, targetCellSize);
        } else {
            writeRectangleObject(writer, mapObject, cell, targetCellSize);
        }
    }

    private static void writeRectangleObject(TextFileWriter writer, MapObject mapObject, CellCoord cell, int targetCellSize)
        throws IOException {
        if (mapObject.x() == null || mapObject.y() == null) {
            return;
        }

        int localX = mapObject.x() - cell.x() * targetCellSize;
        int localY = mapObject.y() - cell.y() * targetCellSize;
        List<String> attributes = new ArrayList<>();
        attributes.add(attribute("name", mapObject.name()));
        attributes.add(attribute("group", mapObject.type()));
        attributes.add(attribute("type", mapObject.type()));
        attributes.add(attribute("x", Integer.toString(localX)));
        attributes.add(attribute("y", Integer.toString(localY)));
        attributes.add(attribute("level", Integer.toString(mapObject.level())));
        attributes.add(attribute("width", Integer.toString(mapObject.width() == null ? 0 : mapObject.width())));
        attributes.add(attribute("height", Integer.toString(mapObject.height() == null ? 0 : mapObject.height())));

        writeObjectElement(writer, attributes, mapObject.properties());
    }

    private static void writeGeometryObject(TextFileWriter writer, MapObject mapObject, CellCoord cell, int targetCellSize)
        throws IOException {
        int cellMinX = cell.x() * targetCellSize;
        int cellMinY = cell.y() * targetCellSize;
        String geometry = Text.isNullOrWhiteSpace(mapObject.geometry()) ? "polygon" : Text.trim(mapObject.geometry());
        StringBuilder points = new StringBuilder();
        for (ObjectPoint point : mapObject.points()) {
            if (points.length() > 0) {
                points.append(' ');
            }

            points.append(point.x() - cellMinX).append(',').append(point.y() - cellMinY);
        }

        List<String> attributes = new ArrayList<>();
        attributes.add(attribute("name", mapObject.name()));
        attributes.add(attribute("group", mapObject.type()));
        attributes.add(attribute("type", mapObject.type()));
        attributes.add(attribute("level", Integer.toString(mapObject.level())));
        attributes.add(attribute("geometry", geometry));
        attributes.add(attribute("points", points.toString()));

        if (geometry.equalsIgnoreCase("polyline") && mapObject.lineWidth() != null) {
            attributes.add(attribute("linewidth", Integer.toString(mapObject.lineWidth())));
        }

        writeObjectElement(writer, attributes, mapObject.properties());
    }

    private static void writeObjectElement(TextFileWriter writer, List<String> attributes, Map<String, String> properties)
        throws IOException {
        String attributeText = String.join(" ", attributes);
        if (properties.isEmpty()) {
            writer.writeLine("  <object " + attributeText + "/>");
            return;
        }

        writer.writeLine("  <object " + attributeText + ">");
        for (Map.Entry<String, String> property : properties.entrySet()) {
            writer.writeLine("   <property name=\"" + Text.xmlEscape(property.getKey()) + "\" value=\""
                + Text.xmlEscape(property.getValue()) + "\"/>");
        }

        writer.writeLine("  </object>");
    }

    private static String attribute(String name, String value) {
        return name + "=\"" + Text.xmlEscape(value) + "\"";
    }
}
