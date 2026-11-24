package dev.opencollab.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.ResolutionKind;
import dev.opencollab.paletter.behaviour.ItemValues;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.Objects;

/**
 * An item variant resolved against one palette: what to send to the client, see
 * {@link dev.opencollab.paletter.Palette#resolve(dev.opencollab.paletter.vanilla.ItemKey)}. Resolutions are made by the
 * palette, kept, and shared between a palette and the palettes extending it. Resolutions are equal if they send the same
 * definition, aux and block, resolved the same way, with the same behaviour handle; compare them with {@code equals}.
 *
 * @since 1.0.0
 */
public final class ResolvedItem {
    private final RegisteredItem definition;
    private final int aux;
    private final @Nullable RegisteredBlockState block;
    private final ResolutionKind kind;
    private final ItemRegistry registry;

    ResolvedItem(@NonNull RegisteredItem definition, int aux, @Nullable RegisteredBlockState block, @NonNull ResolutionKind kind,
                 @NonNull ItemRegistry registry) {
        this.definition = definition;
        this.aux = aux;
        this.block = block;
        this.kind = Objects.requireNonNull(kind);
        this.registry = registry;
    }

    /**
     * The item definition this resolution sends.
     *
     * @return the item definition
     */
    public @NonNull RegisteredItem definition() {
        return definition;
    }

    /**
     * The aux value to send.
     *
     * @return the aux value to send (the {@code damage} of the {@code ItemData})
     */
    public int aux() {
        return aux;
    }

    /**
     * The block the item is sent with.
     *
     * @return the block to send, or null
     */
    public @Nullable RegisteredBlockState block() {
        return block;
    }

    /**
     * How the palette resolved the variant for its version.
     *
     * @return {@link ResolutionKind#EXACT}, {@link ResolutionKind#FALLBACK} (an {@code ItemFallback}, also a forced one)
     *         or {@link ResolutionKind#DEFAULT} (the default fallback item); never {@code ADAPTED} or {@code MISSING}
     * @since 1.0.0
     */
    public @NonNull ResolutionKind kind() {
        return kind;
    }

    /**
     * Whether a replacement was used.
     *
     * @return true if an {@code ItemFallback} or the default fallback item was used ({@link #kind()} is {@code FALLBACK}
     *         or {@code DEFAULT}): the variant has no item of its own in this version, or is forcibly replaced
     */
    public boolean fallback() {
        return kind.isReplacement();
    }

    /**
     * The behaviour values (max stack size, durability, attack damage, ...) of the item sent, in the palette that
     * resolved it: {@code palette.resolve(variant).behaviour().get(ItemBehaviour.MAX_STACK_SIZE)}. Values are per item
     * type, not per aux value or fallback: they are those of {@link #definition()}, and the handle is the same for the
     * same item in a palette, whichever variant resolved to it. A palette that extends another one shares the handles
     * of the items it did not change. A handle is made on each call: compare it with {@code equals}, not {@code ==}.
     *
     * @return the behaviour values of the item sent
     * @throws IllegalStateException if the definition is not an item of the palette, which a resolution never gives
     * @since 1.0.0
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
        return aux == other.aux && kind == other.kind && definition.equals(other.definition)
            && Objects.equals(block, other.block);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ResolvedItem other && sameAs(other)
            && (registry == other.registry || Objects.equals(registry.behaviour().item(definition), other.registry.behaviour().item(other.definition)));
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, aux, block, kind);
    }

    /**
     * The variant this palette sends for the requested one, as a key: the requested variant itself, or if {@link #fallback()} its replacement
     * (of an {@code ItemFallback}, also a forced one, or the default fallback {@code minecraft:unknown} for a variant
     * the version lacks). A replacement is exactly the fallback's variant.
     *
     * @return the variant this palette sends for the requested one, never null
     * @since 1.0.0
     */
    public @NonNull BedrockItemVariant variant() {
        return definition.type().variant(aux);
    }

    /**
     * Builds the client item data of one item of this resolution.
     *
     * @return the item data with definition, aux, block and a count of 1; {@code toBuilder()} changes the rest
     * @since 1.0.0
     */
    public @NonNull ItemData toItemData() {
        return toItemData(1);
    }

    /**
     * Builds the client item data of a stack of this resolution.
     *
     * @param count the stack size
     * @return the item data with definition, aux, block and the count; {@code toBuilder()} changes the rest
     * @since 1.0.0
     */
    public @NonNull ItemData toItemData(int count) {
        return ItemData.builder()
            .definition(definition)
            .damage(aux)
            .blockDefinition(block)
            .count(count)
            .build();
    }

    @Override
    public String toString() {
        return "ResolvedItem{" + definition + ", aux=" + aux + ", block=" + block + ", " + kind + '}';
    }
}
