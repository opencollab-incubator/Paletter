package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.attach.AttachmentKey;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.ItemOverride;
import org.geysermc.paletter.item.ItemVariant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A patch on top of a palette: custom items and blocks, changes to vanilla items, creative inventory edits,
 * fallbacks and attachments. Layers are immutable and independent of protocol versions; the same layer is compiled
 * against every version.
 * <p>
 * Palettes are built by stacking layers, so ownership can be split. For example:
 * <ol>
 *     <li>a {@code geyser} layer with Geyser's own fixes and mappings, compiled once per version and shared;</li>
 *     <li>an {@code extensions} layer with custom items and blocks of extensions, also shared;</li>
 *     <li>a per-session layer, e.g. content of the server the player is on, applied with {@link Palette#extend}.</li>
 * </ol>
 * A later layer sees everything earlier layers did. Definitions untouched by a layer are shared with the palette
 * it extends, so the per-session palette costs little and its definitions stay {@code ==} to the shared ones.
 * Adding custom blocks in a layer does re-number block states, since the client sorts its palette by name.
 */
public final class PaletteLayer {
    private final String id;
    private final List<CustomItem> items;
    private final List<CustomBlock> blocks;
    private final List<Override> overrides;
    private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits;
    private final List<ItemAttachment> itemAttachments;
    private final List<BlockAttachment> blockAttachments;
    private final Map<String, ItemFallback> itemFallbacks;
    private final Map<String, BlockFallback> blockFallbacks;
    private final Map<String, List<String>> tags;

    private PaletteLayer(Builder builder) {
        this.id = builder.id;
        this.items = List.copyOf(builder.items);
        this.blocks = List.copyOf(builder.blocks);
        this.overrides = List.copyOf(builder.overrides);
        this.creativeEdits = List.copyOf(builder.creativeEdits);
        this.itemAttachments = List.copyOf(builder.itemAttachments);
        this.blockAttachments = List.copyOf(builder.blockAttachments);
        this.itemFallbacks = Map.copyOf(builder.itemFallbacks);
        this.blockFallbacks = Map.copyOf(builder.blockFallbacks);
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

    public @NonNull List<CustomBlock> blocks() {
        return blocks;
    }

    public @NonNull List<Override> overrides() {
        return overrides;
    }

    public @NonNull List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits() {
        return creativeEdits;
    }

    public @NonNull List<ItemAttachment> itemAttachments() {
        return itemAttachments;
    }

    public @NonNull List<BlockAttachment> blockAttachments() {
        return blockAttachments;
    }

    public @NonNull Map<String, ItemFallback> itemFallbacks() {
        return itemFallbacks;
    }

    public @NonNull Map<String, BlockFallback> blockFallbacks() {
        return blockFallbacks;
    }

    public @NonNull Map<String, List<String>> tags() {
        return tags;
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

    public record ItemAttachment(@NonNull ItemVariant variant, @NonNull AttachmentKey<?> key, @NonNull Object value) {
    }

    public record BlockAttachment(@NonNull NbtMap state, @NonNull AttachmentKey<?> key, @NonNull Object value) {
    }

    public static final class Builder {
        private final String id;
        private final List<CustomItem> items = new ArrayList<>();
        private final List<CustomBlock> blocks = new ArrayList<>();
        private final List<Override> overrides = new ArrayList<>();
        private final List<BiConsumer<CreativeEditor, DataVersion>> creativeEdits = new ArrayList<>();
        private final List<ItemAttachment> itemAttachments = new ArrayList<>();
        private final List<BlockAttachment> blockAttachments = new ArrayList<>();
        private final Map<String, ItemFallback> itemFallbacks = new LinkedHashMap<>();
        private final Map<String, BlockFallback> blockFallbacks = new LinkedHashMap<>();
        private final Map<String, List<String>> tags = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        public @NonNull Builder item(@NonNull CustomItem item) {
            items.add(item);
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
         * Attaches a value to an item variant, e.g. the Java item it translates back to.
         */
        public <T> @NonNull Builder attach(@NonNull ItemVariant variant, @NonNull AttachmentKey<T> key, @NonNull T value) {
            itemAttachments.add(new ItemAttachment(variant, key, value));
            return this;
        }

        /**
         * Attaches a value to a block state (written against the newest Bedrock version).
         */
        public <T> @NonNull Builder attachBlock(@NonNull NbtMap state, @NonNull AttachmentKey<T> key, @NonNull T value) {
            blockAttachments.add(new BlockAttachment(BlockStates.normalize(state), key, value));
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

        public @NonNull Builder tag(@NonNull String tag, @NonNull String... identifiers) {
            tags.computeIfAbsent(tag, t -> new ArrayList<>()).addAll(List.of(identifiers));
            return this;
        }

        public @NonNull PaletteLayer build() {
            return new PaletteLayer(this);
        }
    }
}
