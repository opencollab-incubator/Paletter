package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Experimental;

/**
 * {@code item_properties.frame_count}: a wire-only int Bedrock Dedicated Server always sends (default 1) but add-on
 * JSON cannot set; clients use it for the item's use animation frames (e.g. a bow's draw stages). Not an add-on
 * component; the name is the wire path.
 *
 * @param value the frame count sent to the client, at least 1
 * @since 1.0.0
 */
@Experimental
public record FrameCount(int value) implements WireOnlyItemComponent {
    /**
     * The wire path (there is no add-on JSON name).
     */
    public static final String NAME = "item_properties.frame_count";

    public FrameCount {
        if (value < 1) {
            throw new IllegalArgumentException("FrameCount.value must be at least 1: " + value);
        }
    }

    public static @NonNull FrameCount of(int value) {
        return new FrameCount(value);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }
}
