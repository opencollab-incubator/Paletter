package dev.opencollab.paletter.item.component;

import dev.opencollab.paletter.annotation.Experimental;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Objects;

/**
 * An escape hatch: NBT written as is into the item's component data, merged over what the other components of the
 * item wrote, for a wire field Paletter does not model (yet) or models with a fixed
 * value (e.g. {@code of("minecraft:block_placer", NbtMap.builder().putByte("canUseBlockAsIcon", (byte) 0).build())}: Paletter sends 1, as BDS does). Nothing
 * checks it against a version: every palette sends it as given, and a value a client does not expect is the caller's problem.
 * <p>
 * The {@link #key()} is a path in the item's {@code components} compound ({@code .} separates
 * compounds); the {@link #value()} is merged into the compound at that path: a compound into a compound key by key, any other value replacing what is there. Raw
 * components are written after every other component of the same item, whatever their order, so they win. Two raw
 * components of the same key replace each other, like other components of the same {@link #name()}.
 *
 * @since 1.0.0
 */
@Experimental
public final class RawItemComponent implements WireOnlyItemComponent {
    /**
     * The prefix of {@link #name()}: a raw component is named after its key, apart from the component of that name.
     *
     * @since 1.0.0
     */
    public static final String NAME_PREFIX = "raw:";

    private final String key;
    private final NbtMap value;

    private RawItemComponent(String key, NbtMap value) {
        this.key = Objects.requireNonNull(key, "RawItemComponent.key");
        this.value = Objects.requireNonNull(value, "RawItemComponent.value");
        if (key.isEmpty() || key.startsWith(".") || key.endsWith(".") || key.contains("..")) {
            throw new IllegalArgumentException("RawItemComponent.key must be a path of compound names, was '" + key + "'");
        }
    }

    /**
     * Creates a raw component.
     *
     * @param key the path of the compound in the item's {@code components} compound, e.g. {@code minecraft:block_placer}
     * @param value the NBT to merge into the compound at that path
     * @return the component
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the key is empty or has an empty part
     * @since 1.0.0
     */
    public static @NonNull RawItemComponent of(@NonNull String key, @NonNull NbtMap value) {
        return new RawItemComponent(key, value);
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
        return this == o || o instanceof RawItemComponent other && key.equals(other.key) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return "RawItemComponent[key=" + key + ", value=" + value + "]";
    }
}
