# PZ Reverse Mapper

<p align="center">
  <img src="assets/pz-reverse-mapper-icon.png" width="160" alt="PZ Reverse Mapper icon">
</p>

PZ Reverse Mapper rebuilds editable mapping assets from compiled Project
Zomboid map data. It provides a graphical Studio and a command-line converter,
both shipped in a single cross-platform Java jar (Windows, macOS and Linux).

The project supports legacy 300 × 300 compiled cells and Build 42 256 × 256
compiled cells. Build 42 maps stay on their native 256 × 256 grid and are
written as native-256 WorldEd projects; legacy maps keep the classic 300 × 300
grid and can optionally be reprojected to 256 × 256 through world coordinates.

> PZ Reverse Mapper is an unofficial community tool. Project Zomboid,
> WorldEd and TileZed are created by The Indie Stone. No game assets are
> included in this repository or in application releases.

## Main features

- Rebuild TMX map cells and a WorldEd PZW project from `.lotheader` and
  `.lotpack` files.
- Keep Build 42 maps on their native 256 × 256 grid as native-256 WorldEd
  projects. (Build 42 maps are never converted to 300 × 300.)
- Optionally reproject legacy v41 300 × 300 maps to native 256 × 256 cells.
- Preserve tile layers, rooms, buildings and level information.
- Restore `objects.lua` objects in the generated PZW project.
- Export every compiled building as a BuildingEd TBX file. Identical buildings
  share one file, and each building is placed back on its TMX cell as a WorldEd
  lot, so the map holds no duplicate building tiles or RoomDefs.
- With the BuildingEd config files from
  [PZ_Mapping_Tools](https://github.com/Unjammer/PZ_Mapping_Tools), rebuild
  building TBX files as real BuildingEd buildings: rooms, exterior and interior
  walls, trims, floors, ceilings, grime, doors, windows, curtains, shutters,
  stairs and furniture.
- Generate per-cell and merged map, vegetation, biome and zombie-density
  previews when the corresponding source data exists.
- Extract Project Zomboid texture packs without loading every atlas into RAM
  at once.
- Restore each packed tile to its real canvas using the atlas rectangle,
  offsets, original tile name and stored tile dimensions.
- Rebuild tilesets with the Project Zomboid eight-column layout.
- Run tile extraction by itself without reading map headers or lotpacks.
- Read optional mod assets from a separate read-only folder.

## Tile pack extraction

The extractor separates physical atlas files from logical tileset merging:

```text
TilesRaw/
  Tiles2x.floor.pack/
    Tiles2x0.png
    ...
  Tiles2x.pack/
    Tiles2x0.png
    ...

TilesSingle/
  Tiles2x.pack/
    blends_natural_01/
      blends_natural_01_0.png
      ...

Tiles/
  Tiles2x.pack/
    blends_natural_01.png
    ...
```

`Tiles2x.floor.pack` and `Tiles2x.pack` can contain atlas images with identical
internal names. Their raw atlas directories therefore remain separate. Only
the individual tiles are merged under the matching logical pack after they
have been cut from the correct physical atlas.

Every reconstructed tileset is eight tiles wide. A tileset containing
128-pixel-wide tiles is therefore exactly `8 × 128 = 1024` pixels wide,
regardless of whether the source is 1x, 2x or another scale.

## Requirements

- Java 17 or newer (any distribution, for example Eclipse Temurin)
- A legally installed copy of Project Zomboid for the game data being read

For building from source:

- JDK 17 or newer
- Apache Maven 3.9 or newer

## Download

Releases are published on the repository's **Releases** page as
`PZReverseMapper-<version>.jar`, with a `.sha256` checksum next to it.

## Using Studio

Double-click the jar, or start it from a terminal:

```bash
java -jar PZReverseMapper-1.0.0.jar
```

1. Select a workflow preset.
2. Select the compiled map folder.
3. Select the Project Zomboid `media` folder for tiles and texture packs.
4. Select a dedicated output folder.
5. Use `Validate` before a map export.
6. Run a small source-cell test before exporting a complete map.

The default output grid is `Auto`: Build 42 maps export to native 256 × 256
cells and v41 maps to 300 × 300 cells. Build 42 exports mark the WorldEd project
as `cellFormat="native-256"`, so
[PZ_Mapping_Tools](https://github.com/Unjammer/PZ_Mapping_Tools) opens it as a
native 256 × 256 project. Converting Build 42 maps to 300 × 300 is not
supported.

To get editable BuildingEd buildings, set `BuildingEd config` to the folder
holding `BuildingTiles.txt` (see [Rebuilding BuildingEd buildings](#rebuilding-buildinged-buildings)).

When `Tile sheets` is the only selected output, Studio automatically enters
tiles-only mode. The compiled map input is not required and no `.lotheader` or
`.lotpack` file is parsed.

The "Reproducible command" box always shows the equivalent command line, and
the About window explains the project, current version, tile extraction model
and unofficial-project status.

## Command-line examples

Any argument switches the jar to the command-line converter. A Build 42 map,
with tile sheets and rebuilt BuildingEd buildings:

```bash
java -jar PZReverseMapper-1.0.0.jar \
  --input "D:\Maps\CompiledMap" \
  --output "D:\Exports\EditableMap" \
  --tiles "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\media" \
  --tbx-config "D:\Tools\PZ_Mapping_Tools" \
  --source-cell-size 256 \
  --extract-tiles
```

Tiles only:

```bash
java -jar PZReverseMapper-1.0.0.jar \
  --output "D:\Exports\Tiles" \
  --tiles "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\media" \
  --tiles-only
```

In PowerShell, use a backtick (`` ` ``) instead of `\` to continue lines. Use
`--help` for the complete CLI option list, and see [docs/CLI.md](docs/CLI.md)
for details. Exit codes are `0` on success, `1` for usage errors or a cancelled
clean, and `2` for failures.

### Memory

Complete maps hold every decoded tile in memory. If Java reports that it ran
out of memory, give it a larger heap, for example:

```bash
java -Xmx12g -jar PZReverseMapper-1.0.0.jar --input ... --output ...
```

## Rebuilding BuildingEd buildings

Compiled maps only keep the final tiles of each square, so a plain export can
only store a building as raw tiles. Given the BuildingEd configuration of
PZ_Mapping_Tools (`--tbx-config <folder>` or `BuildingEd config` in Studio: the
folder holding `BuildingTiles.txt`, `BuildingFurniture.txt` and
`TMXConfig.txt`; a PZ_Mapping_Tools checkout or install folder also works), each
compiled building is rebuilt as a proper BuildingEd building:

- Rooms come from the compiled RoomDefs, floor by floor.
- BuildingEd's own wall rules decide which walls each room and the building's
  exterior draw. For each of them, the tile entry is chosen by majority vote
  among the BuildingTiles entries matching the compiled tiles. The same vote
  picks each room's floor, ceiling, wall trim and grime, and the building's
  exterior wall, trim and grime.
- Door and window wall variants become door and window objects with their
  frames, curtains and shutters; stair runs become stairs; furniture is matched
  against BuildingFurniture, largest pieces first.
- The rebuilt building is rendered again with a Java port of BuildingEd's
  layout rules and compared with the compiled tiles. Anything not reproduced is
  kept as user-drawn tiles, so nothing is lost. The console reports how much
  was reproduced.

Pass `--tiles <media>` as well: BuildingEd's grime and window rules read tile
properties from the game's `.tiles` files. Roofs and wall-layer furniture are
kept as user-drawn tiles (the building's default roof style is still set).

Without `--tbx-config`, each building TBX holds the building's rooms and keeps
its tiles as user-drawn tiles.

### Shared buildings and lots

Maps often repeat the same building many times. Buildings whose rebuilt TBX
comes out identical (ignoring where they stand) share one file in
`tbx_buildings/`, named after the first room and the building size, for example
`hall_3x7.tbx`, then `hall_3x7_2.tbx` for a different building with the same
name and size. `tbx_buildings/buildings.csv` lists every compiled building with
its TBX file, world position, source cell and building id, and the TMX cell it
was placed in.

Each building is placed back on its TMX cell as a WorldEd lot
(`<object name="lot" type="../tbx_buildings/<file>.tbx"/>` in a `Lots` object
group on the building's level). The tiles the lot draws and the building's rooms
are left out of the TMX layers and RoomDefs, so WorldEd does not stack them
twice when it generates lots. Map ground and vegetation around and under the
building stay in the TMX.

A building is not placed as a lot, and keeps its tiles and RoomDefs in the TMX,
when it does not fit in one TMX cell (its east and south walls included), or
when its rebuilt TBX would draw tiles that are not in the compiled map (for
example a room floor across a square that has none). Placing lots therefore
never changes the map: the TMX tiles plus the tiles the lots draw are exactly
the compiled tiles. The console summary reports how many buildings were placed
and why any were not.

## Main outputs

- `tmx/*.tmx` — editable map cells, with building lots
- `<project>.pzw` — WorldEd project (marked `native-256` for 256 × 256 exports)
- `tbx_buildings/*.tbx` — one TBX per unique building: a rebuilt BuildingEd
  building with `--tbx-config`, otherwise rooms with user-drawn tiles
- `tbx_buildings/buildings.csv` — every compiled building, its TBX file and
  position
- `maps_img/*` — per-cell previews
- `Map.png`, `Map_veg.png`, `world.png` — merged map previews
- `Map_ZombieSpawnMap.png` — zombie-density preview when available
- `biomemap.png` — merged Build 42 biomemap when source images exist
- `TilesRaw/<physical-pack>/*` — physical atlas images
- `TilesSingle/<logical-pack>/<tileset>/*.png` — restored individual tiles
- `Tiles/<logical-pack>/*.png` — reconstructed eight-column tilesets

Text outputs (TMX, PZW, TBX, CSV) are written as UTF-8 with a byte order mark and
CRLF line endings on every platform, exactly like the original Windows tool,
so exports are interchangeable between machines.

## Repository layout

```text
pom.xml                               Maven build (no runtime dependencies)
src/main/java/pzreversemapper/
  Main.java                           Jar entry point: Studio or CLI
  converter/                          Conversion core and command-line converter
  studio/                             Swing graphical application
src/main/resources/                   Studio artwork and version file
src/test/java/                        Unit and end-to-end tests with synthetic map data
docs/                                 CLI reference and export README template
assets/                               Repository artwork
.github/workflows/                    CI and release workflows
```

The historical PZ_Mapper application is intentionally not part of this
standalone repository.

## Build

```bash
mvn -B verify
```

The runnable jar is written to:

```text
target/PZReverseMapper-<version>.jar
```

Pass `-Drevision=<version>` to build a specific version number.

## Releases

The `Release` workflow (`.github/workflows/release.yml`) builds, tests and
publishes the jar plus its SHA-256 checksum as a GitHub release:

- push a version tag, for example `git tag v1.2.0 && git push origin v1.2.0`, or
- run the workflow from the Actions tab and enter a version such as `1.2.0`;
  the tag `v1.2.0` is created on the selected branch.

Versions with a suffix such as `1.2.0-beta.1` are published as pre-releases.
The `CI` workflow builds and tests every push and pull request on Windows,
macOS and Linux and keeps the jar as a downloadable build artifact.

## Safety and limitations

- Game and mod inputs are read only.
- Existing output is removed only when the guarded clean-output option is
  explicitly enabled and confirmed. Entries are moved to the Recycle Bin
  (Windows) or Trash (macOS, Linux) and never deleted permanently; if the
  system offers no trash, the clean is refused and nothing is removed.
- Reverse-engineered formats can change between Project Zomboid builds.
- Reconstructed building TBX files are reconstructions, not original
  authoring-source files. Properties that leave no trace in compiled data
  (for example a ceiling hidden under the floor above) cannot be recovered.
- Always validate a small area before processing a complete map.

## License and credits

PZ Reverse Mapper is source-available software, not open-source software.
Official releases may be used for personal, non-commercial purposes. The
source may be viewed, studied and modified privately, and proposed changes may
be submitted to the official project.

Redistribution, rehosting, resale, public modified versions, forks,
repackaging, commercial exploitation and integration into another product are
not permitted without prior written authorization.

See [LICENSE](LICENSE) for the complete terms and [CREDITS.md](CREDITS.md) for
project credits.
