package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

/**
 * An item variant resolved against one palette: what to send to the client, see
 * {@link org.geysermc.paletter.Palette#resolve(org.geysermc.paletter.vanilla.BedrockItemVariant)}.
 *
 * @param definition the item definition
 * @param damage the damage value to send
 * @param block the block to send, or null
 * @param fallback true if the variant does not exist in this version and a replacement was used
 * @since 0.1.0
 */
public record ResolvedItem(@NonNull RegisteredItem definition, int damage, @Nullable RegisteredBlockState block, boolean fallback) {

    /**
     * The variant this palette sends in place of the one that was resolved: the replacement of an
     * {@code ItemFallback} (also a forced one), or the default fallback ({@code minecraft:unknown}) for a variant the
     * version lacks. The aux is the one of the fallback's variant, else the replaced variant's. For a variant sent as
     * itself there is no replacement. (A variant nothing is sent for has no resolution at all, see
     * {@link org.geysermc.paletter.Palette#resolve}, so it never gets here.)
     *
     * @return the variant sent instead, or null if {@link #fallback()} is false
     * @since 0.1.0
     */
    public @Nullable BedrockItemVariant sent() {
        return fallback ? definition.key().variantOf(damage) : null;
    }

    /**
     * @return an item data builder with definition, damage, block and a count of 1 filled in
     */
    public ItemData.@NonNull Builder toItemData() {
        return ItemData.builder()
            .definition(definition)
            .damage(damage)
            .blockDefinition(block)
            .count(1);
    }
}
