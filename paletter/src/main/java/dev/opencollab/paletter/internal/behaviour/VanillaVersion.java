package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;
import java.util.Map;

/**
 * The bundled behaviour values of the vanilla blocks and items of one data version, and the captures that decided them.
 * Immutable. Built by {@link VanillaVersionBuilder} from the generated data: the oldest version from its base, each newer
 * one from the version before it and its changes, so a block that did not change is the same {@link BlockEntry} in both.
 * <p>
 * A block's values are per key: a value for all its states, a {@link Cell} or null (unknown). A state of a palette has
 * these values only if the entry covers it ({@link BlockEntry#covers}: the same properties and known values), which is
 * what looking it up by state hash in the version's palette would give. The deciding capture of a
 * value is the version's default capture of the key unless the block or item has its own (a capture or a {@link Cell} of
 * captures, for a block whose states different sources cover).
 */
@Internal
public final class VanillaVersion {
    /** The position of a key in the values of its owner, by {@link BehaviourKey#ordinal()}. */
    private static final int[] INDEX = index();

    /**
     * The values of one block: its states (the properties and the values of each, from its {@link BlockSchema}), and per
     * key a value, a {@link Cell} or null. Not changed once its version is built.
     */
    public static final class BlockEntry {
        private final String schema;
        private final String[] properties;
        private final Object[][] domains;
        final @Nullable Object[] values;

        BlockEntry(String schema, Object[] values) {
            this(schema, BlockSchema.parse(schema), values);
        }

        /** @param parsed the schema read back, shared by the blocks of the same schema */
        BlockEntry(String schema, BlockSchema.Parsed parsed, Object[] values) {
            this.schema = schema;
            this.properties = parsed.properties();
            this.domains = parsed.domains();
            this.values = values;
        }

        /** @return the same states with other values */
        BlockEntry with(Object[] values) {
            return new BlockEntry(schema, new BlockSchema.Parsed(properties, domains), values);
        }

        public @NonNull String schema() {
            return schema;
        }

        /**
         * @param states the {@code states} compound of a block state of a palette
         * @return true if it is one of this entry's states: the same properties, each with one of the entry's values (what
         * the same state hash means, as every combination of the values is a state)
         */
        public boolean covers(@NonNull NbtMap states) {
            if (states.size() != properties.length) {
                return false;
            }
            for (int i = 0; i < properties.length; i++) {
                Object value = BlockSchema.value(states.get(properties[i]));
                boolean found = false;
                for (Object candidate : domains[i]) {
                    found |= candidate.equals(value);
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
    }

    private final DataVersion version;
    final Map<String, BlockEntry> blocks;
    final Map<String, Object[]> items;
    final Object[] blockSourceDefaults;
    final Object[] itemSourceDefaults;
    final Map<String, Object[]> blockSources;
    final Map<String, Object[]> itemSources;
    /** What a block or item added in the next version starts from. */
    final Object[] blockDefaults;
    final Object[] itemDefaults;

    VanillaVersion(DataVersion version, Map<String, BlockEntry> blocks, Map<String, Object[]> items, Object[] blockSourceDefaults,
                   Object[] itemSourceDefaults, Map<String, Object[]> blockSources, Map<String, Object[]> itemSources,
                   Object[] blockDefaults, Object[] itemDefaults) {
        this.version = version;
        this.blocks = blocks;
        this.items = items;
        this.blockSourceDefaults = blockSourceDefaults;
        this.itemSourceDefaults = itemSourceDefaults;
        this.blockSources = blockSources;
        this.itemSources = itemSources;
        this.blockDefaults = blockDefaults;
        this.itemDefaults = itemDefaults;
    }

    private static int[] index() {
        List<BehaviourKey<?>> blocks = BlockBehaviour.keys();
        List<BehaviourKey<?>> items = ItemBehaviour.keys();
        int[] index = new int[blocks.size() + items.size()];
        for (int i = 0; i < blocks.size(); i++) {
            index[blocks.get(i).ordinal()] = i;
        }
        for (int i = 0; i < items.size(); i++) {
            index[items.get(i).ordinal()] = i;
        }
        return index;
    }

    /** @return the position of a key among the keys of its owner ({@link BlockBehaviour#keys()} or {@link ItemBehaviour#keys()}) */
    public static int index(@NonNull BehaviourKey<?> key) {
        return INDEX[key.ordinal()];
    }

    public @NonNull DataVersion version() {
        return version;
    }

    /** @return the values of a block, or null if this version has none; check {@link BlockEntry#covers} for each state */
    public @Nullable BlockEntry block(@NonNull String identifier) {
        return blocks.get(identifier);
    }

    /** @return the value of a key for a state of the block (one it {@linkplain BlockEntry#covers covers}), or null if it is unknown */
    public static @Nullable Object value(@NonNull BlockEntry entry, @NonNull BehaviourKey<?> key, @NonNull NbtMap states) {
        Object value = entry.values[index(key)];
        return value instanceof Cell cell ? cell.get(states) : value;
    }

    /** @return the capture that decided the (known) value of a key for a state of a block */
    public @Nullable Capture capture(@NonNull String identifier, @NonNull BehaviourKey<?> key, @NonNull NbtMap states) {
        return decided(blockSources.get(identifier), blockSourceDefaults, key, states);
    }

    /** @return the value of a key for an item, or null if it is unknown */
    public @Nullable Object item(@NonNull String identifier, @NonNull BehaviourKey<?> key) {
        Object[] values = items.get(identifier);
        return values == null ? null : values[index(key)];
    }

    /** @return the capture that decided the (known) value of a key for an item */
    public @Nullable Capture itemCapture(@NonNull String identifier, @NonNull BehaviourKey<?> key) {
        return decided(itemSources.get(identifier), itemSourceDefaults, key, NbtMap.EMPTY);
    }

    private static @Nullable Capture decided(Object @Nullable [] own, Object[] defaults, BehaviourKey<?> key, NbtMap states) {
        int index = index(key);
        Object capture = own == null || own[index] == null ? defaults[index] : own[index];
        return (Capture) (capture instanceof Cell cell ? cell.get(states) : capture);
    }

    @Override
    public String toString() {
        return "VanillaVersion{" + version.name() + ", " + blocks.size() + " blocks, " + items.size() + " items}";
    }
}
