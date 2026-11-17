package org.geysermc.paletter;

import org.geysermc.paletter.annotation.Internal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.internal.shape.VoxelShapeRules;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.item.ItemOverride;
import org.geysermc.paletter.internal.item.ItemVariant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A patch on top of a palette: custom items and blocks, changes to vanilla items, creative inventory edits,
 * tags. Content only, fallbacks and creative ordering are policy of the {@link Paletter}. Layers are immutable and independent of protocol versions; the same layer is compiled
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
 * Canonical ids never change: custom content is appended. Block runtime ids are state hashes, so adding custom blocks
 * does not change the ids of other blocks.
 *
 * @since 0.1.0
 */
public final class PaletteLayer {
    private final String id;
    private final List<ItemEntry> itemEntries;
    /** The custom items among {@link #itemEntries}. */
    private final List<CustomItem> items;
    private final List<CustomBlock> blocks;
    private final List<Override> overrides;
    private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits;
    private final Map<String, List<String>> tags;
    private final List<ItemVariant> variants;
    private final Map<String, VoxelShape> voxelShapes;

    private PaletteLayer(Builder builder) {
        this.id = builder.id;
        this.itemEntries = List.copyOf(builder.itemEntries);
        this.items = itemEntries.stream().filter(ItemEntry.Custom.class::isInstance)
            .map(entry -> ((ItemEntry.Custom) entry).item()).toList();
        this.blocks = List.copyOf(builder.blocks);
        this.overrides = List.copyOf(builder.overrides);
        this.creativeEdits = List.copyOf(builder.creativeEdits);
        this.variants = List.copyOf(builder.variants);
        Map<String, List<String>> tags = new LinkedHashMap<>();
        builder.tags.forEach((tag, list) -> tags.put(tag, List.copyOf(list)));
        this.tags = Map.copyOf(tags);
        this.voxelShapes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.voxelShapes));
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
     * @since 0.1.0
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

    public @NonNull Map<String, List<String>> tags() {
        return tags;
    }

    /**
     * @return item variants this layer gives canonical ids to, besides its custom items
     */
    @Internal
    public @NonNull List<ItemVariant> variants() {
        return variants;
    }

    /**
     * @return the voxel shapes this layer defines, by identifier, in the order they were registered
     * @since 0.1.0
     */
    public @NonNull Map<String, VoxelShape> voxelShapes() {
        return voxelShapes;
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
     * @since 0.1.0
     */
    public sealed interface ItemEntry {
        /**
         * @since 0.1.0
         */
        record Custom(@NonNull CustomItem item) implements ItemEntry {
        }

        /**
         * @param block the identifier of a custom block of this layer or an earlier one
         * @param item how the item is registered
         * @param creative where it goes in the creative inventory, or null to leave it out
         * @since 0.1.0
         */
        record OfBlock(@NonNull String block, CustomBlock.@NonNull BlockItem item, @Nullable CreativePlacement creative) implements ItemEntry {
        }
    }

    public static final class Builder {
        private final String id;
        private final List<ItemEntry> itemEntries = new ArrayList<>();
        private final List<CustomBlock> blocks = new ArrayList<>();
        private final List<Override> overrides = new ArrayList<>();
        private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits = new ArrayList<>();
        private final Map<String, List<String>> tags = new LinkedHashMap<>();
        private final List<ItemVariant> variants = new ArrayList<>();
        private final Map<String, VoxelShape> voxelShapes = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        public @NonNull Builder item(@NonNull CustomItem item) {
            itemEntries.add(new ItemEntry.Custom(Objects.requireNonNull(item)));
            return this;
        }

        /**
         * Registers the item of a custom block separately from the block, e.g. when the block comes from an earlier
         * layer (registered there with {@link CustomBlock.BlockItem#NONE}), or to order block items among custom items:
         * runtime and canonical ids are assigned in the order of {@link #item} and {@code blockItem} calls.
         *
         * @since 0.1.0
         */
        public @NonNull Builder blockItem(@NonNull String block, CustomBlock.@NonNull BlockItem item, @Nullable CreativePlacement creative) {
            itemEntries.add(new ItemEntry.OfBlock(Objects.requireNonNull(block), Objects.requireNonNull(item), creative));
            return this;
        }

        public @NonNull Builder block(@NonNull CustomBlock block) {
            blocks.add(Objects.requireNonNull(block));
            return this;
        }

        /**
         * Changes an existing item definition. Skipped (and reported) for versions without the item.
         * <p>
         * <b>Experimental.</b> BDS replaced the components of an overridden vanilla data driven item; for hard coded items
         * it accepted the feather at format 1.10 (as a legacy item) and rejected the stick at 1.26.60, and it rejected a
         * legacy bread at 1.16.100 (1.20 and up was never tried). Paletter refuses nothing. The client's behaviour for
         * Paletter's overrides is <b>unverified on a client</b> (no client test exists). The API may change.
         */
        @Experimental
        public @NonNull Builder overrideItem(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier) {
            overrides.add(new Override(identifier, modifier));
            return this;
        }

        /**
         * Changes an existing item definition, like {@link #overrideItem(String, Consumer)}, with the same caveats:
         * experimental, and unverified on a client.
         *
         * @since 0.1.0
         */
        @Experimental
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
         * Gives an item variant a canonical id, so that {@link Palette#decode} recognizes it. Needed for variants of the newest version that are neither an item's base variant nor
         * in the creative inventory, e.g. a damage value or block state a consumer maps to. Ids are appended after
         * the custom items of this layer, in call order; variants that already have one keep it. Like every canonical id
         * they are the same in every version.
         */
        @Internal
        public @NonNull Builder variant(@NonNull ItemVariant variant) {
            variants.add(Objects.requireNonNull(variant));
            return this;
        }

        /**
         * Gives a variant a canonical id, so that {@link Palette#decode} recognizes it. Needed for variants of the newest version that are
         * neither an item's base variant nor in the creative inventory. A palette hands back a variant equal to this one,
         * not necessarily this object: compare it with {@code equals}.
         *
         * @since 0.1.0
         */
        public @NonNull Builder variant(@NonNull BedrockItemVariant variant) {
            return variant(variant.toItemVariant());
        }

        @Internal
        public @NonNull Builder variants(@NonNull Collection<ItemVariant> variants) {
            variants.forEach(this::variant);
            return this;
        }

        public @NonNull Builder tag(@NonNull String tag, @NonNull String... identifiers) {
            tags.computeIfAbsent(tag, t -> new ArrayList<>()).addAll(List.of(identifiers));
            return this;
        }

        /**
         * Defines a voxel shape, as a file in the {@code shapes} directory of a behavior pack does: the shape Bedrock
         * culls the faces of neighbouring blocks with, for the custom blocks that name it as the {@code culling_shape} of
         * their {@code minecraft:geometry}. A voxel shape is a culling shape, not a collision or selection shape (those are
         * {@code BlockShape}s), and the Script API does not expose it. The palette sends it with
         * {@link Palette#voxelShapesPacket()}.
         * <p>
         * Shapes get their ids in the order they are registered (BDS: in the order of their file names), which a client
         * does not see. BDS refuses the {@code minecraft} namespace, which fails here with its reason; the checks of the
         * shape itself are those of {@link VoxelShape#ofBoxes(String, float...)}.
         *
         * @param identifier the name blocks use, e.g. {@code my:half_pipe}
         * @param shape the shape
         * @since 0.1.0
         */
        public @NonNull Builder voxelShape(@NonNull String identifier, @NonNull VoxelShape shape) {
            VoxelShapeRules.checkIdentifier(identifier);
            if (voxelShapes.putIfAbsent(identifier, shape) != null) {
                throw new IllegalArgumentException("The voxel shape " + identifier + " is defined twice");
            }
            return this;
        }

        public @NonNull PaletteLayer build() {
            return new PaletteLayer(this);
        }
    }
}
