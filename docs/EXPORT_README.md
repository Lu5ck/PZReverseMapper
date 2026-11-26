# PZ_Vanilla_Map_B42

![Kentucky in WorldEd](kentucky.jpg)

Project Zomboid Vanilla Map exported back to a WorldEd/TileZed editable project.

This export is based on Project Zomboid Build 42 compiled map files and was generated with private reverse-engineering tools.

## Export Content

- Project Zomboid Build 42 map data
- Compiled `.lotheader` and `.lotpack` files decoded
- Build 42 source cells kept on their native `256x256` grid (a `native-256` WorldEd project)
- Variable Z levels preserved where present in the compiled data
- Tiles exported at their decoded floor, layer, and world position
- Buildings exported as BuildingEd TBX files and placed back on the map as WorldEd lots
- Identical buildings share one TBX file
- `objects.lua` parsed and restored into the `.pzw` project
- Objects are visible and editable in WorldEd

## Generated Files

- `Kentucky_full.pzw`  
  Full WorldEd project with TMX cells, building lots, RoomDefs, and objects.

- `tmx/`  
  Reconstructed `256x256` TMX cells. Each cell places its buildings as lots.

- `tbx_buildings/`  
  One BuildingEd TBX file per unique building, referenced by the lots.

- `tbx_buildings/buildings.csv`  
  Every compiled building with its TBX file, world position and TMX cell.

## About The TBX Export

Every compiled building is rebuilt as a BuildingEd building, grouping the rooms that belong to the same compiled building. Buildings that come out identical share one TBX file, and each building is placed back on its TMX cell as a WorldEd lot. The tiles and rooms a lot draws are left out of the TMX, so WorldEd does not stack them twice; ground and vegetation stay in the TMX.

These files are not the original source TBX files from The Indie Stone. They are reconstructions from compiled lot data, which makes it possible to edit, sort, reuse, or package vanilla buildings separately.

Known limitations:

- Original TBX metadata and tile categories are not fully recoverable from the compiled map.
- Buildings that do not fit in one TMX cell, or that a TBX cannot reproduce exactly, keep their tiles and RoomDefs in the TMX.
- Room names, room masks, floors, walls, furniture, and decoded tile layers are preserved as far as the compiled data allows.

## Requirements

This export is intended to be used with the official Project Zomboid mapping tools:

- WorldEd
- TileZed

To properly open, edit, or compile the project, you also need the game tilesheets and tile definitions used by Project Zomboid Build 42.

Some tilesheets are packed inside the game texture packs and must be extracted before the project can render correctly in WorldEd/TileZed.

## Notes

Layer names are generated from the decoded layer order. They can be renamed in WorldEd if needed.

The map is exported from compiled game data. It is not provided by The Indie Stone and is the result of reverse engineering `.lotheader`, `.lotpack`, tiles, and map object data from Project Zomboid.

This project is intended for research, preservation, compatibility work, and modding workflows around the official mapping tools.

## Credits

Project Zomboid is developed by The Indie Stone.

This export is an unofficial community reverse-engineering project and is not affiliated with or endorsed by The Indie Stone.

