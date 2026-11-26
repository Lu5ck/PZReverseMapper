package pzreversemapper.studio;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JTextField;

/** A text field that shows grey placeholder text while it is empty, like WinForms PlaceholderText. */
final class HintTextField extends JTextField {
    private static final long serialVersionUID = 1L;
    private static final int MIN_HEIGHT = 28;
    private String hint = "";

    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension size = super.getPreferredSize();
        return new java.awt.Dimension(size.width, Math.max(MIN_HEIGHT, size.height));
    }

    void setHint(String hint) {
        this.hint = hint == null ? "" : hint;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty() || hint.isEmpty()) {
            return;
        }

        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Theme.HINT);
            graphics.setFont(getFont());
            Insets insets = getInsets();
            var metrics = graphics.getFontMetrics();
            int baseline = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(hint, insets.left + 2, baseline);
        } finally {
            graphics.dispose();
        }
    }
}
