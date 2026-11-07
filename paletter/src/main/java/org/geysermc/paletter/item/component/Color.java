package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An RGB colour, e.g. the default colour of a dyeable item.
 *
 * @param red 0 to 255
 * @param green 0 to 255
 * @param blue 0 to 255
 * @since 2.0.0
 */
public record Color(int red, int green, int blue) {
    public static final Color WHITE = new Color(255, 255, 255);

    public Color {
        if ((red | green | blue) >>> 8 != 0) {
            throw new IllegalArgumentException("Colour components must be 0 to 255: " + red + ", " + green + ", " + blue);
        }
    }

    public static @NonNull Color rgb(int red, int green, int blue) {
        return new Color(red, green, blue);
    }

    /**
     * @param rgb {@code 0xRRGGBB}; higher bits are ignored
     */
    public static @NonNull Color of(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /**
     * @return {@code 0xRRGGBB}
     */
    public int rgb() {
        return red << 16 | green << 8 | blue;
    }
}
