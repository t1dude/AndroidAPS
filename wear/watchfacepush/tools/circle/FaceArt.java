import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the glucose circle face's background, and a preview of the whole face for the face pickers.
 * Geometry must match gen_watchface_xml.py (450 px face, circle slot 236 px centred at 225, 168).
 *
 * Usage: java FaceArt.java <outDir>. It writes background.png and preview.png; see README.md for
 * where they go.
 */
public class FaceArt {

    static final int S = 450;
    static final double C = 225;
    // Glucose circle: slot 236 px at (107, 50), so centre (225, 168).
    static final double GX = 225, GY = 168, SLOT = 236, K = SLOT / 270.0;
    static final double RING_R = 106 * K, RING_W = 14 * K;
    static final Color ACCENT = new Color(0xA9, 0xB4, 0xFF);
    // Dials, as in gen_watchface_xml.py. Their contents were laid out for 84 px; DK scales them.
    static final double DIAL = 108, DK = DIAL / 84, DIAL_LEFT_X = 92, DIAL_RIGHT_X = 358, DIAL_Y = 280;

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        BufferedImage bg = image();
        Graphics2D g = graphics(bg);
        drawBackground(g);
        g.dispose();
        ImageIO.write(bg, "png", new File(out, "background.png"));

        BufferedImage preview = image();
        g = graphics(preview);
        drawBackground(g);
        drawPreviewContent(g);
        g.dispose();
        ImageIO.write(preview, "png", new File(out, "preview.png"));
    }

    static BufferedImage image() {
        return new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** Point at [deg] degrees clockwise from 12 o'clock. */
    static Point2D.Double polar(double cx, double cy, double r, double deg) {
        double a = Math.toRadians(deg);
        return new Point2D.Double(cx + r * Math.sin(a), cy - r * Math.cos(a));
    }

    static void drawBackground(Graphics2D g) {
        Shape face = new Ellipse2D.Double(0, 0, S, S);
        g.setPaint(new RadialGradientPaint(
            new Point2D.Double(C, 190), 285f,
            new float[] { 0f, 0.6f, 1f },
            new Color[] { new Color(0x33, 0x40, 0x63), new Color(0x17, 0x1d, 0x2e), new Color(0x08, 0x0a, 0x10) },
            MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(face);

        // Sunburst from the glucose circle's centre, kept inside the edge band.
        Shape oldClip = g.getClip();
        g.setClip(new Ellipse2D.Double(C - 199, C - 199, 398, 398));
        g.setColor(new Color(255, 255, 255, 34));
        g.setStroke(new BasicStroke(1.2f));
        double start = RING_R + RING_W / 2 + 16 * K;
        for (int a = 0; a < 360; a += 3) {
            Point2D.Double p0 = polar(GX, GY, start, a);
            Point2D.Double p1 = polar(GX, GY, 420, a);
            g.draw(new Line2D.Double(p0, p1));
        }
        g.setClip(oldClip);

        // A dark plate under the circle, so the sunburst does not run into the ring.
        double plate = RING_R + RING_W / 2 + 4 * K;
        g.setColor(new Color(7, 9, 13, 140));
        g.fill(new Ellipse2D.Double(GX - plate, GY - plate, plate * 2, plate * 2));

        // Minute ticks on the edge.
        for (int i = 0; i < 60; i++) {
            boolean major = i % 5 == 0;
            g.setColor(major ? new Color(0x8a, 0x92, 0xa3) : new Color(0x4a, 0x50, 0x5d));
            g.setStroke(new BasicStroke(major ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(polar(C, C, major ? 211 : 216, i * 6), polar(C, C, 221, i * 6)));
        }
    }

    // ------------------------------------------------------------------ preview only

    static void drawPreviewContent(Graphics2D g) {
        // Edge arcs: watch battery, steps (text only), reservoir, rig battery.
        edgeArc(g, 296, 340, 0.78, "78%", false);
        edgeArc(g, 20, 64, 0.64, "6412", false);
        edgeArc(g, 130, 166, 0.47, "142U", true);
        edgeArc(g, 194, 230, 0.54, "54%", true);

        // Glucose circle, in range, flat.
        Color bg = new Color(0x5B, 0xD6, 0x8A);
        g.setStroke(new BasicStroke((float) RING_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 77));
        g.draw(new Ellipse2D.Double(GX - RING_R, GY - RING_R, RING_R * 2, RING_R * 2));
        g.setColor(bg);
        g.draw(new Arc2D.Double(GX - RING_R, GY - RING_R, RING_R * 2, RING_R * 2, -20, 40, Arc2D.OPEN));
        Point2D.Double tip = polar(GX, GY, RING_R + RING_W / 2 + 15 * K, 90);
        Point2D.Double b1 = polar(GX, GY, RING_R + RING_W / 2 + 1 * K, 82);
        Point2D.Double b2 = polar(GX, GY, RING_R + RING_W / 2 + 1 * K, 98);
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(tip.x, tip.y);
        arrow.lineTo(b1.x, b1.y);
        arrow.lineTo(b2.x, b2.y);
        arrow.closePath();
        g.fill(arrow);
        text(g, "7.2", GX, GY + 14 * K, 76 * K, Font.BOLD, bg);
        text(g, "+0.1", GX, GY + 46 * K, 24 * K, Font.BOLD, new Color(0xca, 0xc4, 0xd0));
        text(g, "2 min ago", GX, GY + 72 * K, 20 * K, Font.PLAIN, new Color(0xca, 0xc4, 0xd0));

        // Dials: weather on the left, IOB on the right.
        dial(g, DIAL_LEFT_X, DIAL_Y);
        Point2D.Double sun = new Point2D.Double(DIAL_LEFT_X, DIAL_Y - 12 * DK);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double sunR = 5.5 * DK;
        g.draw(new Ellipse2D.Double(sun.x - sunR, sun.y - sunR, 2 * sunR, 2 * sunR));
        for (int a = 0; a < 360; a += 45) g.draw(new Line2D.Double(polar(sun.x, sun.y, 8.5 * DK, a), polar(sun.x, sun.y, 11 * DK, a)));
        text(g, "12°", DIAL_LEFT_X, DIAL_Y + 19 * DK, 23 * DK, Font.BOLD, new Color(0xf3, 0xf5, 0xfa));
        dial(g, DIAL_RIGHT_X, DIAL_Y);
        text(g, "IOB", DIAL_RIGHT_X, DIAL_Y - 8 * DK, 11 * DK, Font.BOLD, ACCENT);
        text(g, "1.25U", DIAL_RIGHT_X, DIAL_Y + 14 * DK, 21 * DK, Font.BOLD, new Color(0xf3, 0xf5, 0xfa));

        // Time and date.
        text(g, "10:08", C, 364, 64, Font.PLAIN, new Color(0xf3, 0xf5, 0xfa));
        text(g, "Tue 7 Oct", C, 392, 17, Font.BOLD, new Color(0x9a, 0xa3, 0xb5));
    }

    static void dial(Graphics2D g, double x, double y) {
        g.setColor(new Color(0x10, 0x14, 0x1c));
        double half = DIAL / 2.0;
        g.fill(new Ellipse2D.Double(x - half, y - half, DIAL, DIAL));
        g.setColor(new Color(255, 255, 255, 31));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Double(x - half, y - half, DIAL, DIAL));
        g.setColor(new Color(255, 255, 255, 23));
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double r = 35 * DK;
        // Track: 280 degrees, open at the bottom. Arc2D angles run counter-clockwise from 3 o'clock.
        g.draw(new Arc2D.Double(x - r, y - r, 2 * r, 2 * r, -50, 280, Arc2D.OPEN));
    }

    static void edgeArc(Graphics2D g, double a0, double a1, double v, String label, boolean bottom) {
        double r = 207;
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 26));
        g.draw(clockArc(r, a0, a1));
        g.setColor(ACCENT);
        g.draw(clockArc(r, a0, a0 + (a1 - a0) * v));
        edgeText(g, a0, a1, label, bottom);
    }

    /** An arc from [a0] to [a1], both clockwise from 12 o'clock, as Java2D wants it. */
    static Shape clockArc(double r, double a0, double a1) {
        return new Arc2D.Double(C - r, C - r, 2 * r, 2 * r, 90 - a0, -(a1 - a0), Arc2D.OPEN);
    }

    /**
     * Text along the edge, upright for the reader in the top and bottom half. Like the face, it starts
     * at the left end of the span, after the room the face keeps for an icon.
     */
    static void edgeText(Graphics2D g, double a0, double a1, String label, boolean bottom) {
        Font font = new Font("SansSerif", Font.BOLD, 15);
        g.setFont(font);
        g.setColor(new Color(0xc6, 0xcd, 0xdd));
        FontMetrics fm = g.getFontMetrics();
        double r = bottom ? 186 : 191;
        // Angle per pixel along the radius.
        double degPerPx = Math.toDegrees(1.0 / r);
        double angle = bottom ? a1 - 8 : a0 + 8;
        for (char ch : label.toCharArray()) {
            String s = String.valueOf(ch);
            double w = fm.stringWidth(s);
            double centre = bottom ? angle - w / 2 * degPerPx : angle + w / 2 * degPerPx;
            Point2D.Double p = polar(C, C, r, centre);
            AffineTransform old = g.getTransform();
            g.translate(p.x, p.y);
            g.rotate(Math.toRadians(bottom ? centre + 180 : centre));
            g.drawString(s, (float) (-w / 2), bottom ? (float) (fm.getAscent() * 0.75) : 0f);
            g.setTransform(old);
            angle = bottom ? angle - w * degPerPx : angle + w * degPerPx;
        }
    }

    static void text(Graphics2D g, String s, double cx, double baseline, double size, int style, Color color) {
        g.setFont(new Font("SansSerif", style, (int) Math.round(size)));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseline);
    }
}
