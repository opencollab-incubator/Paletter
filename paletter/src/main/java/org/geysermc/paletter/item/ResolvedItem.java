package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.ItemValues;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.Objects;

/**
 * An item variant resolved against one palette: what to send to the client, see
 * {@link org.geysermc.paletter.Palette#resolve(org.geysermc.paletter.vanilla.BedrockItemVariant)}.
 *
 * @param definition the item definition
 * @param damage the damage value to send
 * @param block the block to send, or null
 * @param fallback true if the variant does not exist in this version and a replacement was used
 * @param link plumbing of {@link #behaviour()}, not part of the API; {@code equals} ignores it
 * @since 0.1.0
 */
public record ResolvedItem(@NonNull RegisteredItem definition, int damage, @Nullable RegisteredBlockState block, boolean fallback,
                           @Internal @NonNull PaletteLink link) {

    /**
     * The behaviour values (max stack size, durability, attack damage, ...) of the item sent, from the palette that
     * resolved it: {@code palette.resolve(variant).behaviour().get(ItemBehaviour.MAX_STACK_SIZE)}. Values are per item
     * type, not per aux value or fallback: they are those of {@link #definition()}. A palette that extends another one
     * shares the resolutions of the items it did not change, and so their handles: they have the values of the palette
     * they were made for, which are the same in the extending one (a handle is equal only to the handle of its own
     * palette, compare values). A handle is made on each call, see {@link ItemValues}.
     *
     * @throws IllegalStateException if the definition is not an item of the palette, which a resolution never gives
     * @since 0.1.0
     */
    public @NonNull ItemValues behaviour() {
        ItemValues values = link.behaviour().item(definition);
        if (values == null) {
            throw new IllegalStateException(definition + " is not an item of the palette that resolved it");
        }
        return values;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ResolvedItem other && damage == other.damage && fallback == other.fallback
            && definition.equals(other.definition) && Objects.equals(block, other.block);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, damage, block, fallback);
    }

    /**
     * The variant this palette sends for the requested one, as a key: the requested variant itself, or if {@link #fallback()} its replacement
     * (of an {@code ItemFallback}, also a forced one, or the default fallback {@code minecraft:unknown} for a variant
     * the version lacks). A replacement has the aux of the fallback's variant, else the replaced variant's.
     *
     * @return the variant this palette sends for the requested one, never null
     * @since 0.1.0
     */
    public @NonNull BedrockItemVariant variant() {
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
