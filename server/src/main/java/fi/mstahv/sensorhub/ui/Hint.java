package fi.mstahv.sensorhub.ui;

/**
 * A sentence that explains the control above it.
 *
 * <p>Shared rather than redeclared: four views had a private class of this name with
 * this body, which is the point where "named for what it is" turns into copies.
 */
class Hint extends SecondaryText {

    Hint(String text) {
        super(text);
    }
}
