package pzreversemapper.studio;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/** Colours, fonts and artwork shared by the Studio windows. */
final class Theme {
    static final Color BACKGROUND = new Color(244, 246, 249);
    static final Color HEADER = new Color(31, 38, 52);
    static final Color SUBTITLE = new Color(211, 218, 228);
    static final Color STATUS = new Color(66, 75, 92);
    static final Color ACCENT = new Color(42, 115, 204);
    static final Color BORDER = new Color(214, 219, 226);
    static final Color GROUP_TITLE = new Color(43, 49, 61);
    static final Color COMMAND_BACKGROUND = new Color(250, 251, 253);
    static final Color DISABLED_FIELD = new Color(246, 248, 251);
    static final Color HINT = new Color(140, 148, 160);
    static final Color GUIDE_SECTION = new Color(42, 86, 145);
    static final Color GUIDE_BODY = new Color(43, 49, 61);
    static final Color GUIDE_CODE = new Color(25, 33, 45);
    static final Color GUIDE_CODE_BACKGROUND = new Color(240, 243, 247);
    static final Color ABOUT_VERSION = new Color(91, 101, 118);

    static final String UI_FAMILY = pickFamily("Segoe UI", "Helvetica Neue", "Noto Sans", "DejaVu Sans", Font.SANS_SERIF);
    static final String MONO_FAMILY = pickFamily("Consolas", "Menlo", "DejaVu Sans Mono", "Liberation Mono", Font.MONOSPACED);

    private static final String LOGO_RESOURCE = "/pzreversemapper/studio/PZReverseMapper.png";
    private static BufferedImage logo;
    private static boolean logoLoaded;

    private Theme() {
    }

    static Font ui(float size) {
        return new Font(UI_FAMILY, Font.PLAIN, 12).deriveFont(size);
    }

    static Font uiBold(float size) {
        return new Font(UI_FAMILY, Font.BOLD, 12).deriveFont(size);
    }

    static Font mono(float size) {
        return new Font(MONO_FAMILY, Font.PLAIN, 12).deriveFont(size);
    }

    /** The application logo, or {@code null} when the resource is unavailable. */
    static synchronized BufferedImage logo() {
        if (!logoLoaded) {
            logoLoaded = true;
            try (InputStream stream = Theme.class.getResourceAsStream(LOGO_RESOURCE)) {
                logo = stream == null ? null : ImageIO.read(stream);
            } catch (IOException ex) {
                logo = null;
            }
        }

        return logo;
    }

    static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        BufferedImage source = logo();
        if (source != null) {
            for (int size : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) {
                icons.add(scale(source, size, size));
            }
        }

        return icons;
    }

    /** High-quality downscale using repeated halving, then a final bicubic step. */
    static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int currentWidth = source.getWidth();
        int currentHeight = source.getHeight();
        while (currentWidth / 2 >= width && currentHeight / 2 >= height) {
            currentWidth /= 2;
            currentHeight /= 2;
            current = resize(current, currentWidth, currentHeight);
        }

        return currentWidth == width && currentHeight == height ? current : resize(current, width, height);
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        return target;
    }

    private static String pickFamily(String... candidates) {
        Set<String> available;
        try {
            available = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        } catch (RuntimeException | Error ex) {
            return candidates[candidates.length - 1];
        }

        for (String candidate : candidates) {
            if (available.contains(candidate)) {
                return candidate;
            }
        }

        return candidates[candidates.length - 1];
    }
}
