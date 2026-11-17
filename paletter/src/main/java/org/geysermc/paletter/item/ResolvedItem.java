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
     * The variant this palette sends, as a key: the resolved variant itself, or if {@link #fallback()} its replacement
     * (of an {@code ItemFallback}, also a forced one, or the default fallback {@code minecraft:unknown} for a variant
     * the version lacks). A replacement has the aux of the fallback's variant, else the replaced variant's.
     *
     * @return the variant sent, never null
     * @since 0.1.0
     */
    public @NonNull BedrockItemVariant sent() {
        return definition.key().variantOf(damage);
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
