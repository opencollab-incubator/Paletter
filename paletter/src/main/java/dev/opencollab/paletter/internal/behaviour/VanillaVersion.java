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
 * A block's values are per key: a value for all its states, a {@link Cell} or null (unknown). A block of a palette has
 * these values only if its states are the ones the entry was made for ({@link BlockSchema}). The deciding capture of a
 * value is the version's default capture of the key unless the block or item has its own (a capture or a {@link Cell} of
 * captures, for a block whose states different sources cover).
 */
@Internal
public final class VanillaVersion {
    /** The position of a key in the values of its owner, by {@link BehaviourKey#ordinal()}. */
    private static final int[] INDEX = index();

    /** The values of one block: its schema, and per key a value, a {@link Cell} or null. */
    public record BlockEntry(@NonNull String schema, @Nullable Object @NonNull [] values) {
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

    /**
     * @param schema the {@link BlockSchema} of the block in the palette asking
     * @return the values of the block, or null if this version has none for a block with these states
     */
    public @Nullable BlockEntry block(@NonNull String identifier, @NonNull String schema) {
        BlockEntry entry = blocks.get(identifier);
        return entry != null && entry.schema().equals(schema) ? entry : null;
    }

    /** @return the value of a key for a state of the block, or null if it is unknown */
    public static @Nullable Object value(@NonNull BlockEntry entry, @NonNull BehaviourKey<?> key, @NonNull NbtMap states) {
        Object value = entry.values()[index(key)];
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
