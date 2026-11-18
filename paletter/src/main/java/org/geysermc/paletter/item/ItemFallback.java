package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.ItemKey;
import org.geysermc.paletter.vanilla.BedrockItems;

import java.util.Objects;

/**
 * A replacement for an item in a version. The target is a key: it needs no existence in any version, custom keys are
 * made with {@link org.geysermc.paletter.Palette#itemType}.
 *
 * @param target the replacement item type
 * @param variant the exact replacement variant
 * @param force true to replace the item even though it exists in this version, e.g. because it is experimental there
 * @since 1.0.0
 */
public record ItemFallback(@NonNull BedrockItemType target, @NonNull BedrockItemVariant variant, boolean force) {
    /**
     * The default for items a version lacks: the {@code minecraft:unknown} item (the block item of
     * {@code minecraft:unknown}).
     *
     * @since 1.0.0
     */
    public static final ItemFallback UNKNOWN = of(BedrockItems.UNKNOWN);

    /**
     * Creates a fallback.
     *
     * @throws NullPointerException if the target or the variant is null
     * @throws IllegalArgumentException if the variant is not of the target type
     */
    public ItemFallback {
        Objects.requireNonNull(target);
        Objects.requireNonNull(variant);
        if (!variant.type().equals(target)) {
            throw new IllegalArgumentException("The variant " + variant + " is not of " + target);
        }
    }

    /**
     * Creates a fallback that is not forced.
     *
     * @param target the replacement: a variant, or a type for its base variant
     * @return a fallback to exactly this variant (a type is its base variant)
     * @since 1.0.0
     */
    public static @NonNull ItemFallback of(@NonNull ItemKey target) {
        BedrockItemVariant variant = target.variant();
        return new ItemFallback(variant.type(), variant, false);
    }

    /**
     * Returns this fallback with {@code force} set.
     *
     * @return this fallback, used even in versions that have the item
     * @since 1.0.0
     */
    public @NonNull ItemFallback forced() {
        return force ? this : new ItemFallback(target, variant, true);
    }
}
