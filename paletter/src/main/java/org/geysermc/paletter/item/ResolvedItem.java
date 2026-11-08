package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.block.PaletteBlock;

/**
 * An {@link ItemVariant} resolved against one palette: what to send to the client.
 *
 * @param definition the item definition
 * @param damage the damage value to send
 * @param block the block to send, or null
 * @param fallback true if the variant does not exist in this version and a replacement was used
 * @since 1.0.0
 */
public record ResolvedItem(@NonNull PaletteItem definition, int damage, @Nullable PaletteBlock block, boolean fallback) {

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
