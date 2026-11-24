package dev.opencollab.paletter;

import dev.opencollab.paletter.annotation.Internal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.internal.shape.VoxelShapeRules;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.VoxelShape;
import dev.opencollab.paletter.creative.CreativeEditor;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.ItemKey;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.annotation.Experimental;
import dev.opencollab.paletter.item.ItemOverride;
import dev.opencollab.paletter.internal.item.ItemVariant;

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
 * <p>
 * Thread safety: a built layer is immutable and may be shared between threads and compiled by several palettes at once.
 * Its {@link Builder} is not thread safe. The callbacks given to the builder ({@link Builder#creative(Consumer)},
 * {@link Builder#overrideItem(String, Consumer)}) are called while a palette is compiled, once per compilation and data
 * version, possibly from any thread; they must not depend on external mutable state.
 * <p>
 * Null handling: everything is {@code @NonNull} unless marked; the builder methods throw {@link NullPointerException} for
 * null. Problems with the content (a duplicate identifier, a hash collision) are not found here but when a palette
 * compiles the layer: {@link Paletter} throws {@link PaletteException}, {@link Palette#extend} reports them. Content is
 * named by keys, custom items and blocks create their own (see {@link CustomItem}, {@link CustomBlock}).
 *
 * @since 1.0.0
 */
public final class PaletteLayer {
    private final String id;
    private final List<CustomItem> items;
    private final List<CustomBlock> blocks;
    private final List<Override> overrides;
    private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits;
    private final Map<String, List<String>> tags;
    private final List<ItemVariant> variants;
    private final Map<String, VoxelShape> voxelShapes;
    private final @Nullable String minEngineVersion;

    private PaletteLayer(Builder builder) {
        this.id = builder.id;
        this.minEngineVersion = builder.minEngineVersion;
        this.items = List.copyOf(builder.items);
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
     * Starts a layer.
     *
     * @param id a name for diagnostics, e.g. {@code geyser} or {@code session:Steve}
     * @return a new builder
     * @since 1.0.0
     */
    public static @NonNull Builder builder(@NonNull String id) {
        return new Builder(id);
    }

    /**
     * Returns the name this layer was given, which reports and error messages use.
     *
     * @return the id
     * @since 1.0.0
     */
    public @NonNull String id() {
        return id;
    }

    /**
     * Returns the {@code min_engine_version} of the pack this layer stands for ({@link Builder#minEngineVersion}).
     *
     * @return the engine version, or null for the newest
     * @since 1.0.0
     */
    public @Nullable String minEngineVersion() {
        return minEngineVersion;
    }

    /**
     * Lists the custom items of this layer, not the items of custom blocks (see {@link CustomBlock#item()}).
     *
     * @return the custom items, in registration order, unmodifiable
     * @since 1.0.0
     */
    public @NonNull List<CustomItem> items() {
        return items;
    }

    /**
     * Lists the custom blocks of this layer. Their items belong to the blocks, see {@link CustomBlock#item()}.
     *
     * @return the custom blocks, in registration order, unmodifiable
     * @since 1.0.0
     */
    public @NonNull List<CustomBlock> blocks() {
        return blocks;
    }

    /** The vanilla item overrides of this layer, in registration order, for the compiler. */
    List<Override> overrides() {
        return overrides;
    }

    /** The creative inventory edits of this layer, in registration order, for the compiler. */
    List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits() {
        return creativeEdits;
    }

    /**
     * Lists the item tags this layer defines.
     *
     * @return the identifiers of each tag, unmodifiable
     * @since 1.0.0
     */
    public @NonNull Map<String, List<String>> tags() {
        return tags;
    }

    /**
     * Internal: the item variants this layer gives canonical ids to.
     *
     * @return item variants this layer gives canonical ids to, besides its custom items
     */
    @Internal
    public @NonNull List<ItemVariant> variants() {
        return variants;
    }

    /**
     * Lists the voxel shapes this layer defines.
     *
     * @return the voxel shapes this layer defines, by identifier, in the order they were registered
     * @since 1.0.0
     */
    public @NonNull Map<String, VoxelShape> voxelShapes() {
        return voxelShapes;
    }

    /**
     * Tells whether compiling this layer changes block states.
     *
     * @return true if compiling this layer changes block states, so the block palette cannot be shared
     * @since 1.0.0
     */
    public boolean changesBlocks() {
        return !blocks.isEmpty();
    }

    @java.lang.Override
    public String toString() {
        return "PaletteLayer{" + id + "}";
    }

    /**
     * A change to a vanilla item, registered with {@link Builder#overrideItem(String, Consumer)}.
     *
     * @param identifier the identifier of the vanilla item
     * @param modifier called with the override to configure while a palette is compiled
     */
    record Override(String identifier, Consumer<ItemOverride> modifier) {
        Override {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(modifier, "modifier");
        }
    }

    /**
     * Collects the content of a {@link PaletteLayer}. Not thread safe; every method throws {@link NullPointerException} for
     * a null argument unless the parameter is marked {@code @Nullable}, and returns this builder.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private final String id;
        private final List<CustomItem> items = new ArrayList<>();
        private final List<CustomBlock> blocks = new ArrayList<>();
        private final List<Override> overrides = new ArrayList<>();
        private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits = new ArrayList<>();
        private final Map<String, List<String>> tags = new LinkedHashMap<>();
        private final List<ItemVariant> variants = new ArrayList<>();
        private final Map<String, VoxelShape> voxelShapes = new LinkedHashMap<>();
        private @Nullable String minEngineVersion;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        /**
         * Sets the {@code min_engine_version} of the pack this layer stands for. BDS writes a {@code molangVersion} into every
         * block of a pack that follows it (captured from 1.26.36.1 on: 1.16.100 gives 0, 1.19.40 gives 6, the packs of the
         * current formats 13); the blocks of this layer get the one of this version. Unset, the layer is of the newest engine.
         *
         * @param minEngineVersion the pack's engine version, dotted numbers such as {@code 1.19.40}
         * @return this builder
         * @throws NullPointerException if the version is null
         * @throws IllegalArgumentException if it is not dotted numbers
         * @since 1.0.0
         */
        public @NonNull Builder minEngineVersion(@NonNull String minEngineVersion) {
            if (!minEngineVersion.matches("\\d+(\\.\\d+)*")) {
                throw new IllegalArgumentException("min_engine_version " + minEngineVersion + " is not dotted numbers such as 1.19.40");
            }
            this.minEngineVersion = minEngineVersion;
            return this;
        }

        /**
         * Registers a custom item. The item of a custom block belongs to the block: see
         * {@link CustomBlock.Builder#item} and {@link CustomBlock.Builder#creativePlacement}.
         *
         * @param item the custom item
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder item(@NonNull CustomItem item) {
            items.add(Objects.requireNonNull(item));
            return this;
        }

        /**
         * Registers a custom block. Its item follows the block's {@link CustomBlock.BlockItem}.
         *
         * @param block the custom block
         * @return this builder
         * @since 1.0.0
         */
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
         *
         * @param identifier the identifier of the vanilla item
         * @param modifier called with the override to configure while a palette is compiled, once per version; it must
         *                 not depend on external mutable state
         * @return this builder
         * @since 1.0.0
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
         * @param type the vanilla item type
         * @param modifier called with the override to configure while a palette is compiled, once per version
         * @return this builder
         * @since 1.0.0
         */
        @Experimental
        public @NonNull Builder overrideItem(@NonNull BedrockItemType type, @NonNull Consumer<ItemOverride> modifier) {
            return overrideItem(type.identifier(), modifier);
        }

        /**
         * Edits the creative inventory. Runs after this layer's custom items and blocks were placed, so it can
         * reference them.
         *
         * @param edit called with the editor while a palette is compiled, once per version
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder creative(@NonNull Consumer<CreativeEditor> edit) {
            Objects.requireNonNull(edit);
            creativeEdits.add((editor, version) -> edit.accept(editor));
            return this;
        }

        /**
         * Edits the creative inventory depending on the data version, for the rare edit that only applies to some.
         *
         * @param edit called with the editor and the data version while a palette is compiled
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder creative(@NonNull BiConsumer<CreativeEditor, DataVersion> edit) {
            creativeEdits.add(Objects.requireNonNull(edit));
            return this;
        }

        /**
         * Internal: gives an item variant a canonical id, so that {@link Palette#decode} recognizes it. Needed for
         * variants of the newest version that are neither an item's base variant nor in the creative inventory, e.g. a
         * damage value or block state a consumer maps to. Ids are appended after the custom items of this layer, in
         * call order; variants that already have one keep it. Like every canonical id they are the same in every
         * version.
         *
         * @param variant the variant
         * @return this builder
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
         * @param key the item type or variant, as a key
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder variant(@NonNull ItemKey key) {
            return variant(key.variant().toItemVariant());
        }

        /**
         * Internal: gives several variants canonical ids, as {@link #variant(ItemVariant)}.
         *
         * @param variants the variants
         * @return this builder
         */
        @Internal
        public @NonNull Builder variants(@NonNull Collection<ItemVariant> variants) {
            variants.forEach(this::variant);
            return this;
        }

        /**
         * Adds item identifiers to an item tag, creating the tag if it is new. Identifiers added in several calls
         * accumulate.
         *
         * @param tag the tag, e.g. {@code my:gems}
         * @param identifiers the identifiers of the items in the tag
         * @return this builder
         * @throws NullPointerException if the tag, the array or an identifier is null
         * @since 1.0.0
         */
        public @NonNull Builder tag(@NonNull String tag, @NonNull String... identifiers) {
            Objects.requireNonNull(tag, "tag");
            List<String> added = List.of(identifiers);
            tags.computeIfAbsent(tag, t -> new ArrayList<>()).addAll(added);
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
         * @return this builder
         * @throws NullPointerException if the identifier or the shape is null
         * @throws IllegalArgumentException if the identifier is refused (see above) or already defined in this layer
         * @since 1.0.0
         */
        public @NonNull Builder voxelShape(@NonNull String identifier, @NonNull VoxelShape shape) {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(shape, "shape");
            VoxelShapeRules.checkIdentifier(identifier);
            if (voxelShapes.putIfAbsent(identifier, shape) != null) {
                throw new IllegalArgumentException("The voxel shape " + identifier + " is defined twice");
            }
            return this;
        }

        /**
         * Creates the immutable layer. Nothing is checked against a data version yet: that happens when a palette
         * compiles the layer.
         *
         * @return the layer
         * @since 1.0.0
         */
        public @NonNull PaletteLayer build() {
            return new PaletteLayer(this);
        }
    }
}
