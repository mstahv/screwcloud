import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

/**
 * Produces the ScrewCloud PWA icon.
 *
 * <pre>
 *   java tools/GenerateIcon.java
 * </pre>
 *
 * <p>Why this exists: Vaadin loads the PWA icon with {@link ImageIO}, so it has
 * to be a raster format — SVG will not do. The composition is the same as in
 * icons/screwcloud.svg, but the background is a filled tile, because a
 * transparent icon looks broken in an operating system's app launcher.
 *
 * <p>The SVG is the design source. If the logo changes, change both.
 */
public final class GenerateIcon {

    private static final int SIZE = 512;
    private static final double SCALE = SIZE / 64.0;

    private static final Color BACKGROUND = new Color(0x2B3A46);
    private static final Color CLOUD = new Color(0xE8EEF4);
    private static final Color SHAFT = new Color(0xE0A33E);
    private static final Color THREAD = new Color(0xA9741B);
    private static final Color HEAD = new Color(0xEFB65A);
    private static final Color SLOT = new Color(0x8C5E12);

    private GenerateIcon() {
    }

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BACKGROUND);
        g.fill(new RoundRectangle2D.Double(0, 0, SIZE, SIZE, SIZE * 0.22, SIZE * 0.22));

        // The same drawing order as in the SVG: shaft, cloud, head.
        drawScrewShaft(g);
        drawCloud(g);
        drawScrewHead(g);

        g.dispose();

        File target = new File("src/main/resources/META-INF/resources/icons/icon.png");
        Files.createDirectories(target.getParentFile().toPath());
        ImageIO.write(image, "png", target);
        System.out.println("Wrote " + target + " (" + SIZE + "x" + SIZE + ")");
    }

    /** The screw's local frame: head at the origin, tip pointing down, tilted 30°. */
    private static AffineTransform screwTransform() {
        AffineTransform transform = AffineTransform.getScaleInstance(SCALE, SCALE);
        transform.translate(48, 8);
        transform.rotate(Math.toRadians(30));
        return transform;
    }

    private static void drawScrewShaft(Graphics2D g) {
        AffineTransform screw = screwTransform();

        Path2D shaft = new Path2D.Double();
        shaft.moveTo(-4, 5);
        shaft.lineTo(4, 5);
        shaft.lineTo(2.6, 24);
        shaft.lineTo(0, 31);
        shaft.lineTo(-2.6, 24);
        shaft.closePath();
        g.setColor(SHAFT);
        g.fill(screw.createTransformedShape(shaft));

        g.setColor(THREAD);
        g.setStroke(new BasicStroke((float) (1.3 * SCALE), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double[][] threads = {{-3.8, 9, 3.8, 10}, {-3.6, 13, 3.6, 14}, {-3.3, 17, 3.3, 18}, {-3.0, 21, 3.0, 22}};
        for (double[] thread : threads) {
            Path2D line = new Path2D.Double();
            line.moveTo(thread[0], thread[1]);
            line.lineTo(thread[2], thread[3]);
            g.draw(screw.createTransformedShape(line));
        }
    }

    private static void drawCloud(Graphics2D g) {
        g.setColor(CLOUD);
        AffineTransform scale = AffineTransform.getScaleInstance(SCALE, SCALE);
        g.fill(scale.createTransformedShape(new Ellipse2D.Double(32 - 13, 34 - 13, 26, 26)));
        g.fill(scale.createTransformedShape(new Ellipse2D.Double(19 - 9, 40 - 9, 18, 18)));
        g.fill(scale.createTransformedShape(new Ellipse2D.Double(46 - 9.5, 39 - 9.5, 19, 19)));
        g.fill(scale.createTransformedShape(new RoundRectangle2D.Double(10, 39, 46, 12, 12, 12)));
    }

    private static void drawScrewHead(Graphics2D g) {
        AffineTransform screw = screwTransform();

        Ellipse2D head = new Ellipse2D.Double(-7.5, -7.5, 15, 15);
        g.setColor(HEAD);
        g.fill(screw.createTransformedShape(head));
        g.setColor(THREAD);
        g.setStroke(new BasicStroke((float) (1.4 * SCALE)));
        g.draw(screw.createTransformedShape(head));

        g.setColor(SLOT);
        g.fill(screw.createTransformedShape(new RoundRectangle2D.Double(-5, -1.4, 10, 2.8, 2.8, 2.8)));
    }
}
