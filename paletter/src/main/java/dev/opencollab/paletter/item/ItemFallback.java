package dev.opencollab.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;

import java.util.Objects;

/**
 * A replacement for an item in a version: an exact variant. The target is a key: it needs no existence in any version,
 * custom keys are made with {@link dev.opencollab.paletter.Palette#itemType}. Made with {@link #of(BedrockItemVariant)} and
 * {@link #forced()}; a value with {@code equals}, {@code hashCode} and {@code toString}.
 *
 * @since 1.0.0
 */
public final class ItemFallback {
    /**
     * The default for items a version lacks: the {@code minecraft:unknown} item (the block item of
     * {@code minecraft:unknown}).
     *
     * @since 1.0.0
     */
    public static final ItemFallback UNKNOWN = of(BedrockItems.UNKNOWN.variant());

    private final BedrockItemVariant variant;
    private final boolean force;

    private ItemFallback(BedrockItemVariant variant, boolean force) {
        this.variant = Objects.requireNonNull(variant, "variant");
        this.force = force;
    }

    /**
     * Creates a fallback that is not forced.
     *
     * @param target the replacement: a variant
     * @return a fallback to exactly this variant
     * @throws NullPointerException if the target is null
     * @since 1.0.0
     */
    public static @NonNull ItemFallback of(@NonNull BedrockItemVariant target) {
        return new ItemFallback(Objects.requireNonNull(target, "target"), false);
    }

    /**
     * Returns this fallback, forced.
     *
     * @return this fallback, used even in versions that have the item, e.g. because it is experimental there
     * @since 1.0.0
     */
    public @NonNull ItemFallback forced() {
        return force ? this : new ItemFallback(variant, true);
    }

    /**
     * The replacement item type.
     *
     * @return the type of {@link #variant()}
     * @since 1.0.0
     */
    public @NonNull BedrockItemType target() {
        return variant.type();
    }

    /**
     * The replacement variant.
     *
     * @return the exact replacement variant
     * @since 1.0.0
     */
    public @NonNull BedrockItemVariant variant() {
        return variant;
    }

    /**
     * Whether the fallback is forced.
     *
     * @return true to replace the item even though it exists in this version
     * @since 1.0.0
     */
    public boolean force() {
        return force;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ItemFallback other && force == other.force && variant.equals(other.variant);
    }

    @Override
    public int hashCode() {
        return 31 * variant.hashCode() + (force ? 1 : 0);
    }

    @Override
    public String toString() {
        return "ItemFallback{" + variant + (force ? ", forced" : "") + "}";
    }
}
