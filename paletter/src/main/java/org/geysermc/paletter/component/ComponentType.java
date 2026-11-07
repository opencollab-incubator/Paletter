package org.geysermc.paletter.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.function.Function;

/**
 * Describes a generated component type: its network name, how well it is verified, and how to read it.
 *
 * @param name the component name on the network, which is also its add-on JSON name
 * @param kind whether it is an item or a block component
 * @param confidence what its shape was inferred from
 * @param since the minimum add-on format version that accepts the component, if known
 * @param experimental whether add-ons need an experimental toggle for the component
 * @param reader reads the network NBT
 * @param <T> the component type
 */
public record ComponentType<T extends BedrockComponent>(@NonNull String name, @NonNull Kind kind,
                                                        @NonNull Confidence confidence, @Nullable String since,
                                                        boolean experimental, @NonNull Function<NbtMap, T> reader) {
    public enum Kind {
        ITEM, BLOCK
    }

    /**
     * Reads a component from network NBT. Reading is lenient: numbers of another width are converted and missing
     * fields take their defaults, so NBT written by older encoders can be read and re-encoded in the right shape.
     */
    public @NonNull T read(@NonNull NbtMap nbt) {
        return reader.apply(nbt);
    }

    @Override
    public String toString() {
        return "ComponentType[" + name + ", " + kind + ", " + confidence + "]";
    }
}
