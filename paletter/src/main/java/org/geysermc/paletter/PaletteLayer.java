package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.FallbackDefaults;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.ItemOverride;
import org.geysermc.paletter.item.ItemVariant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A patch on top of a palette: custom items and blocks, changes to vanilla items, creative inventory edits,
 * and fallbacks. Layers are immutable and independent of protocol versions; the same layer is compiled
 * against every version.
 * <p>
 * Palettes are built by stacking layers, so ownership can be split. For example:
 * <ol>
 *     <li>a {@code geyser} layer with Geyser's own fixes and custom items, compiled once per version and shared;</li>
 *     <li>an {@code extensions} layer with custom items and blocks of extensions, also shared;</li>
 *     <li>a per-session layer, e.g. content of the server the player is on, applied with {@link Palette#extend}.</li>
 * </ol>
 * A later layer sees everything earlier layers did. Definitions untouched by a layer are shared with the palette
 * it extends, so the per-session palette costs little and its definitions stay {@code ==} to the shared ones.
 * Adding custom blocks in a layer does re-number block states in {@link org.geysermc.paletter.block.BlockNetworkIds#SEQUENTIAL}
 * palettes, since the client sorts its palette by name hash. Canonical ids never change: custom content is appended.
 *
 * @since 2.0.0
 */
public final class PaletteLayer {
    private final String id;
    private final List<CustomItem> items;
    private final List<ItemEntry> itemEntries;
    private final List<CustomBlock> blocks;
    private final List<Override> overrides;
    private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits;
    private final Map<String, ItemFallback> itemFallbacks;
    private final Map<String, BlockFallback> blockFallbacks;
    private final Map<String, Map<String, ItemFallback>> versionItemFallbacks;
    private final Map<String, Map<String, BlockFallback>> versionBlockFallbacks;
    private final @Nullable FallbackDefaults fallbackDefaults;
    private final Map<String, List<String>> tags;
    private final List<ItemVariant> variants;

    private PaletteLayer(Builder builder) {
        this.id = builder.id;
        this.items = List.copyOf(builder.items);
        this.itemEntries = List.copyOf(builder.itemEntries);
        this.blocks = List.copyOf(builder.blocks);
        this.overrides = List.copyOf(builder.overrides);
        this.creativeEdits = List.copyOf(builder.creativeEdits);
        this.itemFallbacks = Map.copyOf(builder.itemFallbacks);
        this.blockFallbacks = Map.copyOf(builder.blockFallbacks);
        Map<String, Map<String, ItemFallback>> versionItems = new LinkedHashMap<>();
        builder.versionItemFallbacks.forEach((version, map) -> versionItems.put(version, Map.copyOf(map)));
        this.versionItemFallbacks = Map.copyOf(versionItems);
        Map<String, Map<String, BlockFallback>> versionBlocks = new LinkedHashMap<>();
        builder.versionBlockFallbacks.forEach((version, map) -> versionBlocks.put(version, Map.copyOf(map)));
        this.versionBlockFallbacks = Map.copyOf(versionBlocks);
        this.fallbackDefaults = builder.fallbackDefaults;
        this.variants = List.copyOf(builder.variants);
        Map<String, List<String>> tags = new LinkedHashMap<>();
        builder.tags.forEach((tag, list) -> tags.put(tag, List.copyOf(list)));
        this.tags = Map.copyOf(tags);
    }

    /**
     * @param id a name for diagnostics, e.g. {@code geyser} or {@code session:Steve}
     */
    public static @NonNull Builder builder(@NonNull String id) {
        return new Builder(id);
    }

    public @NonNull String id() {
        return id;
    }

    public @NonNull List<CustomItem> items() {
        return items;
    }

    /**
     * @return custom items and items of custom blocks registered with {@link Builder#blockItem}, in registration
     * order: the order their runtime and canonical ids are assigned in. Items of blocks registered with
     * {@link Builder#block} follow them
     * @since 2.0.0
     */
    public @NonNull List<ItemEntry> itemEntries() {
        return itemEntries;
    }

    public @NonNull List<CustomBlock> blocks() {
        return blocks;
    }

    public @NonNull List<Override> overrides() {
        return overrides;
    }

    public @NonNull List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits() {
        return creativeEdits;
    }

    public @NonNull Map<String, ItemFallback> itemFallbacks() {
        return itemFallbacks;
    }

    public @NonNull Map<String, BlockFallback> blockFallbacks() {
        return blockFallbacks;
    }

    /**
     * @return the item fallbacks this layer applies to a version: those for every version, overridden by those for
     *         this one
     * @since 2.0.0
     */
    public @NonNull Map<String, ItemFallback> itemFallbacks(@NonNull DataVersion version) {
        Map<String, ItemFallback> specific = versionItemFallbacks.get(version.name());
        if (specific == null) {
            return itemFallbacks;
        }
        Map<String, ItemFallback> merged = new LinkedHashMap<>(itemFallbacks);
        merged.putAll(specific);
        return Map.copyOf(merged);
    }

    /**
     * @return the block fallbacks this layer applies to a version: those for every version, overridden by those for
     *         this one
     * @since 2.0.0
     */
    public @NonNull Map<String, BlockFallback> blockFallbacks(@NonNull DataVersion version) {
        Map<String, BlockFallback> specific = versionBlockFallbacks.get(version.name());
        if (specific == null) {
            return blockFallbacks;
        }
        Map<String, BlockFallback> merged = new LinkedHashMap<>(blockFallbacks);
        merged.putAll(specific);
        return Map.copyOf(merged);
    }

    /**
     * @return the defaults this layer sets for content without a fallback, or null if it keeps those of the layers
     *         below (initially {@link FallbackDefaults#UNKNOWN})
     * @since 2.0.0
     */
    public @Nullable FallbackDefaults fallbackDefaults() {
        return fallbackDefaults;
    }

    public @NonNull Map<String, List<String>> tags() {
        return tags;
    }

    /**
     * @return item variants this layer gives canonical ids to, besides its custom items
     */
    public @NonNull List<ItemVariant> variants() {
        return variants;
    }

    /**
     * @return true if compiling this layer changes block states, so the block palette cannot be shared
     */
    public boolean changesBlocks() {
        return !blocks.isEmpty();
    }

    @java.lang.Override
    public String toString() {
        return "PaletteLayer{" + id + "}";
    }

    public record Override(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier) {
    }

    /**
     * An item a layer registers: a custom item, or the item of a custom block.
     *
     * @since 2.0.0
     */
    public sealed interface ItemEntry {
        /**
         * @since 2.0.0
         */
        record Custom(@NonNull CustomItem item) implements ItemEntry {
        }

        /**
         * @param block the identifier of a custom block of this layer or an earlier one
         * @param item how the item is registered
         * @param creative where it goes in the creative inventory, or null to leave it out
         * @since 2.0.0
         */
        record OfBlock(@NonNull String block, CustomBlock.@NonNull BlockItem item, @Nullable CreativePlacement creative) implements ItemEntry {
        }
    }

    public static final class Builder {
        private final String id;
        private final List<CustomItem> items = new ArrayList<>();
        private final List<ItemEntry> itemEntries = new ArrayList<>();
        private final List<CustomBlock> blocks = new ArrayList<>();
        private final List<Override> overrides = new ArrayList<>();
        private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits = new ArrayList<>();
        private final Map<String, ItemFallback> itemFallbacks = new LinkedHashMap<>();
        private final Map<String, BlockFallback> blockFallbacks = new LinkedHashMap<>();
        private final Map<String, Map<String, ItemFallback>> versionItemFallbacks = new LinkedHashMap<>();
        private final Map<String, Map<String, BlockFallback>> versionBlockFallbacks = new LinkedHashMap<>();
        private @Nullable FallbackDefaults fallbackDefaults;
        private final Map<String, List<String>> tags = new LinkedHashMap<>();
        private final List<ItemVariant> variants = new ArrayList<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        public @NonNull Builder item(@NonNull CustomItem item) {
            items.add(item);
            itemEntries.add(new ItemEntry.Custom(item));
            return this;
        }

        /**
         * Registers the item of a custom block separately from the block, e.g. when the block comes from an earlier
         * layer (registered there with {@link CustomBlock.BlockItem#NONE}), or to order block items among custom items:
         * runtime and canonical ids are assigned in the order of {@link #item} and {@code blockItem} calls.
         *
         * @since 2.0.0
         */
        public @NonNull Builder blockItem(@NonNull String block, CustomBlock.@NonNull BlockItem item, @Nullable CreativePlacement creative) {
            itemEntries.add(new ItemEntry.OfBlock(Objects.requireNonNull(block), Objects.requireNonNull(item), creative));
            return this;
        }

        public @NonNull Builder block(@NonNull CustomBlock block) {
            blocks.add(block);
            return this;
        }

        /**
         * Changes an existing item definition. Skipped (and reported) for versions without the item.
         */
        public @NonNull Builder overrideItem(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier) {
            overrides.add(new Override(identifier, modifier));
            return this;
        }

        /**
         * Changes an existing item definition, like {@link #overrideItem(String, Consumer)}.
         *
         * @since 2.0.0
         */
        public @NonNull Builder overrideItem(@NonNull BedrockItemType type, @NonNull Consumer<ItemOverride> modifier) {
            return overrideItem(type.identifier(), modifier);
        }

        /**
         * Edits the creative inventory. Runs after this layer's custom items and blocks were placed, so it can
         * reference them.
         */
        public @NonNull Builder creative(@NonNull Consumer<CreativeEditor> edit) {
            Objects.requireNonNull(edit);
            creativeEdits.add((editor, version) -> edit.accept(editor));
            return this;
        }

        /**
         * Edits the creative inventory depending on the data version, for the rare edit that only applies to some.
         */
        public @NonNull Builder creative(@NonNull BiConsumer<CreativeEditor, DataVersion> edit) {
            creativeEdits.add(Objects.requireNonNull(edit));
            return this;
        }

        /**
         * Uses {@code replacement} for {@code identifier} in versions where it does not exist. Typically only
         * needed for custom content that references new vanilla items; vanilla fallbacks are bundled.
         */
        public @NonNull Builder itemFallback(@NonNull String identifier, @NonNull String replacement) {
            return itemFallback(identifier, ItemFallback.of(replacement));
        }

        public @NonNull Builder itemFallback(@NonNull String identifier, @NonNull ItemFallback fallback) {
            itemFallbacks.put(identifier, fallback);
            return this;
        }

        public @NonNull Builder blockFallback(@NonNull String identifier, @NonNull BlockFallback fallback) {
            blockFallbacks.put(identifier, fallback);
            return this;
        }

        /**
         * @since 2.0.0
         */
        public @NonNull Builder itemFallback(@NonNull BedrockItemType type, @NonNull BedrockItemType replacement) {
            return itemFallback(type.identifier(), ItemFallback.of(replacement));
        }

        /**
         * @since 2.0.0
         */
        public @NonNull Builder itemFallback(@NonNull BedrockItemType type, @NonNull ItemFallback fallback) {
            return itemFallback(type.identifier(), fallback);
        }

        /**
         * @since 2.0.0
         */
        public @NonNull Builder blockFallback(@NonNull BedrockBlockType type, @NonNull BlockFallback fallback) {
            return blockFallback(type.identifier(), fallback);
        }

        /**
         * @since 2.0.0
         */
        public @NonNull Builder blockFallback(@NonNull BedrockBlockType type, @NonNull BedrockBlockType replacement) {
            return blockFallback(type.identifier(), BlockFallback.of(replacement));
        }

        /**
         * Uses {@code fallback} for {@code identifier} in one data version only (e.g. {@code "26.50"}), overriding this
         * layer's fallback for every version and the bundled one. Fallbacks apply where the item does not exist, or
         * with {@link ItemFallback#force()} also where it does.
         *
         * @since 2.0.0
         */
        public @NonNull Builder itemFallback(@NonNull String version, @NonNull String identifier, @NonNull ItemFallback fallback) {
            versionItemFallbacks.computeIfAbsent(Objects.requireNonNull(version), v -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(identifier), Objects.requireNonNull(fallback));
            return this;
        }

        /**
         * Uses {@code fallback} for {@code identifier} in one data version only (e.g. {@code "26.50"}), overriding this
         * layer's fallback for every version and the bundled one.
         *
         * @since 2.0.0
         */
        public @NonNull Builder blockFallback(@NonNull String version, @NonNull String identifier, @NonNull BlockFallback fallback) {
            versionBlockFallbacks.computeIfAbsent(Objects.requireNonNull(version), v -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(identifier), Objects.requireNonNull(fallback));
            return this;
        }

        /**
         * Sets what canonical content without any fallback becomes in versions that lack it, for this layer and the
         * layers above it. The default is {@link FallbackDefaults#UNKNOWN}; {@link FallbackDefaults#NONE} sends air and
         * leaves items out.
         *
         * @since 2.0.0
         */
        public @NonNull Builder fallbackDefaults(@NonNull FallbackDefaults defaults) {
            this.fallbackDefaults = Objects.requireNonNull(defaults);
            return this;
        }

        /**
         * Gives an item variant a canonical id, so it can be used with {@link Palette#item(int)} and is recognized by
         * {@link Palette#match}. Needed for variants of the newest version that are neither an item's base variant nor
         * in the creative inventory, e.g. a damage value or block state a consumer maps to. Ids are appended after
         * the custom items of this layer, in call order; variants that already have one keep it. Like every canonical id
         * they are the same in every version.
         */
        public @NonNull Builder variant(@NonNull ItemVariant variant) {
            variants.add(Objects.requireNonNull(variant));
            return this;
        }

        /**
         * Gives a variant a canonical id, like {@link #variant(ItemVariant)}.
         *
         * @since 2.0.0
         */
        public @NonNull Builder variant(@NonNull BedrockItemVariant variant) {
            return variant(variant.toItemVariant());
        }

        public @NonNull Builder variants(@NonNull Collection<ItemVariant> variants) {
            variants.forEach(this::variant);
            return this;
        }

        public @NonNull Builder tag(@NonNull String tag, @NonNull String... identifiers) {
            tags.computeIfAbsent(tag, t -> new ArrayList<>()).addAll(List.of(identifiers));
            return this;
        }

        public @NonNull PaletteLayer build() {
            return new PaletteLayer(this);
        }
    }
}
