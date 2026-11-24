package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.vanilla.BlockLayout;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.access.PaletteAccess;
import dev.opencollab.paletter.internal.item.ItemVariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated constants linked to the newest bundled release (see {@link Paletter#bundled()}): built once, on the first use of a
 * constant that needs data (a state, a canonical id). Referencing constants alone loads nothing.
 */
final class VanillaIndex {
    /** Initialization on demand: the first {@link #get} builds the index, every later one is a plain static read. */
    private static final class Holder {
        static final VanillaIndex INSTANCE;
        /** Why building failed: a failed class initialization only throws NoClassDefFoundError afterwards, which hides the cause. */
        static final Throwable FAILURE;

        static {
            VanillaIndex index = null;
            Throwable failure = null;
            try {
                index = new VanillaIndex();
            } catch (Throwable t) {
                failure = t;
            }
            INSTANCE = index;
            FAILURE = failure;
        }
    }

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
    final Renames renames = Access.data().loader(PaletteDataSource.bundled()).renames();
    private final Map<String, BedrockItemType> itemConstants;
    private final Map<String, BedrockBlockType> blockConstants;

    private VanillaIndex() {
        // Collect every constant first, so the data links to the constant objects
        initialize("BedrockStates");
        Map<String, BedrockBlockType> constantBlocks = new HashMap<>();
        Map<String, BedrockItemType> constantItems = new HashMap<>();
        initialize("BedrockBlocks");
        initialize("BedrockItems");
        // The constants classes registered the types they made (aliases make none): no reflection over their fields
        synchronized (VanillaTypes.BLOCK_CONSTANTS) {
            VanillaTypes.BLOCK_CONSTANTS.forEach(type -> constantBlocks.putIfAbsent(type.identifier(), type));
        }
        synchronized (VanillaTypes.ITEM_CONSTANTS) {
            VanillaTypes.ITEM_CONSTANTS.forEach(type -> constantItems.putIfAbsent(type.identifier(), type));
        }

        // The named variants register on their types, which the data's variants of them adopt
        VanillaTypes.loadNamedVariants();

        this.itemConstants = constantItems;
        this.blockConstants = constantBlocks;
        // The constants of a preview are not in the data of the release: they carry their states (see VanillaTypes.blockWithStates)
        // The loader, not Paletter.bundled(): that compiles every version, which links these constants
        PaletteAccess access = Access.palette();
        Paletter paletter = access.bundledLoader();
        VanillaData latest = access.vanillaData(paletter, paletter.latest());
        this.blocks = access.canonicalBlocks(paletter);
        this.items = access.canonicalItems(paletter);

        List<NbtMap> states = latest.blockStates();
        this.byCanonical = new BlockTypeLayout[states.size()];
        Map<String, BedrockBlockType> blocksById = new LinkedHashMap<>();
        List<String> tableBlocks = new ArrayList<>();
        for (BlockLayout source : BlockLayout.of(states)) {
            BedrockBlockType type = constantBlocks.computeIfAbsent(source.identifier(), id -> new BedrockBlockType(null, id, VanillaTypes.UNKNOWN, false, null));
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
        Map<String, List<Integer>> variantIds = new HashMap<>();
        this.itemsByCanonical = new BedrockItemVariant[items.vanillaSize()];
        for (int i = 0; i < items.vanillaSize(); i++) {
            ItemVariant variant = items.variant(i);
            BedrockItemType type = itemsById.computeIfAbsent(variant.identifier(),
                id -> constantItems.computeIfAbsent(id, key -> new BedrockItemType(null, key, VanillaTypes.UNKNOWN, false)));
            // The base and the named variants belong to the type and have no id of their own: the type's link has it
            BedrockItemVariant handle = variant.damage() == 0 ? type.variant() : type.named(variant.damage());
            if (handle == null) {
                handle = new BedrockItemVariant(type, variant.damage(), i);
            }
            itemsByCanonical[i] = handle;
            variants.computeIfAbsent(variant.identifier(), id -> new ArrayList<>()).add(handle);
            variantIds.computeIfAbsent(variant.identifier(), id -> new ArrayList<>()).add(i);
        }
        itemsById.forEach((id, type) -> type.link(variants.get(id), variantIds.get(id).stream().mapToInt(Integer::intValue).toArray()));
        this.itemsById = itemsById;
        this.itemTypes = List.copyOf(itemsById.values());

        // What the data no longer has (deprecated constants) or does not have yet (previews) has states of its own, which
        // palettes without the block resolve through their fallbacks; an item likewise, by identifier
        constantBlocks.values().forEach(type -> {
            if (!blocksById.containsKey(type.identifier())) {
                type.linkUnavailable();
            }
        });
        constantItems.values().forEach(type -> {
            if (!itemsById.containsKey(type.identifier())) {
                type.linkAbsent(itemsById.get(type.fallback()));
            }
        });
    }

    private static Class<?> initialize(String simpleName) {
        try {
            return Class.forName(VanillaIndex.class.getPackageName() + "." + simpleName, true, VanillaIndex.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static @NonNull VanillaIndex get() {
        VanillaIndex index = Holder.INSTANCE;
        if (index == null) {
            throw new IllegalStateException("The index of the bundled constants could not be built", Holder.FAILURE);
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

    /** The vanilla type of the data, else a generated constant the data does not have, else null. */
    @Nullable BedrockBlockType blockOrConstant(String identifier) {
        BedrockBlockType type = blocksById.get(identifier);
        return type != null ? type : blockConstants.get(identifier);
    }

    /** The vanilla type of the data, else a generated constant the data does not have, else null. */
    @Nullable BedrockItemType itemOrConstant(String identifier) {
        BedrockItemType type = itemsById.get(identifier);
        return type != null ? type : itemConstants.get(identifier);
    }

    @Nullable BedrockItemType item(String identifier) {
        return itemsById.get(identifier);
    }
}
