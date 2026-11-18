package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.CanonicalBlocks;

/**
 * One state of a {@link BedrockBlockType}. A type creates each of its states once, so {@link #with} does not allocate.
 * <p>
 * Equality: {@code equals} always works. {@code ==} works too for the states of vanilla types (the constants) and of
 * registered custom blocks: a {@link org.geysermc.paletter.block.CustomBlock} owns its {@link
 * org.geysermc.paletter.block.CustomBlock#type() type} and states, and a palette it is registered in hands back exactly
 * those ({@code Palette#blockState(NbtMap, Lookup)}, ...). The states
 * of a type made by {@link BedrockBlockType#fromIdentifier(String)} for an unknown identifier are equal by value, not identical.
 * Equal custom blocks registered by different layers each own their states; those are equal, not identical.
 * <p>
 * Canonical ids come from {@link org.geysermc.paletter.Paletter#canonicalBlocks()} (vanilla states) and
 * {@link org.geysermc.paletter.Palette#runtimeId(BedrockBlockState)} (vanilla and custom states). For a vanilla
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
     * Internal: the handle of a canonical id, for {@link org.geysermc.paletter.Palette}.
     *
     * @param canonical the canonical blocks of a palette lineage
     * @param id a canonical id
     * @return the state of a canonical id of any palette lineage, or null if there is no such id or the state is not
     *         known to the constants (a vanilla state of a source that has states the bundled data lacks)
     */
    @Internal
    public static @Nullable BedrockBlockState fromCanonical(@NonNull CanonicalBlocks canonical, int id) {
        if (id < 0 || id >= canonical.size()) {
            return null;
        }
        VanillaIndex index = VanillaIndex.get();
        if (id < canonical.vanillaSize()) {
            // The ids belong to the palette's own newest version, which is not necessarily the bundled one
            return index.state(canonical.root() == index.blocks ? id : canonical.vanillaIdsIn(index.blocks)[id]);
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
     * The state as block state NBT.
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
     * Internal: the canonical id of this state in a palette's canonical blocks: the precomputed vanilla id for vanilla states of
     * palettes of the bundled newest version, an array lookup in the palette's own table for the others, the custom
     * block's id in that lineage, else a hash lookup.
     *
     * @param canonical the canonical blocks of a palette
     * @return the canonical id, or -1 if the state is not in it
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalBlocks canonical) {
        if (id >= 0) {
            CanonicalBlocks bundled = VanillaIndex.get().blocks;
            return canonical.root() == bundled ? id : canonical.vanillaIdsFrom(bundled)[id];
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
