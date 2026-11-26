package pzreversemapper.converter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pzreversemapper.converter.TbxModel.Building;
import pzreversemapper.converter.TbxModel.Floor;
import pzreversemapper.converter.TbxModel.FurnitureDef;
import pzreversemapper.converter.TbxModel.FurnitureEntry;
import pzreversemapper.converter.TbxModel.Room;
import pzreversemapper.converter.TbxModel.TileEntry;

/** Reads BuildingEd .tbx files into {@link TbxModel} for tests (roof and wall objects are skipped). */
final class TbxReader {
    private TbxReader() {
    }

    static Building read(Path file) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
        Element root = document.getDocumentElement();
        Building building = new Building(Integer.parseInt(root.getAttribute("width")), Integer.parseInt(root.getAttribute("height")));

        List<TileEntry> entries = new ArrayList<>();
        Map<String, TileEntry> unique = new HashMap<>();
        List<FurnitureDef> furniture = new ArrayList<>();
        List<String> userTiles = new ArrayList<>();
        for (Element child : children(root)) {
            switch (child.getTagName()) {
                case "tile_entry" -> {
                    TileEntry entry = new TileEntry(child.getAttribute("category"), entries.size());
                    for (Element tile : children(child)) {
                        String name = TbxModel.canonicalTile(tile.getAttribute("tile"));
                        entry.tiles.put(tile.getAttribute("enum"), name == null ? "" : name);
                        if (tile.hasAttribute("offset")) {
                            String[] parts = tile.getAttribute("offset").split(",");
                            entry.offsets.put(tile.getAttribute("enum"), new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
                        }
                    }

                    // BuildingEd merges identical entries, so identical ones share one object.
                    entries.add(unique.computeIfAbsent(entry.key(), ignored -> entry));
                }
                case "furniture" -> {
                    FurnitureDef def = new FurnitureDef(child.getAttribute("layer"), "true".equals(child.getAttribute("corners")), furniture.size(), "");
                    for (Element entryElement : children(child)) {
                        FurnitureEntry entry = new FurnitureEntry(entryElement.getAttribute("orient"));
                        entry.allowGrime = !"false".equals(entryElement.getAttribute("grime"));
                        for (Element tile : children(entryElement)) {
                            entry.tiles.put(new TbxModel.Point(Integer.parseInt(tile.getAttribute("x")), Integer.parseInt(tile.getAttribute("y"))),
                                TbxModel.canonicalTile(tile.getAttribute("name")));
                        }

                        def.entries.put(entry.orient, entry);
                    }

                    furniture.add(def);
                }
                case "user_tiles" -> {
                    for (Element tile : children(child)) {
                        userTiles.add(TbxModel.canonicalTile(tile.getAttribute("tile")));
                    }
                }
                case "properties" -> {
                    for (Element property : children(child)) {
                        building.properties.put(property.getAttribute("name"), property.getAttribute("value"));
                    }
                }
                default -> {
                }
            }
        }

        for (String slot : TbxModel.BUILDING_TILES) {
            building.tiles.put(slot, entry(entries, root.getAttribute(slot)));
        }

        for (Element child : children(root)) {
            if (child.getTagName().equals("room")) {
                String[] color = child.getAttribute("Color").trim().split("\\s+");
                Room room = new Room(child.getAttribute("Name"), child.getAttribute("InternalName"),
                    new int[] {Integer.parseInt(color[0]), Integer.parseInt(color[1]), Integer.parseInt(color[2])}, building.rooms.size());
                for (String slot : TbxModel.ROOM_TILES) {
                    room.tiles.put(slot, entry(entries, child.getAttribute(slot)));
                }

                building.rooms.add(room);
            }
        }

        int floorIndex = 0;
        for (Element child : children(root)) {
            if (!child.getTagName().equals("floor")) {
                continue;
            }

            Floor floor = new Floor(floorIndex, floorIndex, building.width, building.height);
            floorIndex++;
            for (Element element : children(child)) {
                switch (element.getTagName()) {
                    case "object" -> readObject(floor, element, entries, furniture);
                    case "rooms" -> {
                        int[] values = csv(element.getTextContent());
                        for (int i = 0; i < values.length; i++) {
                            floor.roomAt[i % building.width][i / building.width] = values[i] - 1;
                        }
                    }
                    case "tiles" -> {
                        int[] values = csv(element.getTextContent());
                        for (int i = 0; i < values.length; i++) {
                            if (values[i] > 0) {
                                floor.setUserTile(element.getAttribute("layer"), i % (building.width + 1), i / (building.width + 1),
                                    userTiles.get(values[i] - 1), building.width, building.height);
                            }
                        }
                    }
                    default -> {
                    }
                }
            }

            building.floors.add(floor);
        }

        return building;
    }

    private static void readObject(Floor floor, Element element, List<TileEntry> entries, List<FurnitureDef> furniture) {
        int x = Integer.parseInt(element.getAttribute("x"));
        int y = Integer.parseInt(element.getAttribute("y"));
        String dir = element.getAttribute("dir");
        switch (element.getAttribute("type")) {
            case "door" -> floor.objects.add(new TbxModel.Door(x, y, dir, entry(entries, element.getAttribute("Tile")),
                entry(entries, element.getAttribute("FrameTile"))));
            case "window" -> floor.objects.add(new TbxModel.Window(x, y, dir, entry(entries, element.getAttribute("Tile")),
                entry(entries, element.getAttribute("CurtainsTile")), entry(entries, element.getAttribute("ShuttersTile"))));
            case "stairs" -> floor.objects.add(new TbxModel.Stairs(x, y, dir, entry(entries, element.getAttribute("Tile"))));
            case "furniture" -> floor.objects.add(new TbxModel.Furniture(x, y,
                furniture.get(Integer.parseInt(element.getAttribute("FurnitureTiles"))), element.getAttribute("orient")));
            default -> {
            }
        }
    }

    private static TileEntry entry(List<TileEntry> entries, String index) {
        if (index == null || index.isEmpty()) {
            return null;
        }

        int value = Integer.parseInt(index);
        return value <= 0 || value > entries.size() ? null : entries.get(value - 1);
    }

    private static int[] csv(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new int[0];
        }

        String[] parts = trimmed.split("[,\\s]+");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }

        return values;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) nodes.item(i));
            }
        }

        return result;
    }
}
