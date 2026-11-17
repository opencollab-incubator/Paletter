package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.behaviour.ItemValues;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.Objects;

/**
 * An item variant resolved against one palette: what to send to the client, see
 * {@link org.geysermc.paletter.Palette#resolve(org.geysermc.paletter.vanilla.BedrockItemVariant)}. Resolutions are
 * made by the palette, and shared between a palette and the palettes extending it: compare them by their parts, not
 * with {@code equals}.
 *
 * @since 0.1.0
 */
public final class ResolvedItem {
    private final RegisteredItem definition;
    private final int damage;
    private final @Nullable RegisteredBlockState block;
    private final boolean fallback;
    private final ItemRegistry registry;

    ResolvedItem(@NonNull RegisteredItem definition, int damage, @Nullable RegisteredBlockState block, boolean fallback,
                 @NonNull ItemRegistry registry) {
        this.definition = definition;
        this.damage = damage;
        this.block = block;
        this.fallback = fallback;
        this.registry = registry;
    }

    /**
     * @return the item definition
     */
    public @NonNull RegisteredItem definition() {
        return definition;
    }

    /**
     * @return the damage value to send
     */
    public int damage() {
        return damage;
    }

    /**
     * @return the block to send, or null
     */
    public @Nullable RegisteredBlockState block() {
        return block;
    }

    /**
     * @return true if the variant does not exist in this version and a replacement was used
     */
    public boolean fallback() {
        return fallback;
    }

    /**
     * The behaviour values (max stack size, durability, attack damage, ...) of the item sent, in the palette that
     * resolved it: {@code palette.resolve(variant).behaviour().get(ItemBehaviour.MAX_STACK_SIZE)}. Values are per item
     * type, not per aux value or fallback: they are those of {@link #definition()}, and the handle is the same for the
     * same item in a palette, whichever variant resolved to it. A palette that extends another one shares the handles
     * of the items it did not change. A handle is made on each call: compare it with {@code equals}, not {@code ==}.
     *
     * @throws IllegalStateException if the definition is not an item of the palette, which a resolution never gives
     * @since 0.1.0
     */
    public @NonNull ItemValues behaviour() {
        ItemValues values = registry.behaviour().item(definition);
        if (values == null) {
            throw new IllegalStateException(definition + " is not an item of the palette that resolved it");
        }
        return values;
    }

    /** @return whether another resolution has the same parts, whichever palette made it */
    boolean sameAs(ResolvedItem other) {
        return damage == other.damage && fallback == other.fallback && definition.equals(other.definition)
            && Objects.equals(block, other.block);
    }

    /**
     * The variant this palette sends for the requested one, as a key: the requested variant itself, or if {@link #fallback()} its replacement
     * (of an {@code ItemFallback}, also a forced one, or the default fallback {@code minecraft:unknown} for a variant
     * the version lacks). A replacement is exactly the fallback's variant.
     *
     * @return the variant this palette sends for the requested one, never null
     * @since 0.1.0
     */
    public @NonNull BedrockItemVariant variant() {
        return definition.key().variant(damage);
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

    @Override
    public String toString() {
        return "ResolvedItem{" + definition + ", damage=" + damage + ", block=" + block + ", fallback=" + fallback + '}';
    }
}
