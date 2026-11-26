package pzreversemapper.studio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import pzreversemapper.converter.AppInfo;

/** The About window. */
final class AboutDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    static final String DESCRIPTION =
        "PZ Reverse Mapper rebuilds editable mapping assets from compiled Project Zomboid map data.\n\n"
            + "It converts legacy 300 × 300 and Build 42 256 × 256 compiled cells into TMX/PZW projects, "
            + "restores objects and room/building metadata, creates map previews, and reconstructs tilesets "
            + "directly from .pack atlas metadata.\n\n"
            + "The tile extractor keeps every physical atlas pack separate, restores each tile to its real "
            + "canvas, merges matching .floor.pack tiles only after cutting, and rebuilds tilesets with the "
            + "standard eight-column layout.\n\n"
            + "This standalone application is the modern successor to the original PZ_Mapper research project. "
            + "It contains only the Java Studio and command-line converter, packaged as a single jar.\n\n"
            + "PZ Reverse Mapper is source-available software. Official releases are licensed for personal, "
            + "non-commercial use; redistribution and commercial exploitation require written permission.\n\n"
            + "PZ Reverse Mapper is an unofficial community tool. Project Zomboid, WorldEd and TileZed are "
            + "created by The Indie Stone. No game assets are included or redistributed.";

    AboutDialog(Window owner) {
        super(owner, "About " + AppInfo.APP_NAME, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> dispose());
        setContentPane(buildContent(closeButton));
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setLocationRelativeTo(owner);
    }

    static JPanel buildContent(JButton closeButton) {
        JPanel layout = new JPanel(new BorderLayout(0, 12));
        layout.setBackground(Theme.BACKGROUND);
        layout.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        layout.setPreferredSize(new Dimension(680, 580));

        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);

        JLabel iconBox = new JLabel();
        BufferedImage logo = Theme.logo();
        if (logo != null) {
            iconBox.setIcon(new ImageIcon(Theme.scale(logo, 88, 88)));
        }

        JLabel title = new JLabel(AppInfo.APP_NAME);
        title.setFont(Theme.uiBold(26f));
        title.setForeground(new Color(31, 38, 52));
        title.setVerticalAlignment(SwingConstants.BOTTOM);

        JLabel versionLabel = new JLabel("Version " + AppInfo.version());
        versionLabel.setForeground(Theme.ABOUT_VERSION);
        versionLabel.setVerticalAlignment(SwingConstants.TOP);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 2;
        c.insets = new Insets(0, 0, 8, 16);
        header.add(iconBox, c);

        c.gridx = 1;
        c.gridheight = 1;
        c.weightx = 1;
        c.weighty = 1;
        c.anchor = GridBagConstraints.SOUTHWEST;
        c.insets = new Insets(0, 0, 2, 0);
        header.add(title, c);

        c.gridy = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        header.add(versionLabel, c);

        JTextArea description = new JTextArea(DESCRIPTION);
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setFont(Theme.ui(13f));
        description.setBackground(Color.WHITE);
        description.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        description.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(description, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        closeButton.setPreferredSize(new Dimension(112, 32));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(closeButton);

        layout.add(header, BorderLayout.NORTH);
        layout.add(scroll, BorderLayout.CENTER);
        layout.add(buttons, BorderLayout.SOUTH);
        return layout;
    }
}
