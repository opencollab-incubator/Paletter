package dev.opencollab.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.annotation.Experimental;

/**
 * {@code item_properties.frame_count}: a wire-only int Bedrock Dedicated Server always sends (default 1) but add-on
 * JSON cannot set; clients use it for the item's use animation frames (e.g. a bow's draw stages). Not an add-on
 * component; the name is the wire path.
 *
 * @since 1.0.0
 */
@Experimental
public final class FrameCount implements WireOnlyItemComponent {
    /**
     * The wire path (there is no add-on JSON name).
     *
     * @since 1.0.0
     */
    public static final String NAME = "item_properties.frame_count";

    private final int value;

    private FrameCount(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("FrameCount.value must be at least 1: " + value);
        }
        this.value = value;
    }

    /**
     * Returns the frame count.
     *
     * @return the frame count sent to the client, at least 1
     * @since 1.0.0
     */
    public int value() {
        return value;
    }

    /**
     * Creates a frame count.
     *
     * @param value the frame count sent to the client, at least 1
     * @return the component
     * @throws IllegalArgumentException if the value is below 1
     * @since 1.0.0
     */
    public static @NonNull FrameCount of(int value) {
        return new FrameCount(value);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof FrameCount other && value == other.value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public String toString() {
        return "FrameCount[value=" + value + "]";
    }
}
