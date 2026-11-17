package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
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
 * @param variant the exact replacement variant, or null to keep the aux of the replaced item
 * @param force true to replace the item even though it exists in this version, e.g. because it is experimental there
 * @since 0.1.0
 */
public record ItemFallback(@NonNull BedrockItemType target, @Nullable BedrockItemVariant variant, boolean force) {
    /**
     * The default for items a version lacks: the {@code minecraft:unknown} item (the block item of
     * {@code minecraft:unknown}).
     *
     * @since 0.1.0
     */
    public static final ItemFallback UNKNOWN = new ItemFallback(BedrockItems.UNKNOWN, null, false);

    public ItemFallback {
        Objects.requireNonNull(target);
        if (variant != null && !variant.type().equals(target)) {
            throw new IllegalArgumentException("The variant " + variant + " is not of " + target);
        }
    }

    /**
     * @return a fallback to an item type, keeping the aux of the replaced item
     * @since 0.1.0
     */
    public static @NonNull ItemFallback keepingAux(@NonNull BedrockItemType target) {
        return new ItemFallback(target, null, false);
    }

    /**
     * @return a fallback to exactly this variant, type and aux (a type is its base variant, aux 0; see
     *         {@link #keepingAux} to keep the replaced item's aux)
     * @since 0.1.0
     */
    public static @NonNull ItemFallback of(@NonNull ItemKey target) {
        BedrockItemVariant variant = target.variant();
        return new ItemFallback(variant.type(), variant, false);
    }

    /**
     * @return this fallback, used even in versions that have the item
     * @since 0.1.0
     */
    public @NonNull ItemFallback forced() {
        return force ? this : new ItemFallback(target, variant, true);
    }
}
