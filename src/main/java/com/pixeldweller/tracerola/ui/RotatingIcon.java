package com.pixeldweller.tracerola.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * Wraps a delegate {@link Icon} and paints it rotated by the current
 * {@link #angle} (in radians).  Call {@link #nextStep()} to advance the
 * rotation by 90° — designed to be driven by a {@link javax.swing.Timer}.
 */
public final class RotatingIcon implements Icon {

    private static final double STEP = Math.PI / 2; // 90°

    private final Icon delegate;
    private double angle;

    public RotatingIcon(Icon delegate) {
        this.delegate = delegate;
    }

    /** Advance the rotation by 90°. */
    public void nextStep() {
        angle += STEP;
        if (angle >= 2 * Math.PI) {
            angle -= 2 * Math.PI;
        }
    }

    public void reset() {
        angle = 0;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int cx = x + getIconWidth() / 2;
            int cy = y + getIconHeight() / 2;
            g2.transform(AffineTransform.getRotateInstance(angle, cx, cy));
            delegate.paintIcon(c, g2, x, y);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return delegate.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return delegate.getIconHeight();
    }
}
