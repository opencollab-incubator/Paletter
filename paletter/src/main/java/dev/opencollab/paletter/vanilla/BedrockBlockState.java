package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.CanonicalBlocks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One state of a {@link BedrockBlockType}. A type creates each of its states once, so {@link #with} does not allocate.
 * <p>
 * Equality: {@code equals} always works. {@code ==} works too for the states of vanilla types (the constants) and of
 * registered custom blocks: a {@link dev.opencollab.paletter.block.CustomBlock} owns its {@link
 * dev.opencollab.paletter.block.CustomBlock#type() type} and states, and a palette it is registered in hands back exactly
 * those ({@code Palette#blockState(NbtMap, Lookup)}, ...). Two states are equal when their types are equal and they
 * have the same {@link #values()}, whichever version or palette made them: a state a preview palette decodes for a block
 * the preview changed equals the constant's state with the same values (it is not {@code ==} it). The states
 * of a type made for an identifier the bundled data does not know are equal by value, not identical.
 * Equal custom blocks registered by different layers each own their states; those are equal, not identical.
 * <p>
 * Canonical ids come from the canonical block ids of the {@code Paletter} (vanilla states) and
 * {@link dev.opencollab.paletter.Palette#runtimeId(BedrockBlockState)} (vanilla and custom states). For a vanilla
 * state both are an addition, not a hash lookup: the first state of its block plus the sum of each value's index times
 * the stride of its property, computed once (Bedrock enumerates every block's states as the full product of its values;
 * blocks that are not get a lookup table). Custom block states have canonical ids per layer lineage only.
 *
 * @since 1.0.0
 */
public final class BedrockBlockState {
    private final BlockTypeLayout layout;
    private final int combination;
    private final int id;
    private volatile @Nullable NbtMap nbt;
    private volatile @Nullable Map<String, Object> values;
    private int hash;

    BedrockBlockState(BlockTypeLayout layout, int combination, int id) {
        this.layout = layout;
        this.combination = combination;
        this.id = id;
    }

    /**
     * The handle of a canonical id, for {@link dev.opencollab.paletter.Palette}.
     *
     * @param canonical the canonical blocks of a palette
     * @param id the canonical id
     * @return the state of a canonical id of any palette lineage, or null if there is no such id
     */
    static @Nullable BedrockBlockState fromCanonical(@NonNull CanonicalBlocks canonical, int id) {
        if (id < 0 || id >= canonical.size()) {
            return null;
        }
        VanillaIndex index = VanillaIndex.get();
        if (id < canonical.vanillaSize()) {
            // The ids belong to the palette's own newest version, which is not necessarily the bundled one
            if (canonical.root() == index.blocks) {
                return index.state(id);
            }
            // Blocks a preview added or changed: the constants are linked to the release, so the palette's own table makes them
            BedrockBlockState preview = PreviewBlocks.state(canonical.root(), id);
            return preview != null ? preview : index.state(canonical.vanillaIdsIn(index.blocks)[id]);
        }
        BedrockBlockType type = canonical.customTypeAt(id);
        return type == null ? null : type.layout().byOffset(id - canonical.customStartAt(id));
    }

    /**
     * The type of the state.
     *
     * @return the block type this is a state of
     */
    public @NonNull BedrockBlockType type() {
        return layout.owner;
    }

    /**
     * Reads the value of a state property.
     *
     * @param <T> the value type of the state
     * @param state the state property to read
     * @return the value of a state
     * @throws IllegalArgumentException if the block does not have the state
     * @throws IllegalStateException if the value has no constant in the state's enum (data newer than the constants);
     *         {@link #raw} reads such a value
     */
    public <T> @NonNull T get(@NonNull BedrockState<T> state) {
        int property = BedrockBlockType.requireProperty(layout, state);
        Object nbt = layout.values[property][layout.valueOf(combination, property)];
        T value = state.fromNbt(nbt);
        if (value == null) {
            throw new IllegalStateException(type() + "'s " + state.name() + " is " + nbt + ", which " + state.getClass().getSimpleName()
                + " " + state.name() + " has no value for");
        }
        return value;
    }

    /**
     * Reads the value of a state property as Bedrock sends it, without converting it to the state's Java type. Unlike
     * {@link #get}, it never throws: a value of a newer Bedrock version that a state enum has no constant for is still
     * read here.
     *
     * @param state the state property to read
     * @return the value as in {@link #values()} ({@link Boolean}, {@link Integer} or {@link String}), or null if the block
     *         does not have the state
     */
    public @Nullable Object raw(@NonNull BedrockState<?> state) {
        int property = layout.property(state);
        if (property == -1) {
            return null;
        }
        Object value = layout.values[property][layout.valueOf(combination, property)];
        return value instanceof Byte b ? (Object) (b != 0) : value;
    }

    /**
     * Changes the value of a state property.
     *
     * @param <T> the value type of the state
     * @param state the state property to change
     * @param value the new value
     * @return this state with one value changed
     * @throws IllegalArgumentException if the block does not have the state or the value
     */
    public <T> @NonNull BedrockBlockState with(@NonNull BedrockState<T> state, @NonNull T value) {
        int property = BedrockBlockType.requireProperty(layout, state);
        int index = layout.valueIndex(property, state.toNbt(value));
        if (index == -1) {
            throw noValue(property, value);
        }
        return with(property, index);
    }

    /**
     * Changes the value of a boolean state property.
     *
     * @param state the state property to change
     * @param value the new value
     * @return this state with one value changed
     * @throws IllegalArgumentException if the block does not have the state
     */
    public @NonNull BedrockBlockState with(@NonNull BoolState state, boolean value) {
        return with((BedrockState<Boolean>) state, value);
    }

    /**
     * Changes the value of an integer state property.
     *
     * @param state the state property to change
     * @param value the new value
     * @return this state with one value changed
     * @throws IllegalArgumentException if the block does not have the state or the value
     */
    public @NonNull BedrockBlockState with(@NonNull IntState state, int value) {
        int property = BedrockBlockType.requireProperty(layout, state);
        int index = layout.valueIndex(property, value);
        if (index == -1) {
            throw noValue(property, value);
        }
        return with(property, index);
    }

    private IllegalArgumentException noValue(int property, Object value) {
        return new IllegalArgumentException(type() + " has no " + layout.properties[property].name() + " value " + value);
    }

    private BedrockBlockState with(int property, int index) {
        int changed = combination + (index - layout.valueOf(combination, property)) * layout.radix[property];
        BedrockBlockState state = layout.byCombination()[changed];
        if (state == null) {
            throw new IllegalArgumentException(type() + " has no state " + layout.nbtStates(changed));
        }
        return state;
    }

    /**
     * Returns the values of the state by state name, in the order of the block's states in Bedrock Dedicated Server's
     * data. Together with {@code type().identifier()} they find this state again:
     * {@code palette.blockState(identifier, values(), Lookup.EXACT)}
     *
     * @return the values: {@link Boolean}, {@link Integer} or {@link String}; unmodifiable
     */
    public @NonNull Map<String, Object> values() {
        Map<String, Object> values = this.values;
        if (values == null) {
            values = new LinkedHashMap<>();
            for (int p = 0; p < layout.properties.length; p++) {
                Object value = layout.values[p][layout.valueOf(combination, p)];
                values.put(layout.properties[p].name(), value instanceof Byte b ? (Object) (b != 0) : value);
            }
            this.values = values = Collections.unmodifiableMap(values);
        }
        return values;
    }

    /**
     * Returns the state as NBT.
     *
     * @return the state as {@code {name, states}}
     */
    public @NonNull NbtMap toNbt() {
        NbtMap nbt = this.nbt;
        if (nbt == null) {
            nbt = id >= 0 ? VanillaIndex.get().blocks.state(id) : BlockStates.of(type().identifier(), layout.nbtStates(combination));
            this.nbt = nbt;
        }
        return nbt;
    }

    /**
     * The canonical id of this state in a palette's canonical blocks: the precomputed vanilla id for vanilla
     * states of
     * palettes of the bundled newest version, an array lookup in the palette's own table for the others, the custom
     * block's id in that lineage, else a hash lookup.
     *
     * @param canonical the canonical blocks of a palette
     * @return the canonical id, or -1 if the state is not in it
     */
    int canonicalIn(@NonNull CanonicalBlocks canonical) {
        if (id >= 0) {
            // A state of the bundled constants (only those have an id). This is checked before the root below because no bundled
            // constant has a root of its own (BundledLayoutRootTest): only preview constants do, and they have no id.
            CanonicalBlocks bundled = VanillaIndex.get().blocks;
            return canonical.root() == bundled ? id : canonical.vanillaIdsFrom(bundled)[id];
        }
        if (layout.root != null && canonical.root() == layout.root) {
            return layout.rootBase + layout.offset(combination);
        }
        BedrockBlockType type = layout.owner;
        if (!type.isCustom() && !type.isAvailable()) {
            // A constant the bundled release lacks: not in its palettes, which resolve it through their fallbacks. A
            // root that has the block (the preview's) knows where
            CanonicalBlocks root = canonical.root();
            if (root == VanillaIndex.get().blocks) {
                return -1;
            }
            int first = PreviewBlocks.first(root, type);
            if (first >= 0) {
                return first + layout.offset(combination);
            }
        }
        int base = canonical.customBase(type.identifier(), layout.count);
        return base == -1 ? canonical.index(toNbt()) : base + combination;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BedrockBlockState other)) {
            return false;
        }
        if (layout == other.layout) {
            return combination == other.combination;
        }
        // Another layout of the same block (the one a preview palette makes for a block the preview changed, or an equal
        // custom block of another layer): equal by values, so equality never depends on the version that made the state
        return type().equals(other.type()) && values().equals(other.values());
    }

    @Override
    public int hashCode() {
        int hash = this.hash;
        if (hash == 0) {
            // From the values, not the combination: states of different layouts of one block are equal by their values
            hash = 31 * type().hashCode() + values().hashCode();
            this.hash = hash;
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(type().identifier());
        if (layout.properties.length > 0) {
            builder.append('[');
            for (int p = 0; p < layout.properties.length; p++) {
                if (p > 0) {
                    builder.append(',');
                }
                builder.append(layout.properties[p].name()).append('=').append(layout.values[p][layout.valueOf(combination, p)]);
            }
            builder.append(']');
        }
        return builder.toString();
    }
}
