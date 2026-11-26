package pzreversemapper.studio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import pzreversemapper.converter.AppInfo;
import pzreversemapper.converter.OutputCleaner;

/** The workflow guide window. */
final class GuideDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    GuideDialog(Window owner) {
        super(owner, AppInfo.APP_NAME + " - Workflow guide", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel layout = new JPanel(new BorderLayout(0, 10));
        layout.setBackground(Theme.BACKGROUND);
        layout.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JTextPane guide = new JTextPane();
        guide.setEditable(false);
        guide.setBackground(Color.WHITE);
        guide.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        populateGuide(guide.getStyledDocument());
        guide.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(guide);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        JButton closeButton = new JButton("Close");
        closeButton.setPreferredSize(new Dimension(112, 32));
        closeButton.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(closeButton);

        layout.add(scroll, BorderLayout.CENTER);
        layout.add(buttons, BorderLayout.SOUTH);
        setContentPane(layout);
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(event -> dispose(),
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

        setMinimumSize(new Dimension(760, 560));
        setSize(900, 680);
        setLocationRelativeTo(owner);
    }

    static void populateGuide(StyledDocument guide) {
        title(guide, "PZ Reverse Mapper workflow");
        body(guide, "This tool reverses compiled Project Zomboid map data back into editable mapping assets. It does not create a new map from scratch; it rebuilds TMX/PZW/TBX/image outputs from lotheader, lotpack, objects.lua and tilespack data.");

        section(guide, "1. Source profile");
        bullet(guide, "Auto detect reads each .lotheader and accepts both legacy 300x300 cells and Build 42 256x256 POT cells.");
        bullet(guide, "v41 source 300 and B42 source 256 are validation modes. They stop the export if the selected source does not match the expected compiled-cell size.");

        section(guide, "2. Output grid");
        bullet(guide, "WorldEd TMX 300x300 is the classic editable map workflow and remains the default.");
        bullet(guide, "Native TMX 256x256 keeps output cells aligned to Build 42 boundaries, and marks the PZW as a native-256 WorldEd project so PZ_Mapping_Tools opens it with 256x256 cells.");

        section(guide, "3. Reprojection rule");
        body(guide, "Tiles, rooms and objects are reprojected through world coordinates before they are written to the target grid.");
        code(guide, "worldX = sourceCellX * sourceCellSize + localX\ntargetCellX = floor(worldX / targetCellSize)\ntargetLocalX = worldX mod targetCellSize");
        body(guide, "That is what allows compiled 256x256 Build 42 cells to be rebuilt into 300x300 TMX cells.");

        section(guide, "4. Presets");
        bullet(guide, "Initial export - auto to 300: safest default; preserves the original 300x300 editable workflow.");
        bullet(guide, "B42 source 256 -> TMX 300: recommended for rebuilding compiled Build 42 maps into the classic WorldEd grid.");
        bullet(guide, "B42 source 256 -> TMX 256: keeps the output grid native to Build 42.");
        bullet(guide, "v41 source 300 -> TMX 300: legacy workflow with source-size validation.");
        bullet(guide, "Map only - no TBX: writes TMX/PZW and objects, but skips RoomDef and building TBX extras.");
        bullet(guide, "TBX only - rooms/buildings: writes only RoomDef TBX and supplemental building TBX outputs.");

        section(guide, "5. Asset paths");
        bullet(guide, "Folder pickers start in the application folder instead of the Desktop. The default output path is an export subfolder beside the app.");
        bullet(guide, "Project name is generated from the selected map folder name until you type a custom value.");
        bullet(guide, "Tiles/media points to the vanilla Project Zomboid media folder. It is read only and is never modified by the export.");
        bullet(guide, "Mod assets is optional. Enable it when you prepared a separate folder containing modded .tiles files and the required texture packs.");
        bullet(guide, "The mod asset folder is loaded after Tiles/media, so it can add custom sheets or replace matching sheet metadata without polluting the game media folder.");
        code(guide, "MyModAssets/\n  my_tiles.tiles\n  texturepacks/\n    my_pack.pack");
        body(guide, "A texture pack folder named texturespack is also accepted for existing prepared folders.");

        section(guide, "6. Output options");
        bullet(guide, "Clean output folder: asks for confirmation, then moves existing output contents to the " + OutputCleaner.trashName()
            + ". Protected folders such as Desktop, Documents, Downloads, the home folder and drive roots are refused.");
        bullet(guide, "Images: writes maps_img cell previews plus Map.png, Map_veg.png, world.png, optional biomemap.png, and Map_ZombieSpawnMap.png when zombie density exists.");
        bullet(guide, "Map.png is the base terrain image used by the world workflow. It does not draw RoomDefs visually.");
        bullet(guide, "Tile sheets: keeps every physical pack atlas separate under TilesRaw/<physical-pack>, merges only cut tiles under TilesSingle/<logical-pack>/<tileset>, then rebuilds eight-column sheets under Tiles/<logical-pack>. Matching .floor.pack and .pack files never share raw atlas files.");
        bullet(guide, "When Tile sheets is the only selected output, Studio uses tiles-only mode and does not read lotheaders or lotpacks.");
        bullet(guide, "Objects: reads objects.lua and writes visible WorldEd objects into the PZW project.");
        bullet(guide, "RoomDef TBX: writes one TBX per room under tmx/tbx/<cell>.");
        bullet(guide, "Building TBX pack: writes reconstructed supplemental buildings under tbx_buildings/<source-cell>. These are useful for inspection or a separate building pack, but they are not original source TBX files.");

        section(guide, "7. Testing and validation");
        bullet(guide, "Use Source cells such as 46_26,46_27 to test a small area before launching a full export.");
        bullet(guide, "Validate reads headers only. It reports source sizes, levels, estimated target cells, missing lotpacks and objects.lua presence without writing the full export.");
        bullet(guide, "Large maps need memory. If an export runs out of memory, start the jar with a larger heap, for example java -Xmx8g -jar " + AppInfo.jarFileName() + ".");
    }

    private static void title(StyledDocument guide, String text) {
        append(guide, text + "\n\n", style(Theme.UI_FAMILY, 22, true, new Color(31, 38, 52), null));
    }

    private static void section(StyledDocument guide, String text) {
        append(guide, "\n" + text + "\n", style(Theme.UI_FAMILY, 15, true, Theme.GUIDE_SECTION, null));
    }

    private static void body(StyledDocument guide, String text) {
        append(guide, text + "\n", style(Theme.UI_FAMILY, 13, false, Theme.GUIDE_BODY, null));
    }

    private static void bullet(StyledDocument guide, String text) {
        append(guide, "  - " + text + "\n", style(Theme.UI_FAMILY, 13, false, Theme.GUIDE_BODY, null));
    }

    private static void code(StyledDocument guide, String text) {
        append(guide, text + "\n", style(Theme.MONO_FAMILY, 12, false, Theme.GUIDE_CODE, Theme.GUIDE_CODE_BACKGROUND));
    }

    private static SimpleAttributeSet style(String family, int size, boolean bold, Color color, Color background) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, family);
        StyleConstants.setFontSize(attributes, size);
        StyleConstants.setBold(attributes, bold);
        StyleConstants.setForeground(attributes, color);
        if (background != null) {
            StyleConstants.setBackground(attributes, background);
        }

        return attributes;
    }

    private static void append(StyledDocument document, String text, SimpleAttributeSet attributes) {
        try {
            document.insertString(document.getLength(), text, attributes);
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
