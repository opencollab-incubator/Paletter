package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What content a version lacks becomes: the one fallback policy of a {@link Paletter}. Entries apply to every version,
 * or to one data version only (those win). Content without an entry uses the default.
 * <p>
 * Paletter bundles no replacements: {@link #UNKNOWN} maps everything a version lacks to {@code minecraft:unknown},
 * {@link #NONE} sends air and leaves items out. Consumers add better replacements with {@link #builder()}.
 *
 * @since 1.0.0
 */
public final class Fallbacks {
    /**
     * Unknown for everything: {@link BlockFallback#UNKNOWN} and {@link ItemFallback#UNKNOWN}.
     */
    public static final Fallbacks UNKNOWN = new Builder().build();

    /**
     * No fallbacks at all: missing blocks are sent as air, missing items have no definition.
     */
    public static final Fallbacks NONE = new Builder().defaultItem(null).defaultBlock(null).build();

    private final Map<String, ItemFallback> items;
    private final Map<String, BlockFallback> blocks;
    private final Map<String, Map<String, ItemFallback>> versionItems;
    private final Map<String, Map<String, BlockFallback>> versionBlocks;
    private final @Nullable ItemFallback defaultItem;
    private final @Nullable BlockFallback defaultBlock;

    private Fallbacks(Builder builder) {
        this.items = Map.copyOf(builder.items);
        this.blocks = Map.copyOf(builder.blocks);
        Map<String, Map<String, ItemFallback>> versionItems = new LinkedHashMap<>();
        builder.versionItems.forEach((version, map) -> versionItems.put(version, Map.copyOf(map)));
        this.versionItems = Map.copyOf(versionItems);
        Map<String, Map<String, BlockFallback>> versionBlocks = new LinkedHashMap<>();
        builder.versionBlocks.forEach((version, map) -> versionBlocks.put(version, Map.copyOf(map)));
        this.versionBlocks = Map.copyOf(versionBlocks);
        this.defaultItem = builder.defaultItem;
        this.defaultBlock = builder.defaultBlock;
    }

    /**
     * @return a builder starting from {@link #UNKNOWN}
     */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * @return the item fallbacks that apply to a version, by identifier of the replaced item: those for every version,
     * overridden by those for this one
     */
    @NonNull Map<String, ItemFallback> items(@NonNull DataVersion version) {
        Map<String, ItemFallback> specific = versionItems.get(version.name());
        if (specific == null) {
            return items;
        }
        Map<String, ItemFallback> merged = new LinkedHashMap<>(items);
        merged.putAll(specific);
        return Map.copyOf(merged);
    }

    /**
     * @return the block fallbacks that apply to a version, by identifier of the replaced block: those for every version,
     * overridden by those for this one
     */
    @NonNull Map<String, BlockFallback> blocks(@NonNull DataVersion version) {
        Map<String, BlockFallback> specific = versionBlocks.get(version.name());
        if (specific == null) {
            return blocks;
        }
        Map<String, BlockFallback> merged = new LinkedHashMap<>(blocks);
        merged.putAll(specific);
        return Map.copyOf(merged);
    }

    /**
     * @return what items without an entry become, or null to leave them out
     */
    public @Nullable ItemFallback defaultItem() {
        return defaultItem;
    }

    /**
     * @return what blocks without an entry become, or null for air
     */
    public @Nullable BlockFallback defaultBlock() {
        return defaultBlock;
    }

    public static final class Builder {
        private final Map<String, ItemFallback> items = new LinkedHashMap<>();
        private final Map<String, BlockFallback> blocks = new LinkedHashMap<>();
        private final Map<String, Map<String, ItemFallback>> versionItems = new LinkedHashMap<>();
        private final Map<String, Map<String, BlockFallback>> versionBlocks = new LinkedHashMap<>();
        private @Nullable ItemFallback defaultItem = ItemFallback.UNKNOWN;
        private @Nullable BlockFallback defaultBlock = BlockFallback.UNKNOWN;

        private Builder() {
        }

        /**
         * Uses {@code fallback} for an item in every version that lacks it (or, with {@link ItemFallback#force()},
         * in every version).
         */
        public @NonNull Builder item(@NonNull BedrockItemType type, @NonNull ItemFallback fallback) {
            items.put(type.identifier(), Objects.requireNonNull(fallback));
            return this;
        }

        public @NonNull Builder item(@NonNull BedrockItemType type, @NonNull BedrockItemType replacement) {
            return item(type, ItemFallback.of(replacement));
        }

        /**
         * Like {@link #item(BedrockItemType, ItemFallback)} for one data version only, overriding the entry for every
         * version.
         */
        public @NonNull Builder item(@NonNull DataVersion version, @NonNull BedrockItemType type, @NonNull ItemFallback fallback) {
            versionItems.computeIfAbsent(version.name(), v -> new LinkedHashMap<>())
                .put(type.identifier(), Objects.requireNonNull(fallback));
            return this;
        }

        /**
         * Uses {@code fallback} for a block in every version that lacks it (or, with {@link BlockFallback#force()},
         * in every version).
         */
        public @NonNull Builder block(@NonNull BedrockBlockType type, @NonNull BlockFallback fallback) {
            blocks.put(type.identifier(), Objects.requireNonNull(fallback));
            return this;
        }

        public @NonNull Builder block(@NonNull BedrockBlockType type, @NonNull BedrockBlockType replacement) {
            return block(type, BlockFallback.of(replacement));
        }

        /**
         * Like {@link #block(BedrockBlockType, BlockFallback)} for one data version only, overriding the entry for every
         * version.
         */
        public @NonNull Builder block(@NonNull DataVersion version, @NonNull BedrockBlockType type, @NonNull BlockFallback fallback) {
            versionBlocks.computeIfAbsent(version.name(), v -> new LinkedHashMap<>())
                .put(type.identifier(), Objects.requireNonNull(fallback));
            return this;
        }

        /**
         * @param fallback what items without an entry become; null leaves them out. Default {@link ItemFallback#UNKNOWN}
         */
        public @NonNull Builder defaultItem(@Nullable ItemFallback fallback) {
            this.defaultItem = fallback;
            return this;
        }

        /**
         * @param fallback what blocks without an entry become; null sends air. Default {@link BlockFallback#UNKNOWN}
         */
        public @NonNull Builder defaultBlock(@Nullable BlockFallback fallback) {
            this.defaultBlock = fallback;
            return this;
        }

        public @NonNull Fallbacks build() {
            return new Fallbacks(this);
        }
    }
}
