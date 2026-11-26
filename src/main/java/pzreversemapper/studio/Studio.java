package pzreversemapper.studio;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import pzreversemapper.converter.Text;

/** Studio entry point. Also supports {@code --screenshot <png>} and {@code --screenshot-about <png>}. */
public final class Studio {
    private Studio() {
    }

    public static void main(String[] args) {
        System.setProperty("swing.boldMetal", "false");
        System.setProperty("awt.useSystemAAFontSettings", System.getProperty("awt.useSystemAAFontSettings", "on"));
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("--screenshot")) {
                saveScreenshot(Paths.get(args[1]), false);
                System.exit(0);
                return;
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("--screenshot-about")) {
                saveScreenshot(Paths.get(args[1]), true);
                System.exit(0);
                return;
            }

            SwingUtilities.invokeLater(() -> {
                configureLookAndFeel();
                new MainWindow().setVisible(true);
            });
        } catch (Exception ex) {
            System.err.println(Text.messageOf(ex));
            System.exit(2);
        }
    }

    static void configureLookAndFeel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win") || os.contains("mac")) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } else {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            }
        } catch (Exception ex) {
            // Keep the default look and feel.
        }

        FontUIResource font = new FontUIResource(Theme.ui(13f));
        for (Object key : UIManager.getLookAndFeelDefaults().keySet().toArray()) {
            if (key != null && key.toString().endsWith(".font")) {
                UIManager.put(key, font);
            }
        }

        UIManager.put("OptionPane.messageFont", font);
        UIManager.put("OptionPane.buttonFont", font);
    }

    private static void saveScreenshot(Path outputFile, boolean about) throws IOException, InterruptedException, InvocationTargetException {
        Path directory = outputFile.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }

        BufferedImage[] image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            configureLookAndFeel();
            JComponent content;
            JFrame frame = null;
            if (about) {
                content = AboutDialog.buildContent(new JButton("Close"));
                content.setSize(content.getPreferredSize());
            } else {
                frame = new MainWindow();
                frame.setSize(1280, 940);
                frame.addNotify();
                frame.validate();
                content = frame.getRootPane();
            }

            layoutTree(content);
            image[0] = render(content);
            if (frame != null) {
                frame.dispose();
            }
        });

        ImageIO.write(image[0], "png", outputFile.toFile());
    }

    private static void layoutTree(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static BufferedImage render(JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }

        return image;
    }
}
