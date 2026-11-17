package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Experimental;

/**
 * {@code item_properties.mining_speed}: a wire-only float Bedrock Dedicated Server always sends (default 1.0) but add-on
 * JSON cannot set. Not an add-on component; the name is the wire path.
 *
 * @param value the mining speed sent to the client
 * @since 1.0.0
 */
@Experimental
public record MiningSpeed(float value) implements WireOnlyItemComponent {
    /**
     * The wire path (there is no add-on JSON name).
     */
    public static final String NAME = "item_properties.mining_speed";

    public MiningSpeed {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("MiningSpeed.value must be finite: " + value);
        }
    }

    public static @NonNull MiningSpeed of(float value) {
        return new MiningSpeed(value);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }
}
