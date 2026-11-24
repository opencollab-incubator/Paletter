package dev.opencollab.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.annotation.Experimental;

/**
 * {@code item_properties.mining_speed}: a wire-only float Bedrock Dedicated Server always sends (default 1.0) but add-on
 * JSON cannot set. Not an add-on component; the name is the wire path.
 *
 * @since 1.0.0
 */
@Experimental
public final class MiningSpeed implements WireOnlyItemComponent {
    /**
     * The wire path (there is no add-on JSON name).
     *
     * @since 1.0.0
     */
    public static final String NAME = "item_properties.mining_speed";

    private final float value;

    private MiningSpeed(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("MiningSpeed.value must be finite: " + value);
        }
        this.value = value;
    }

    /**
     * Returns the mining speed.
     *
     * @return the mining speed sent to the client
     * @since 1.0.0
     */
    public float value() {
        return value;
    }

    /**
     * Creates a mining speed.
     *
     * @param value the mining speed sent to the client, finite
     * @return the component
     * @throws IllegalArgumentException if the value is not finite
     * @since 1.0.0
     */
    public static @NonNull MiningSpeed of(float value) {
        return new MiningSpeed(value);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof MiningSpeed other && Float.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Float.hashCode(value);
    }

    @Override
    public String toString() {
        return "MiningSpeed[value=" + value + "]";
    }
}
