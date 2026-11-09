package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.block.CustomBlock;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.CanonicalBlocks;

/**
 * One state of a {@link BedrockBlockType}. There is one instance per state, so {@link #with} does not allocate, and
 * states can be compared with {@code ==}.
 * <p>
 * Canonical ids come from {@link org.geysermc.paletter.Paletter#canonicalBlock(BedrockBlockState)} (vanilla states) and
 * {@link org.geysermc.paletter.Palette#canonicalBlock(BedrockBlockState)} (vanilla and custom states). For a vanilla
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

    BedrockBlockState(BlockTypeLayout layout, int combination, int id) {
        this.layout = layout;
        this.combination = combination;
        this.id = id;
    }

    /**
     * @return the state of a vanilla canonical id of {@link org.geysermc.paletter.Paletter#bundled()}, or null if
     *         there is no such id. Loads the data
     */
    public static @Nullable BedrockBlockState vanilla(int canonical) {
        return VanillaIndex.get().state(canonical);
    }

    /**
     * @param state a block state of the newest bundled version as {@code {name, states}}
     * @return its state object, or null if the newest version has no such state. Loads the data
     */
    public static @Nullable BedrockBlockState vanilla(@NonNull NbtMap state) {
        VanillaIndex index = VanillaIndex.get();
        return index.state(index.blocks.index(state));
    }

    /**
     * Plumbing of {@link org.geysermc.paletter.Palette}: the handle of a canonical id.
     *
     * @return the state of a canonical id of any palette lineage, or null if there is no such id or the state is not
     *         known to the constants (vanilla states of a source that is not the bundled one)
     */
    @Internal
    public static @Nullable BedrockBlockState fromCanonical(@NonNull CanonicalBlocks canonical, int id) {
        if (id < 0 || id >= canonical.size()) {
            return null;
        }
        VanillaIndex index = VanillaIndex.get();
        if (id < canonical.vanillaSize() && canonical.extendsOrIs(index.blocks)) {
            return index.state(id);
        }
        CustomBlock block = canonical.customBlockAt(id);
        if (block == null) {
            return vanilla(canonical.state(id));
        }
        int base = canonical.customBase(block.identifier(), block.stateCount());
        if (base == -1) {
            return null;
        }
        BlockTypeLayout layout = BedrockBlockType.of(block).layout();
        return layout.byOffset(id - base);
    }

    public @NonNull BedrockBlockType type() {
        return layout.owner;
    }

    /**
     * @return the value of a state
     * @throws IllegalArgumentException if the block does not have the state
     * @throws IllegalStateException if the value has no constant in the state's enum (data newer than the constants)
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

    public @NonNull BedrockBlockState with(@NonNull BoolState state, boolean value) {
        return with((BedrockState<Boolean>) state, value);
    }

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
     * The canonical id of this state in a palette's canonical blocks: the precomputed vanilla id for vanilla states of
     * palettes of {@link org.geysermc.paletter.Paletter#bundled()}, the custom block's id in that lineage, else a hash
     * lookup.
     *
     * @return the canonical id, or -1 if the state is not in it
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalBlocks canonical) {
        if (id >= 0) {
            return canonical.root() == VanillaIndex.get().blocks ? id : canonical.index(toNbt());
        }
        int base = canonical.customBase(type().identifier(), layout.count);
        return base == -1 ? canonical.index(toNbt()) : base + combination;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockBlockState other && combination == other.combination && type().equals(other.type());
    }

    @Override
    public int hashCode() {
        return 31 * type().hashCode() + combination;
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
