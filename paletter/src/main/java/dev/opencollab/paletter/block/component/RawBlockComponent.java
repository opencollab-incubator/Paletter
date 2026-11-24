package dev.opencollab.paletter.block.component;

import dev.opencollab.paletter.annotation.Experimental;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Objects;

/**
 * An escape hatch: NBT written as is into the block's component data, merged over what the other components of the
 * block wrote, for a wire field Paletter does not model (yet) or models with a fixed
 * value (e.g. {@code of("minecraft:collision_box", NbtMap.builder().putString("shape", "example:post").putByte("useVoxelShapes", (byte) 1).build())}: from 26.70 Paletter sends "" and 0 there, as BDS does for the box forms). Nothing
 * checks it against a version: every palette sends it as given, and a value a client does not expect is the caller's problem.
 * <p>
 * The {@link #key()} is a path in the block's (or permutation's) {@code components} compound ({@code .} separates
 * compounds); the {@link #value()} is merged into the compound at that path: a compound into a compound key by key, any other value replacing what is there. Raw
 * components are written after every other component of the same block or permutation, whatever their order, so they win. Two raw
 * components of the same key replace each other, like other components of the same {@link #name()}.
 *
 * @since 1.0.0
 */
@Experimental
public final class RawBlockComponent implements WireOnlyBlockComponent {
    /**
     * The prefix of {@link #name()}: a raw component is named after its key, apart from the component of that name.
     *
     * @since 1.0.0
     */
    public static final String NAME_PREFIX = "raw:";

    private final String key;
    private final NbtMap value;

    private RawBlockComponent(String key, NbtMap value) {
        this.key = Objects.requireNonNull(key, "RawBlockComponent.key");
        this.value = Objects.requireNonNull(value, "RawBlockComponent.value");
        if (key.isEmpty() || key.startsWith(".") || key.endsWith(".") || key.contains("..")) {
            throw new IllegalArgumentException("RawBlockComponent.key must be a path of compound names, was '" + key + "'");
        }
    }

    /**
     * Creates a raw component.
     *
     * @param key the path of the compound in the block's (or permutation's) {@code components} compound, e.g. {@code minecraft:collision_box}
     * @param value the NBT to merge into the compound at that path
     * @return the component
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the key is empty or has an empty part
     * @since 1.0.0
     */
    public static @NonNull RawBlockComponent of(@NonNull String key, @NonNull NbtMap value) {
        return new RawBlockComponent(key, value);
    }

    /**
     * Returns the key.
     *
     * @return the path of the compound the value is merged into
     * @since 1.0.0
     */
    public @NonNull String key() {
        return key;
    }

    /**
     * Returns the value.
     *
     * @return the NBT merged into the compound at {@link #key()}
     * @since 1.0.0
     */
    public @NonNull NbtMap value() {
        return value;
    }

    /**
     * Returns the name.
     *
     * @return {@link #NAME_PREFIX} and the key, so that it does not replace the component of that name
     */
    @Override
    public @NonNull String name() {
        return NAME_PREFIX + key;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof RawBlockComponent other && key.equals(other.key) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return "RawBlockComponent[key=" + key + ", value=" + value + "]";
    }
}
