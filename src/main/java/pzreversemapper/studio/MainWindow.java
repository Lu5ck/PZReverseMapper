package pzreversemapper.studio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import pzreversemapper.converter.AppInfo;
import pzreversemapper.converter.BuildingEdConfig;
import pzreversemapper.converter.CellCoord;
import pzreversemapper.converter.ConversionProgress;
import pzreversemapper.converter.ConversionResult;
import pzreversemapper.converter.ConverterOptions;
import pzreversemapper.converter.InvalidDataException;
import pzreversemapper.converter.LotHeaderData;
import pzreversemapper.converter.LotHeaderReader;
import pzreversemapper.converter.MapConverter;
import pzreversemapper.converter.OutputCleaner;
import pzreversemapper.converter.Text;

/** The Studio main window: workflow presets, paths, command preview, run log and actions. */
final class MainWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String APP_NAME = AppInfo.APP_NAME;
    private static final int CUSTOM_PRESET = 5;
    private static final int GRID_AUTO = 0;
    private static final int GRID_300 = 1;
    private static final int GRID_256 = 2;
    private static final int PROFILE_B42 = 2;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String[] PRESETS = {
        "Initial export - auto grid",
        "B42 source 256 -> TMX 256",
        "v41 source 300 -> TMX 300",
        "Map only - no TBX",
        "TBX only - rooms/buildings",
        "Custom"
    };

    private static final String[] SOURCE_PROFILES = {
        "Auto detect",
        "v41 and older - source 300",
        "Build 42 POT - source 256"
    };

    private static final String[] TARGET_GRIDS = {
        "Auto - match source grid",
        "WorldEd TMX 300 x 300 (v41 sources)",
        "Native TMX 256 x 256"
    };

    private final JComboBox<String> workflowPresetBox = new JComboBox<>(PRESETS);
    private final JComboBox<String> sourceProfileBox = new JComboBox<>(SOURCE_PROFILES);
    private final JComboBox<String> targetGridBox = new JComboBox<>(TARGET_GRIDS);
    private final HintTextField inputBox = new HintTextField();
    private final HintTextField outputBox = new HintTextField();
    private final HintTextField tilesBox = new HintTextField();
    private final JCheckBox useModTilesBox = new JCheckBox("Enable");
    private final HintTextField modTilesPathBox = new HintTextField();
    private final HintTextField projectNameBox = new HintTextField();
    private final HintTextField cellsBox = new HintTextField();
    private final HintTextField tbxConfigBox = new HintTextField();
    private final JCheckBox cleanBox = new JCheckBox("Clean output folder");
    private final JCheckBox imagesBox = new JCheckBox("Images");
    private final JCheckBox tilePacksBox = new JCheckBox("Tile sheets");
    private final JCheckBox objectsBox = new JCheckBox("Objects");
    private final JCheckBox roomTbxBox = new JCheckBox("RoomDef TBX");
    private final JCheckBox buildingTbxBox = new JCheckBox("Building TBX pack");
    private final JCheckBox tbxOnlyBox = new JCheckBox("TBX only");
    private final JTextArea commandBox = new JTextArea();
    private final JTextArea logBox = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel();
    private final JButton validateButton = new JButton("Validate");
    private final JButton modTilesBrowseButton = new JButton("Browse");
    private final JButton copyCommandButton = new JButton("Copy");
    private final JButton helpButton = new JButton("Guide");
    private final JButton aboutButton = new JButton("About");
    private final JButton exportButton = new AccentButton("Run export", Theme.ACCENT);
    private final JButton openOutputButton = new JButton("Open output");

    private boolean isApplyingPreset;
    private boolean isUpdatingProjectNameFromInput;
    private boolean projectNameEditedByUser;
    private String lastProgressStage;
    private int lastLoggedPercent = -1;

    MainWindow() {
        super(APP_NAME);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setIconImages(Theme.windowIcons());
        getContentPane().setBackground(Theme.BACKGROUND);
        setContentPane(buildLayout());
        setMinimumSize(new Dimension(960, 760));
        setPreferredSize(new Dimension(1120, 900));

        configureDefaults();
        wireEvents();
        configureTooltips();
        updateCommandPreview();
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildLayout() {
        JPanel shell = new JPanel(new GridBagLayout());
        shell.setBackground(Theme.BACKGROUND);
        shell.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;

        c.gridy = 0;
        shell.add(buildHeader(), c);

        c.insets = new Insets(12, 0, 0, 0);
        c.gridy = 1;
        shell.add(buildWorkflowGroup(), c);
        c.gridy = 2;
        shell.add(buildPathsGroup(), c);
        c.gridy = 3;
        shell.add(buildCommandGroup(), c);
        c.gridy = 4;
        c.weighty = 1;
        shell.add(buildLogGroup(), c);

        c.gridy = 5;
        c.weighty = 0;
        c.insets = new Insets(8, 0, 0, 0);
        shell.add(buildActions(), c);
        return shell;
    }

    private JComponent buildHeader() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Theme.HEADER);
        panel.setBorder(BorderFactory.createEmptyBorder(13, 18, 12, 18));

        JLabel title = new JLabel(APP_NAME);
        title.setForeground(Color.WHITE);
        title.setFont(Theme.uiBold(24f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Reverse compiled Project Zomboid maps into editable TMX, PZW, TBX, images and tilesheets.");
        subtitle.setForeground(Theme.SUBTITLE);
        subtitle.setFont(Theme.ui(13f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(title);
        panel.add(Box.createVerticalStrut(6));
        panel.add(subtitle);
        panel.setPreferredSize(new Dimension(10, 90));
        panel.setMinimumSize(new Dimension(10, 90));
        return panel;
    }

    private JComponent buildWorkflowGroup() {
        JPanel group = createGroup("Workflow");
        JPanel grid = createGrid();

        configureComboBox(workflowPresetBox);
        configureComboBox(sourceProfileBox);
        configureComboBox(targetGridBox);

        addCell(grid, createLabel("Preset", 170), 0, 0, 1, 0);
        addCell(grid, workflowPresetBox, 1, 0, 3, 1);

        addCell(grid, createLabel("Input profile", 170), 0, 1, 1, 0);
        addCell(grid, sourceProfileBox, 1, 1, 1, 0.5);
        addCell(grid, createLabel("Output grid", 150), 2, 1, 1, 0);
        addCell(grid, targetGridBox, 3, 1, 1, 0.5);

        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.setOpaque(false);
        for (JCheckBox checkBox : new JCheckBox[] {cleanBox, imagesBox, tilePacksBox, objectsBox, roomTbxBox, buildingTbxBox, tbxOnlyBox}) {
            checkBox.setOpaque(false);
            checkBox.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 18));
            checks.add(checkBox);
        }

        addCell(grid, createLabel("Outputs", 170), 0, 2, 1, 0);
        addCell(grid, checks, 1, 2, 3, 1);

        group.add(grid, BorderLayout.CENTER);
        return group;
    }

    private JComponent buildPathsGroup() {
        JPanel group = createGroup("Project paths");
        JPanel grid = createGrid();

        addPathRow(grid, 0, "Map folder", inputBox, () -> browseFolder(inputBox, "Compiled map folder"));
        addPathRow(grid, 1, "Output folder", outputBox, () -> browseFolder(outputBox, "Export output folder"));
        addPathRow(grid, 2, "Tiles/media", tilesBox, () -> browseFolder(tilesBox, "Project Zomboid media folder"));

        useModTilesBox.setOpaque(false);
        useModTilesBox.setPreferredSize(new Dimension(88, useModTilesBox.getPreferredSize().height));
        modTilesPathBox.setHint("Optional mod assets folder with .tiles and texturepacks/*.pack");

        JPanel modTilesPanel = new JPanel(new BorderLayout());
        modTilesPanel.setOpaque(false);
        modTilesPanel.add(useModTilesBox, BorderLayout.WEST);
        modTilesPanel.add(modTilesPathBox, BorderLayout.CENTER);

        modTilesBrowseButton.addActionListener(event -> browseFolder(modTilesPathBox, "Prepared mod tiles/assets folder"));

        addCell(grid, createLabel("Mod assets", 170), 0, 3, 1, 0);
        addCell(grid, modTilesPanel, 1, 3, 1, 1);
        addCell(grid, sized(modTilesBrowseButton, 108), 2, 3, 1, 0);

        projectNameBox.setHint("Auto from selected map folder");
        cellsBox.setHint("Optional, for example 46_26,46_27");

        tbxConfigBox.setHint("Optional PZ_Mapping_Tools folder with BuildingTiles.txt, to rebuild building TBX files");
        addPathRow(grid, 4, "BuildingEd config", tbxConfigBox, () -> browseFolder(tbxConfigBox, "BuildingEd config folder (BuildingTiles.txt)"));

        addCell(grid, createLabel("Project name", 170), 0, 5, 1, 0);
        addCell(grid, projectNameBox, 1, 5, 1, 1);
        addCell(grid, createLabel("Source cells", 170), 0, 6, 1, 0);
        addCell(grid, cellsBox, 1, 6, 1, 1);

        group.add(grid, BorderLayout.CENTER);
        return group;
    }

    private JComponent buildCommandGroup() {
        JPanel group = createGroup("Reproducible command");

        commandBox.setEditable(false);
        commandBox.setLineWrap(true);
        commandBox.setWrapStyleWord(false);
        commandBox.setRows(4);
        commandBox.setFont(Theme.mono(12f));
        commandBox.setBackground(Theme.COMMAND_BACKGROUND);
        commandBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JScrollPane scroll = new JScrollPane(commandBox, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        copyCommandButton.addActionListener(event -> copyCommand());
        JPanel buttonColumn = new JPanel(new BorderLayout());
        buttonColumn.setOpaque(false);
        buttonColumn.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        buttonColumn.add(sized(copyCommandButton, 108), BorderLayout.NORTH);

        group.add(scroll, BorderLayout.CENTER);
        group.add(buttonColumn, BorderLayout.EAST);
        return group;
    }

    private JComponent buildLogGroup() {
        JPanel group = createGroup("Run log");
        logBox.setEditable(false);
        logBox.setFont(Theme.mono(12f));
        logBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        if (logBox.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        }

        JScrollPane scroll = new JScrollPane(logBox);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.setPreferredSize(new Dimension(10, 120));
        group.add(scroll, BorderLayout.CENTER);
        return group;
    }

    private JComponent buildActions() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        statusLabel.setForeground(Theme.STATUS);
        statusLabel.setText("Ready.");
        statusLabel.setMinimumSize(new Dimension(40, 10));
        statusLabel.setPreferredSize(new Dimension(40, 34));

        progressBar.setValue(0);
        validateButton.addActionListener(event -> validateWorkflow());
        aboutButton.addActionListener(event -> showAbout());
        helpButton.addActionListener(event -> showGuide());
        openOutputButton.setEnabled(false);
        openOutputButton.addActionListener(event -> openOutputFolder());
        exportButton.setFont(Theme.uiBold(13f));
        exportButton.addActionListener(event -> runExport());

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.weightx = 1;
        panel.add(statusLabel, c);

        c.weightx = 0;
        c.insets = new Insets(0, 8, 0, 0);
        Object[][] items = {
            {progressBar, 166},
            {aboutButton, 96},
            {helpButton, 96},
            {validateButton, 110},
            {openOutputButton, 124},
            {exportButton, 138}
        };
        for (int i = 0; i < items.length; i++) {
            c.gridx = i + 1;
            panel.add(sized((JComponent) items[i][0], (Integer) items[i][1], 36), c);
        }

        return panel;
    }

    private void configureDefaults() {
        isApplyingPreset = true;
        workflowPresetBox.setSelectedIndex(0);
        sourceProfileBox.setSelectedIndex(0);
        targetGridBox.setSelectedIndex(0);
        cleanBox.setSelected(true);
        imagesBox.setSelected(true);
        tilePacksBox.setSelected(false);
        objectsBox.setSelected(true);
        roomTbxBox.setSelected(true);
        buildingTbxBox.setSelected(true);
        tbxOnlyBox.setSelected(false);
        useModTilesBox.setSelected(false);
        outputBox.setText(AppInfo.applicationDirectory().resolve("export").toString());
        projectNameBox.setText("");
        projectNameEditedByUser = false;
        isApplyingPreset = false;
        applyTbxOnlyState();
        applyModTilesState();

        appendLog("Ready. Select a preset, validate, then run the export.");
    }

    private void wireEvents() {
        onSelectionChanged(workflowPresetBox, this::applyWorkflowPreset);
        onSelectionChanged(sourceProfileBox, this::markCustomAndRefresh);
        onSelectionChanged(targetGridBox, this::markCustomAndRefresh);

        for (JCheckBox checkBox : new JCheckBox[] {cleanBox, imagesBox, tilePacksBox, objectsBox, roomTbxBox, buildingTbxBox}) {
            checkBox.addItemListener(event -> markCustomAndRefresh());
        }

        tbxOnlyBox.addItemListener(event -> {
            applyTbxOnlyState();
            markCustomAndRefresh();
        });
        useModTilesBox.addItemListener(event -> {
            applyModTilesState();
            markCustomAndRefresh();
        });

        onTextChanged(inputBox, text -> {
            updateProjectNameFromInput();
            updateCommandPreview();
        });
        onTextChanged(outputBox, text -> updateCommandPreview());
        onTextChanged(tilesBox, text -> updateCommandPreview());
        onTextChanged(modTilesPathBox, text -> updateCommandPreview());
        onTextChanged(projectNameBox, text -> {
            if (!isUpdatingProjectNameFromInput) {
                projectNameEditedByUser = !Text.isNullOrWhiteSpace(projectNameBox.getText());
            }

            updateCommandPreview();
        });
        onTextChanged(cellsBox, text -> updateCommandPreview());
        onTextChanged(tbxConfigBox, text -> updateCommandPreview());
    }

    private void configureTooltips() {
        ToolTipManager manager = ToolTipManager.sharedInstance();
        manager.setDismissDelay(16000);
        manager.setInitialDelay(350);
        manager.setReshowDelay(150);

        String trash = OutputCleaner.trashName();
        workflowPresetBox.setToolTipText("Chooses a coherent set of source, output grid and extra exports.");
        sourceProfileBox.setToolTipText("Auto reads the lotheader size. v41 validates 300. B42 validates 256.");
        targetGridBox.setToolTipText("Auto keeps the source grid. Build 42 maps always export to native 256x256 cells "
            + "in a native-256 WorldEd project; v41 maps can also be converted to 256.");
        cleanBox.setToolTipText("Asks for confirmation, then moves existing output contents to the " + trash + ".");
        imagesBox.setToolTipText("Writes per-cell and merged PNG map previews used by the map workflow.");
        tilePacksBox.setToolTipText("When selected alone, extracts tiles without reading lotheaders or lotpacks.");
        objectsBox.setToolTipText("Reads objects.lua and writes objects into the PZW project.");
        roomTbxBox.setToolTipText("Writes one TBX per RoomDef under tmx/tbx/<cell>.");
        buildingTbxBox.setToolTipText("Writes supplemental building TBX files under tbx_buildings/<source-cell>.");
        tbxOnlyBox.setToolTipText("Writes only TBX outputs. TMX, PZW and objects.lua are skipped.");
        cellsBox.setToolTipText("Optional filter such as 46_26,46_27. Empty exports all source cells.");
        tbxConfigBox.setToolTipText("Folder holding BuildingTiles.txt, BuildingFurniture.txt and TMXConfig.txt from PZ_Mapping_Tools. "
            + "Building TBX files are then rebuilt with rooms, walls, floors, doors, windows, stairs and furniture.");
        tilesBox.setToolTipText("Vanilla Project Zomboid media folder. This input is read only.");
        useModTilesBox.setToolTipText("Enable a second read-only asset source for modded .tiles and .pack files.");
        modTilesPathBox.setToolTipText("Prepared mod asset folder. Put .tiles here and .pack files under texturepacks or texturespack.");
        modTilesBrowseButton.setToolTipText("Select the prepared mod asset folder.");
        validateButton.setToolTipText("Reads headers and checks the workflow before writing files.");
        aboutButton.setToolTipText("About PZ Reverse Mapper.");
        helpButton.setToolTipText("Open the workflow guide.");
        copyCommandButton.setToolTipText("Copy the equivalent CLI command.");
    }

    private void applyWorkflowPreset() {
        if (isApplyingPreset) {
            return;
        }

        isApplyingPreset = true;
        switch (workflowPresetBox.getSelectedIndex()) {
            case 0 -> applyPreset(0, GRID_AUTO, true, true, true, true, false);
            case 1 -> applyPreset(PROFILE_B42, GRID_256, true, true, true, true, false);
            case 2 -> applyPreset(1, GRID_300, true, true, true, true, false);
            case 3 -> applyPreset(0, GRID_AUTO, true, true, false, false, false);
            case 4 -> applyPreset(0, GRID_AUTO, false, false, true, true, true);
            default -> {
            }
        }

        cleanBox.setSelected(true);
        isApplyingPreset = false;
        applyTbxOnlyState();
        updateCommandPreview();
    }

    private void applyPreset(int sourceProfile, int targetGrid, boolean images, boolean objects, boolean roomTbx, boolean buildingTbx,
                             boolean tbxOnly) {
        sourceProfileBox.setSelectedIndex(sourceProfile);
        targetGridBox.setSelectedIndex(targetGrid);
        imagesBox.setSelected(images);
        tilePacksBox.setSelected(false);
        objectsBox.setSelected(objects);
        roomTbxBox.setSelected(roomTbx);
        buildingTbxBox.setSelected(buildingTbx);
        tbxOnlyBox.setSelected(tbxOnly);
    }

    private void markCustomAndRefresh() {
        if (!isApplyingPreset && workflowPresetBox.getSelectedIndex() != CUSTOM_PRESET) {
            isApplyingPreset = true;
            workflowPresetBox.setSelectedIndex(CUSTOM_PRESET);
            isApplyingPreset = false;
        }

        updateCommandPreview();
    }

    private void applyTbxOnlyState() {
        if (tbxOnlyBox.isSelected()) {
            imagesBox.setSelected(false);
            imagesBox.setEnabled(false);
            objectsBox.setSelected(false);
            objectsBox.setEnabled(false);
            return;
        }

        imagesBox.setEnabled(true);
        objectsBox.setEnabled(true);
    }

    private void applyModTilesState() {
        boolean enabled = useModTilesBox.isSelected();
        modTilesPathBox.setEnabled(enabled);
        modTilesBrowseButton.setEnabled(enabled);
        modTilesPathBox.setBackground(enabled ? Color.WHITE : Theme.DISABLED_FIELD);
    }

    private void validateWorkflow() {
        try {
            ConverterOptions options = buildOptions();
            if (options.tilesOnly()) {
                appendLog("");
                appendLog("Validation OK.");
                appendLog("Mode:           tiles only");
                appendLog("Map data:       not read");
                appendLog("Tiles/media:    " + (Text.isNullOrWhiteSpace(options.tilesPath()) ? "not set" : options.tilesPath()));
                appendLog("Mod assets:     " + (Text.isNullOrWhiteSpace(options.modTilesPath()) ? "disabled" : options.modTilesPath()));
                statusLabel.setText("Validation OK - tiles-only mode, map data will not be read.");
                return;
            }

            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            WorkflowInspection inspection;
            try {
                inspection = inspectWorkflow(options);
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }

            appendLog("");
            appendLog("Validation OK.");
            appendLog("Source cells:   " + inspection.sourceCellCount());
            appendLog("Source sizes:   " + inspection.sourceSizes());
            appendLog("Levels:         " + inspection.levelRange());
            appendLog("Output grid:    " + inspection.outputGrid());
            appendLog("TMX cells:      " + inspection.estimatedTargetCellCount());
            appendLog("Missing packs:  " + inspection.missingLotPackCount());
            appendLog("objects.lua:    " + (inspection.hasObjectsLua() ? "found" : "not found"));
            if (options.exportBuildingTbx() && options.tbxConfigPath() != null) {
                BuildingEdConfig buildingConfig = BuildingEdConfig.load(options.tbxConfigPath());
                appendLog("BuildingEd:     " + buildingConfig.entryCount() + " tile entries, " + buildingConfig.furnitureCount()
                    + " furniture (" + buildingConfig.directory() + ")");
            } else {
                appendLog("BuildingEd:     not set (building TBX files are plain tile dumps)");
            }
            statusLabel.setText("Validation OK - " + inspection.sourceCellCount() + " source cell(s), "
                + inspection.estimatedTargetCellCount() + " target TMX cell(s).");
        } catch (Exception ex) {
            appendLog("Validation failed: " + Text.messageOf(ex));
            JOptionPane.showMessageDialog(this, Text.messageOf(ex), "Validation failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void runExport() {
        ConverterOptions options;
        try {
            options = buildOptions();
            if (!confirmCleanOutputIfNeeded(options)) {
                return;
            }
        } catch (Exception ex) {
            exportFailed(ex);
            return;
        }

        setBusy(true);
        openOutputButton.setEnabled(false);
        lastProgressStage = null;
        lastLoggedPercent = -1;
        appendLog("");
        appendLog("Preset:         " + workflowPresetBox.getSelectedItem());
        appendLog("Input profile:  " + sourceProfileBox.getSelectedItem());
        appendLog("Output grid:    " + targetGridBox.getSelectedItem());
        appendLog("Input:          " + (options.tilesOnly() ? "not used (tiles only)" : options.inputDirectory()));
        appendLog("Output:         " + options.outputDirectory());
        appendLog("Tiles/media:    " + (Text.isNullOrWhiteSpace(options.tilesPath()) ? "not set" : options.tilesPath()));
        appendLog("Mod assets:     " + (Text.isNullOrWhiteSpace(options.modTilesPath()) ? "disabled" : options.modTilesPath()));
        appendLog("Export started...");

        SwingWorker<ConversionResult, ConversionProgress> worker = new SwingWorker<>() {
            @Override
            protected ConversionResult doInBackground() throws Exception {
                return new MapConverter(options, progress -> publish(progress)).run();
            }

            @Override
            protected void process(List<ConversionProgress> chunks) {
                chunks.forEach(MainWindow.this::reportProgress);
            }

            @Override
            protected void done() {
                try {
                    ConversionResult result = get();
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(100);
                    statusLabel.setText("Export finished.");
                    appendLog("Export finished.");
                    appendLog("Source cells:   " + result.sourceCellCount());
                    appendLog("TMX cells:      " + result.targetCellCount());
                    appendLog("Objects:        " + result.objectCount());
                    appendLog("Images:         " + result.imageCount());
                    appendLog("Tile images:    " + result.tileImageCount());
                    appendLog("RoomDef TBX:    " + result.tbxCount());
                    appendLog("Building TBX:   " + result.buildingTbxCount());
                    appendLog("Tilesets:       " + result.tileSetCount());
                    appendLog("Elapsed:        " + MapConverter.formatElapsed(result.elapsed()));
                    appendLog("Project:        " + (Text.isNullOrWhiteSpace(result.projectFile()) ? "not written" : result.projectFile()));
                    openOutputButton.setEnabled(true);
                } catch (ExecutionException ex) {
                    exportFailed(ex.getCause() == null ? ex : ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    exportFailed(ex);
                } finally {
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void exportFailed(Throwable error) {
        String message = error instanceof OutOfMemoryError
            ? "Java ran out of memory. Restart with a larger heap, for example: java -Xmx8g -jar " + AppInfo.jarFileName()
            : Text.messageOf(error);
        statusLabel.setText("Export failed.");
        appendLog("Error: " + message);
        JOptionPane.showMessageDialog(this, message, "Export failed", JOptionPane.ERROR_MESSAGE);
    }

    private boolean confirmCleanOutputIfNeeded(ConverterOptions options) throws Exception {
        if (!options.cleanOutput() || !OutputCleaner.hasContent(options.outputDirectory())) {
            return true;
        }

        OutputCleaner.ensureSafeCleanTarget(options.outputDirectory());

        String message = "Clean output folder is enabled.\n\n"
            + "Existing files and folders in this output directory will be moved to the " + OutputCleaner.trashName()
            + " before export:\n\n"
            + options.outputDirectory() + "\n\n"
            + "Continue?";

        Object[] choices = {"Yes", "No"};
        int result = JOptionPane.showOptionDialog(
            this,
            message,
            "Confirm output cleanup",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            choices,
            choices[1]);

        if (result != 0) {
            statusLabel.setText("Export canceled.");
            appendLog("Export canceled: output cleanup was not confirmed.");
            return false;
        }

        options.setCleanOutputConfirmed(true);
        return true;
    }

    private void reportProgress(ConversionProgress progress) {
        statusLabel.setText(progress.stage() + ": " + progress.message());

        Integer percent = progress.percent();
        if (percent != null) {
            progressBar.setIndeterminate(false);
            progressBar.setValue(percent);

            boolean isNewStage = !progress.stage().equals(lastProgressStage);
            if (isNewStage || percent == 100 || percent - lastLoggedPercent >= 10) {
                appendLog(progress.stage() + ": " + progress.message() + " (" + percent + "%)");
                lastProgressStage = progress.stage();
                lastLoggedPercent = percent;
            }

            return;
        }

        if (!progress.stage().equals(lastProgressStage)) {
            appendLog(progress.stage() + ": " + progress.message());
            lastProgressStage = progress.stage();
            lastLoggedPercent = -1;
        }
    }

    private ConverterOptions buildOptions() throws Exception {
        boolean tilesOnly = isTilesOnlySelection();
        String input = Text.trim(inputBox.getText());
        String output = Text.trim(outputBox.getText());
        if (!tilesOnly && Text.isNullOrWhiteSpace(input)) {
            throw new IllegalStateException("Select a compiled map input folder.");
        }

        if (Text.isNullOrWhiteSpace(output)) {
            throw new IllegalStateException("Select an output folder.");
        }

        String fullInput = Text.isNullOrWhiteSpace(input) ? "" : ConverterOptions.fullPath(input);
        String fullOutput = ConverterOptions.fullPath(output);
        if (!tilesOnly && !Files.isDirectory(Paths.get(fullInput))) {
            throw new FileNotFoundException("Input folder not found: " + fullInput);
        }

        if (!Text.isNullOrWhiteSpace(fullInput)
            && Text.equalsOrdinalIgnoreCase(Text.trimPath(fullInput), Text.trimPath(fullOutput))) {
            throw new IllegalStateException("Input and output folders must be different.");
        }

        if (tbxOnlyBox.isSelected() && !roomTbxBox.isSelected() && !buildingTbxBox.isSelected()) {
            throw new IllegalStateException("TBX only requires RoomDef TBX and/or Building TBX to be enabled.");
        }

        if (sourceProfileBox.getSelectedIndex() == PROFILE_B42 && targetGridBox.getSelectedIndex() == GRID_300) {
            throw new IllegalStateException("Build 42 maps (256x256 cells) can only be exported to native 256x256 cells. "
                + "Choose \"Native TMX 256 x 256\" or \"Auto\" as the output grid.");
        }

        String tiles = Text.trim(tilesBox.getText());
        if (!Text.isNullOrWhiteSpace(tiles) && !Files.exists(Paths.get(tiles))) {
            throw new FileNotFoundException("Tiles/media path not found: " + tiles);
        }

        String modTiles = Text.trim(modTilesPathBox.getText());
        String fullModTiles = null;
        if (useModTilesBox.isSelected()) {
            if (Text.isNullOrWhiteSpace(modTiles)) {
                throw new IllegalStateException("Select the mod assets folder or disable the Mod assets option.");
            }

            if (!Files.exists(Paths.get(modTiles))) {
                throw new FileNotFoundException("Mod assets path not found: " + modTiles);
            }

            fullModTiles = ConverterOptions.fullPath(modTiles);
        }

        String tbxConfig = Text.trim(tbxConfigBox.getText());
        if (!Text.isNullOrWhiteSpace(tbxConfig) && !Files.isDirectory(Paths.get(tbxConfig))) {
            throw new FileNotFoundException("BuildingEd config folder not found: " + tbxConfig);
        }

        if (tilesOnly && Text.isNullOrWhiteSpace(tiles) && Text.isNullOrWhiteSpace(fullModTiles)) {
            throw new IllegalStateException("Tiles-only mode requires a Tiles/media or Mod assets path.");
        }

        return ConverterOptions.builder()
            .inputDirectory(fullInput)
            .outputDirectory(fullOutput)
            .projectName(resolveProjectName(projectNameBox.getText(), fullInput))
            .tilesPath(Text.isNullOrWhiteSpace(tiles) ? null : ConverterOptions.fullPath(tiles))
            .modTilesPath(fullModTiles)
            .expectedSourceCellSize(getExpectedSourceCellSize())
            .targetCellSize(getRequestedTargetCellSize())
            .cleanOutput(cleanBox.isSelected())
            .exportImages(imagesBox.isSelected() && !tbxOnlyBox.isSelected())
            .exportTilePacks(tilePacksBox.isSelected())
            .exportObjects(objectsBox.isSelected() && !tbxOnlyBox.isSelected())
            .exportRoomTbx(roomTbxBox.isSelected())
            .exportBuildingTbx(buildingTbxBox.isSelected())
            .tbxOnly(tbxOnlyBox.isSelected())
            .tilesOnly(tilesOnly)
            .includeCells(parseCells(cellsBox.getText()))
            .tbxConfigPath(Text.isNullOrWhiteSpace(tbxConfig) ? null : ConverterOptions.fullPath(tbxConfig))
            .build();
    }

    private boolean isTilesOnlySelection() {
        return tilePacksBox.isSelected()
            && !imagesBox.isSelected()
            && !objectsBox.isSelected()
            && !roomTbxBox.isSelected()
            && !buildingTbxBox.isSelected()
            && !tbxOnlyBox.isSelected();
    }

    private record WorkflowInspection(
        int sourceCellCount,
        String sourceSizes,
        String levelRange,
        String outputGrid,
        int estimatedTargetCellCount,
        int missingLotPackCount,
        boolean hasObjectsLua) {
    }

    private static WorkflowInspection inspectWorkflow(ConverterOptions options) throws Exception {
        Map<CellCoord, LotHeaderData> headers = LotHeaderReader.readAll(options.inputDirectory());
        if (options.includeCells() != null) {
            headers.keySet().retainAll(options.includeCells());
        }

        if (headers.isEmpty()) {
            throw new InvalidDataException("No matching .lotheader files found in " + options.inputDirectory());
        }

        Collection<LotHeaderData> values = headers.values();
        MapConverter.validateSourceCellSize(options.expectedSourceCellSize(), values);
        int targetCellSize = MapConverter.resolveTargetCellSize(options.targetCellSize(), values);

        int missingLotPacks = 0;
        TreeMap<Integer, Integer> sizes = new TreeMap<>();
        int minLevel = Integer.MAX_VALUE;
        int maxLevel = Integer.MIN_VALUE;
        for (LotHeaderData header : values) {
            if (!Files.isRegularFile(Paths.get(options.inputDirectory(), "world_" + header.cellX() + "_" + header.cellY() + ".lotpack"))) {
                missingLotPacks++;
            }

            sizes.merge(header.cellDim(), 1, Integer::sum);
            minLevel = Math.min(minLevel, header.minLevel());
            maxLevel = Math.max(maxLevel, header.maxLevel());
        }

        List<String> sourceSizes = new ArrayList<>();
        sizes.forEach((size, count) -> sourceSizes.add(size + "x" + size + " (" + count + ")"));

        return new WorkflowInspection(
            headers.size(),
            String.join(", ", sourceSizes),
            minLevel + ".." + maxLevel,
            targetCellSize + "x" + targetCellSize + (options.targetCellSize() == null ? " (auto)" : ""),
            MapConverter.estimateTargetCellCount(values, targetCellSize),
            missingLotPacks,
            Files.isRegularFile(Paths.get(options.inputDirectory(), "objects.lua")));
    }

    private void updateCommandPreview() {
        commandBox.setText(buildCommandPreview());
        commandBox.setCaretPosition(0);
    }

    private String buildCommandPreview() {
        boolean tilesOnly = isTilesOnlySelection();
        List<String> args = new ArrayList<>();
        args.add(AppInfo.cliCommand());

        if (!tilesOnly) {
            addArg(args, "--input", Text.trim(inputBox.getText()));
        }

        addArg(args, "--output", Text.trim(outputBox.getText()));
        addArg(args, "--tiles", Text.trim(tilesBox.getText()));
        if (useModTilesBox.isSelected()) {
            addArg(args, "--mod-tiles", Text.trim(modTilesPathBox.getText()));
        }

        if (!tilesOnly) {
            addArg(args, "--name", resolveProjectName(projectNameBox.getText(), inputBox.getText()));

            Integer sourceCellSize = getExpectedSourceCellSize();
            if (sourceCellSize != null) {
                addArg(args, "--source-cell-size", sourceCellSize.toString());
            }

            Integer targetCellSize = getRequestedTargetCellSize();
            if (targetCellSize != null) {
                addArg(args, "--target-cell-size", targetCellSize.toString());
            }

            if (!Text.isNullOrWhiteSpace(cellsBox.getText())) {
                addArg(args, "--cells", Text.trim(cellsBox.getText()));
            }

            if (buildingTbxBox.isSelected()) {
                addArg(args, "--tbx-config", Text.trim(tbxConfigBox.getText()));
            }
        }

        if (cleanBox.isSelected()) {
            args.add("--clean");
        }

        if (tilesOnly) {
            args.add("--tiles-only");
            return String.join(" ", args);
        }

        if (tbxOnlyBox.isSelected()) {
            args.add("--tbx-only");
        }

        if (!imagesBox.isSelected() && !tbxOnlyBox.isSelected()) {
            args.add("--no-images");
        }

        if (tilePacksBox.isSelected()) {
            args.add("--extract-tiles");
        }

        if (!objectsBox.isSelected() && !tbxOnlyBox.isSelected()) {
            args.add("--no-objects");
        }

        if (!roomTbxBox.isSelected()) {
            args.add("--no-room-tbx");
        }

        if (!buildingTbxBox.isSelected()) {
            args.add("--no-building-tbx");
        }

        return String.join(" ", args);
    }

    private static void addArg(List<String> args, String name, String value) {
        if (Text.isNullOrWhiteSpace(value)) {
            return;
        }

        args.add(name);
        args.add("\"" + value.replace("\"", "\\\"") + "\"");
    }

    private void copyCommand() {
        if (Text.isNullOrWhiteSpace(commandBox.getText())) {
            return;
        }

        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(commandBox.getText()), null);
            appendLog("Command copied to clipboard.");
        } catch (IllegalStateException ex) {
            appendLog("Clipboard is busy; the command was not copied.");
        }
    }

    private void showGuide() {
        new GuideDialog(this).setVisible(true);
    }

    private void showAbout() {
        new AboutDialog(this).setVisible(true);
    }

    private void updateProjectNameFromInput() {
        if (projectNameEditedByUser) {
            return;
        }

        isUpdatingProjectNameFromInput = true;
        try {
            projectNameBox.setText(resolveProjectName("", inputBox.getText()));
            projectNameEditedByUser = false;
        } finally {
            isUpdatingProjectNameFromInput = false;
        }
    }

    private static String resolveProjectName(String enteredName, String inputPath) {
        if (!Text.isNullOrWhiteSpace(enteredName)) {
            return sanitizeProjectName(Text.trim(enteredName));
        }

        String trimmedInput = Text.trim(inputPath);
        if (!Text.isNullOrWhiteSpace(trimmedInput)) {
            String folderName = Text.fileName(Text.trimPath(trimmedInput));
            if (!Text.isNullOrWhiteSpace(folderName)) {
                return sanitizeProjectName(folderName);
            }
        }

        return ConverterOptions.DEFAULT_PROJECT_NAME;
    }

    private static String sanitizeProjectName(String value) {
        String sanitized = Text.sanitizeFileName(value);
        return Text.isNullOrWhiteSpace(sanitized) ? ConverterOptions.DEFAULT_PROJECT_NAME : sanitized;
    }

    /** The selected output grid, or {@code null} for auto (match the source grid). */
    private Integer getRequestedTargetCellSize() {
        return switch (targetGridBox.getSelectedIndex()) {
            case GRID_300 -> 300;
            case GRID_256 -> 256;
            default -> null;
        };
    }

    private Integer getExpectedSourceCellSize() {
        return switch (sourceProfileBox.getSelectedIndex()) {
            case 1 -> 300;
            case 2 -> 256;
            default -> null;
        };
    }

    private static Set<CellCoord> parseCells(String value) {
        if (Text.isNullOrWhiteSpace(value)) {
            return null;
        }

        Set<CellCoord> cells = new LinkedHashSet<>();
        for (String token : ConverterOptions.splitCellTokens(value)) {
            CellCoord cell = ConverterOptions.parseCellToken(token);
            if (cell == null) {
                throw new IllegalStateException("Invalid source cell: " + token + ". Expected X_Y.");
            }

            cells.add(cell);
        }

        return cells.isEmpty() ? null : cells;
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        validateButton.setEnabled(!busy);
        copyCommandButton.setEnabled(!busy);
        aboutButton.setEnabled(!busy);
        helpButton.setEnabled(!busy);
        exportButton.setEnabled(!busy);
        modTilesPathBox.setEnabled(!busy && useModTilesBox.isSelected());
        modTilesBrowseButton.setEnabled(!busy && useModTilesBox.isSelected());
        progressBar.setIndeterminate(busy);
        if (!busy && progressBar.getValue() < 100) {
            progressBar.setValue(0);
        }
    }

    private void appendLog(String message) {
        logBox.append(LocalTime.now().format(LOG_TIME) + "  " + message + "\n");
    }

    private static JPanel createGroup(String title) {
        JPanel group = new JPanel(new BorderLayout());
        group.setBackground(Color.WHITE);
        TitledBorder titled = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Theme.BORDER), title);
        titled.setTitleFont(Theme.uiBold(13f));
        titled.setTitleColor(Theme.GROUP_TITLE);
        group.setBorder(BorderFactory.createCompoundBorder(titled, BorderFactory.createEmptyBorder(4, 12, 10, 12)));
        return group;
    }

    private static JPanel createGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        return grid;
    }

    private static void addCell(JPanel grid, JComponent component, int column, int row, int columnSpan, double weightX) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row;
        c.gridwidth = columnSpan;
        c.weightx = weightX;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, column + columnSpan >= 4 || component instanceof JButton ? 0 : 8);
        grid.add(component, c);
    }

    private static JLabel createLabel(String text, int width) {
        JLabel label = new JLabel(text);
        label.setPreferredSize(new Dimension(width, label.getPreferredSize().height));
        label.setMinimumSize(new Dimension(width, label.getPreferredSize().height));
        return label;
    }

    private static void configureComboBox(JComboBox<String> comboBox) {
        comboBox.setMaximumRowCount(12);
        comboBox.setPreferredSize(new Dimension(120, Math.max(28, comboBox.getPreferredSize().height)));
    }

    private static JComponent sized(JComponent component, int width) {
        return sized(component, width, Math.max(28, component.getPreferredSize().height));
    }

    private static JComponent sized(JComponent component, int width, int height) {
        Dimension size = new Dimension(width, height);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        return component;
    }

    private void addPathRow(JPanel grid, int row, String label, HintTextField textBox, Runnable browse) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> browse.run());

        addCell(grid, createLabel(label, 170), 0, row, 1, 0);
        addCell(grid, textBox, 1, row, 1, 1);
        addCell(grid, sized(button, 108), 2, row, 1, 0);
    }

    private void browseFolder(HintTextField target, String description) {
        File current = new File(Text.trim(target.getText()));
        File start = current.isDirectory() ? current : AppInfo.applicationDirectory().toFile();

        JFileChooser chooser = new JFileChooser(start);
        chooser.setDialogTitle(description);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (current.isDirectory()) {
            chooser.setSelectedFile(current);
        }

        if (chooser.showDialog(this, "Select folder") == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openOutputFolder() {
        String output = Text.trim(outputBox.getText());
        File directory = new File(output);
        if (!directory.isDirectory()) {
            return;
        }

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory);
            } else {
                appendLog("Opening folders is not supported on this system: " + directory);
            }
        } catch (Exception ex) {
            appendLog("Could not open output folder: " + Text.messageOf(ex));
        }
    }

    private static void onSelectionChanged(JComboBox<String> comboBox, Runnable handler) {
        comboBox.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                handler.run();
            }
        });
    }

    private static void onTextChanged(HintTextField field, Consumer<String> handler) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                handler.accept(field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                handler.accept(field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                handler.accept(field.getText());
            }
        });
    }
}
