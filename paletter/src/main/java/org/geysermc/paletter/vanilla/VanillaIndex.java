package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.internal.vanilla.BlockLayout;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.ItemVariant;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated constants linked to the newest data of {@link Paletter#bundled()}: built once, on the first use of a
 * constant that needs data (a state, a canonical id). Referencing constants alone loads nothing.
 */
final class VanillaIndex {
    private static volatile @Nullable VanillaIndex instance;

    final CanonicalBlocks blocks;
    final CanonicalItems items;
    final List<BedrockBlockType> blockTypes;
    final List<BedrockItemType> itemTypes;
    private final BlockTypeLayout[] byCanonical;
    final BedrockItemVariant[] itemsByCanonical;
    private final Map<String, BedrockBlockType> blocksById;
    private final Map<String, BedrockItemType> itemsById;
    /** Blocks whose states are not a full product in a stride order, and so use a lookup table. */
    final List<String> tableBlocks;

    private VanillaIndex() {
        // Collect every constant first, so the data links to the constant objects
        initialize("BedrockStates");
        Map<String, BedrockBlockType> constantBlocks = new HashMap<>();
        Map<String, BedrockItemType> constantItems = new HashMap<>();
        for (String name : List.of("BedrockBlocks", "BedrockPreviewBlocks", "BedrockItems", "BedrockPreviewItems")) {
            Class<?> constants = initialize(name);
            if (constants == null) {
                continue;
            }
            for (Field field : constants.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (field.get(null) instanceof BedrockBlockType type) {
                        constantBlocks.putIfAbsent(type.identifier(), type);
                    } else if (field.get(null) instanceof BedrockItemType type) {
                        constantItems.putIfAbsent(type.identifier(), type);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        Paletter paletter = Paletter.bundled();
        VanillaData latest = paletter.vanillaData(paletter.latest());
        this.blocks = paletter.canonicalBlocks();
        this.items = paletter.canonicalItems();

        List<NbtMap> states = latest.blockStates();
        this.byCanonical = new BlockTypeLayout[states.size()];
        Map<String, BedrockBlockType> blocksById = new LinkedHashMap<>();
        List<String> tableBlocks = new ArrayList<>();
        for (BlockLayout source : BlockLayout.of(states)) {
            BedrockBlockType type = constantBlocks.computeIfAbsent(source.identifier(), id -> new BedrockBlockType(id, VanillaTypes.UNKNOWN));
            NbtMap defaultState = latest.blockDefaults().get(source.identifier());
            int defaultOffset = defaultState == null ? 0 : blocks.index(defaultState) - source.first();
            if (defaultOffset < 0 || defaultOffset >= source.count()) {
                defaultOffset = 0;
            }
            BlockTypeLayout layout = BlockTypeLayout.vanilla(type, source, source.first(), defaultOffset);
            type.link(layout);
            for (int i = 0; i < source.count(); i++) {
                byCanonical[source.first() + i] = layout;
            }
            blocksById.put(source.identifier(), type);
            if (!source.arithmetic()) {
                tableBlocks.add(source.identifier() + ": " + source.problem());
            }
        }
        this.blocksById = blocksById;
        this.blockTypes = List.copyOf(blocksById.values());
        this.tableBlocks = List.copyOf(tableBlocks);

        Map<String, BedrockItemType> itemsById = new LinkedHashMap<>();
        Map<String, List<BedrockItemVariant>> variants = new HashMap<>();
        this.itemsByCanonical = new BedrockItemVariant[items.vanillaSize()];
        for (int i = 0; i < items.vanillaSize(); i++) {
            ItemVariant variant = items.variant(i);
            BedrockItemType type = itemsById.computeIfAbsent(variant.identifier(),
                id -> constantItems.computeIfAbsent(id, key -> new BedrockItemType(key, VanillaTypes.UNKNOWN)));
            BedrockBlockState blockState = variant.blockState() == null ? null : state(blocks.index(variant.blockState()));
            BedrockItemVariant handle = new BedrockItemVariant(type, variant.damage(), blockState, i);
            itemsByCanonical[i] = handle;
            variants.computeIfAbsent(variant.identifier(), id -> new ArrayList<>()).add(handle);
        }
        itemsById.forEach((id, type) -> type.link(variants.get(id)));
        this.itemsById = itemsById;
        this.itemTypes = List.copyOf(itemsById.values());

        // What the data no longer has (deprecated constants) or does not have yet (previews) resolves via its fallback
        constantBlocks.values().forEach(type -> {
            if (!blocksById.containsKey(type.identifier())) {
                type.linkAbsent(blocksById.get(type.fallback()));
            }
        });
        constantItems.values().forEach(type -> {
            if (!itemsById.containsKey(type.identifier())) {
                type.linkAbsent(itemsById.get(type.fallback()));
            }
        });
    }

    private static @Nullable Class<?> initialize(String simpleName) {
        try {
            return Class.forName(VanillaIndex.class.getPackageName() + "." + simpleName, true, VanillaIndex.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // The preview classes are only generated when a preview is bundled
            return null;
        }
    }

    static @NonNull VanillaIndex get() {
        VanillaIndex index = instance;
        if (index == null) {
            synchronized (VanillaIndex.class) {
                index = instance;
                if (index == null) {
                    instance = index = new VanillaIndex();
                }
            }
        }
        return index;
    }

    /**
     * @return the state of a vanilla canonical id, or null if it is out of range
     */
    @Nullable BedrockBlockState state(int canonical) {
        if (canonical < 0 || canonical >= byCanonical.length) {
            return null;
        }
        BlockTypeLayout layout = byCanonical[canonical];
        return layout.byOffset(canonical - layout.base);
    }

    @Nullable BedrockBlockType block(String identifier) {
        return blocksById.get(identifier);
    }

    @Nullable BedrockItemType item(String identifier) {
        return itemsById.get(identifier);
    }
}
