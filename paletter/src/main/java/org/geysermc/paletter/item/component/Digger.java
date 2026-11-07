package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How fast an item mines blocks (add-on JSON {@code minecraft:digger}).
 * <pre>{@code
 * // A tool the client cannot mine anything with, e.g. because the server computes break times itself
 * Digger.of().speed(BlockDescriptor.ANY, 0)
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Digger {
    private final List<DestroySpeed> destroySpeeds;
    private final boolean useEfficiency;

    private Digger(List<DestroySpeed> destroySpeeds, boolean useEfficiency) {
        this.destroySpeeds = List.copyOf(destroySpeeds);
        this.useEfficiency = useEfficiency;
    }

    /**
     * @return a digger without destroy speeds that ignores the efficiency enchantment
     */
    public static @NonNull Digger of() {
        return new Digger(List.of(), false);
    }

    /**
     * @return a copy that also mines the given blocks at this speed
     */
    public @NonNull Digger speed(@NonNull BlockDescriptor blocks, int speed) {
        List<DestroySpeed> all = new ArrayList<>(destroySpeeds);
        all.add(new DestroySpeed(blocks, speed));
        return new Digger(all, useEfficiency);
    }

    /**
     * @param useEfficiency whether the efficiency enchantment speeds mining up
     */
    public @NonNull Digger useEfficiency(boolean useEfficiency) {
        return new Digger(destroySpeeds, useEfficiency);
    }

    public @NonNull List<DestroySpeed> destroySpeeds() {
        return destroySpeeds;
    }

    public boolean useEfficiency() {
        return useEfficiency;
    }

    /**
     * @param blocks the blocks
     * @param speed the mining speed for them
     * @since 2.0.0
     */
    public record DestroySpeed(@NonNull BlockDescriptor blocks, int speed) {
        public DestroySpeed {
            Objects.requireNonNull(blocks, "blocks");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Digger other && destroySpeeds.equals(other.destroySpeeds) && useEfficiency == other.useEfficiency;
    }

    @Override
    public int hashCode() {
        return destroySpeeds.hashCode() * 31 + Boolean.hashCode(useEfficiency);
    }

    @Override
    public String toString() {
        return "Digger[" + destroySpeeds + ", efficiency " + useEfficiency + "]";
    }
}
