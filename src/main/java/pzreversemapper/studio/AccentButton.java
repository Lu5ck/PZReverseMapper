package pzreversemapper.studio;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;

/** Flat, filled primary-action button that looks the same under every look and feel. */
final class AccentButton extends JButton {
    private static final long serialVersionUID = 1L;
    private final Color fill;

    AccentButton(String text, Color fill) {
        super(text);
        this.fill = fill;
        setForeground(Color.WHITE);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = fill;
            if (!isEnabled()) {
                color = new Color(158, 176, 199);
            } else if (getModel().isPressed()) {
                color = fill.darker();
            } else if (getModel().isRollover()) {
                color = new Color(
                    Math.min(255, fill.getRed() + 18),
                    Math.min(255, fill.getGreen() + 18),
                    Math.min(255, fill.getBlue() + 18));
            }

            graphics.setColor(color);
            graphics.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
        } finally {
            graphics.dispose();
        }

        super.paintComponent(g);
    }
}
