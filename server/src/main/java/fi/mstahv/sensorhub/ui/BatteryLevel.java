package fi.mstahv.sensorhub.ui;

import org.vaadin.firitin.components.VSvg;
import org.vaadin.firitin.element.svg.RectElement;

/**
 * A battery, drawn as a battery: an outline, a nub, and a bar filled to the
 * level. Inline, the height of the text it stands in, and in the text's colour
 * — until the level is low, when the bar turns red.
 *
 * <p>Generic on purpose. It knows a percentage and a threshold and nothing about
 * where either came from, so it can stand next to any number that is a
 * fraction of something: a tag's coin cell here, a phone's or a laptop's
 * somewhere else. The threshold is where the bar changes colour and defaults to
 * twenty, which is where most things that have batteries start to complain.
 *
 * <p>Drawn with Viritin's SVG elements rather than an icon font: an icon set has
 * four or five battery glyphs, and the point of this one is that the bar is
 * exactly as long as the level.
 */
public class BatteryLevel extends VSvg {

    /** The drawing's own coordinates; the element scales to whatever size it is given. */
    private static final int WIDTH = 32;
    private static final int HEIGHT = 16;

    /** The bar's box inside the outline. */
    private static final double BAR_X = 4;
    private static final double BAR_Y = 4;
    private static final double BAR_WIDTH = 20;
    private static final double BAR_HEIGHT = 8;

    private int percent;
    private int lowThreshold = 20;
    private String lowColor = "var(--aura-red, #e5484d)";

    public BatteryLevel() {
        this(100);
    }

    public BatteryLevel(int percent) {
        super(0, 0, WIDTH, HEIGHT);
        /*
           Two ems wide and one high, sized in ems so it follows the font it
           stands beside; and on the middle of the line rather than the baseline,
           where an SVG would otherwise sit like a subscript.
        */
        setWidth("2em");
        setHeight("1em");
        getStyle().set("vertical-align", "middle");
        getElement().setAttribute("role", "img");
        setPercent(percent);
    }

    /** Fills the bar to this much, clamped to 0..100, and redraws. */
    public void setPercent(int percent) {
        this.percent = Math.max(0, Math.min(100, percent));
        draw();
    }

    public int getPercent() {
        return percent;
    }

    /** Below this the bar is drawn in {@link #setLowColor the low colour}. Default 20. */
    public void setLowThreshold(int lowThreshold) {
        this.lowThreshold = lowThreshold;
        draw();
    }

    public int getLowThreshold() {
        return lowThreshold;
    }

    /**
     * The colour of a low bar, as CSS — a variable with a fallback is fine, which
     * is why this is a string and not a colour: the theme's red is the right red.
     */
    public void setLowColor(String lowColor) {
        this.lowColor = lowColor;
        draw();
    }

    public boolean isLow() {
        return percent < lowThreshold;
    }

    /*
       Redrawn whole on every change. Three rectangles are nothing to send, and
       the elements' attributes are write-only on the server side, so there is
       nothing to update in place anyway.
    */
    private void draw() {
        getElement().removeAllChildren();
        getElement().setAttribute("aria-label", percent + " %");

        RectElement outline = new RectElement()
                .x(1.5).y(1.5).width(25).height(13).rx(2.5)
                .noFill().stroke("currentColor").strokeWidth(1.5);
        RectElement nub = new RectElement()
                .x(27.5).y(5).width(3).height(6).rx(1)
                .fill("currentColor").noStroke();
        RectElement bar = new RectElement()
                .x(BAR_X).y(BAR_Y).width(BAR_WIDTH * percent / 100.0).height(BAR_HEIGHT).rx(1)
                .noStroke();
        if (isLow()) {
            // A style rather than the attribute, because an attribute cannot hold var().
            bar.getStyle().set("fill", lowColor);
        } else {
            bar.fill("currentColor");
        }

        getElement().appendChild(outline, nub, bar);
    }
}
