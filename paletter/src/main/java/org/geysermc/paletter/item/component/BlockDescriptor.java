package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.geysermc.paletter.vanilla.BedrockBlockType;

import java.util.Objects;

/**
 * Selects blocks in item components: one block type, or every block matching a Molang tag query.
 *
 * @since 2.0.0
 */
public final class BlockDescriptor {
    /**
     * Matches every block.
     */
    public static final BlockDescriptor ANY = new BlockDescriptor(null, "1");

    private final @Nullable String block;
    private final @Nullable String tags;

    private BlockDescriptor(@Nullable String block, @Nullable String tags) {
        this.block = block;
        this.tags = tags;
    }

    /**
     * @param identifier a block identifier, e.g. {@code minecraft:stone}
     */
    public static @NonNull BlockDescriptor block(@NonNull String identifier) {
        return new BlockDescriptor(Objects.requireNonNull(identifier, "identifier"), null);
    }

    /**
     * @param type a block type, e.g. {@code BedrockBlocks.STONE}
     * @since 2.0.0
     */
    public static @NonNull BlockDescriptor block(@NonNull BedrockBlockType type) {
        return block(type.identifier());
    }

    /**
     * @param molang a Molang tag query, e.g. {@code q.any_tag('stone', 'metal')}
     */
    public static @NonNull BlockDescriptor tags(@NonNull String molang) {
        return new BlockDescriptor(null, Objects.requireNonNull(molang, "molang"));
    }

    /**
     * @return the block identifier, or null if this descriptor is a tag query
     */
    public @Nullable String block() {
        return block;
    }

    /**
     * @return the Molang tag query, or null if this descriptor names a block
     */
    public @Nullable String tags() {
        return tags;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockDescriptor other && Objects.equals(block, other.block) && Objects.equals(tags, other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(block, tags);
    }

    @Override
    public String toString() {
        return block != null ? "BlockDescriptor[" + block + "]" : "BlockDescriptor[tags " + tags + "]";
    }
}
