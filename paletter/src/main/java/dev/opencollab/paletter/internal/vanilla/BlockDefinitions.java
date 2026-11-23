package dev.opencollab.paletter.internal.vanilla;

import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.BlockStates;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The vanilla block definitions of one data version, as the generated code builds them (the calls of
 * {@code internal.vanilla.blocks}: the oldest version block by block, each newer one as its changes), and the palette they
 * give ({@link #derive}). Not API.
 */
public final class BlockDefinitions {
    private final Map<String, BlockDefinition> blocks;

    public BlockDefinitions() {
        this(new HashMap<>(2048));
    }

    private BlockDefinitions(Map<String, BlockDefinition> blocks) {
        this.blocks = blocks;
    }

    /** @return a copy to change into the next version: the definitions are immutable and shared */
    public @NonNull BlockDefinitions copy() {
        return new BlockDefinitions(new HashMap<>(blocks));
    }

    /** What {@link #block} and {@link #redefine} return: sets the block's default state. */
    public final class Defined {
        private final String identifier;

        private Defined(String identifier) {
            this.identifier = identifier;
        }

        /** The default state, by one value per property in property order (a boolean as {@code true} or {@code false}). */
        public void defaultState(@NonNull Object @NonNull ... values) {
            BlockDefinitions.this.defaultState(identifier, values);
        }
    }

    /** Adds a block whose first state is its default; it must not exist yet. */
    public @NonNull Defined block(@NonNull String identifier, @NonNull StateValues @NonNull ... properties) {
        if (blocks.putIfAbsent(identifier, new BlockDefinition(identifier, properties.clone(), null)) != null) {
            throw new IllegalStateException(identifier + " is defined twice");
        }
        return new Defined(identifier);
    }

    /** Replaces a block by another definition, whose first state is its default. */
    public @NonNull Defined redefine(@NonNull String identifier, @NonNull StateValues @NonNull ... properties) {
        require(identifier);
        blocks.put(identifier, new BlockDefinition(identifier, properties.clone(), null));
        return new Defined(identifier);
    }

    public void remove(@NonNull String identifier) {
        require(identifier);
        blocks.remove(identifier);
    }

    /**
     * Inserts properties into a block's property order, at {@code position} (0: before every other, the slowest changing).
     * The default state keeps its values; an inserted property takes its first value there.
     */
    public void addProperties(@NonNull String identifier, int position, @NonNull StateValues @NonNull ... properties) {
        blocks.put(identifier, require(identifier).withProperties(position, properties));
    }

    /** Removes a property of a block; the default state keeps the values of the others. */
    public void removeProperty(@NonNull String identifier, @NonNull String property) {
        blocks.put(identifier, require(identifier).withoutProperty(property));
    }

    /** Sets a block's default state, by one value per property in property order. */
    public void defaultState(@NonNull String identifier, @NonNull Object @NonNull ... values) {
        blocks.put(identifier, require(identifier).withDefault(values));
    }

    private BlockDefinition require(String identifier) {
        BlockDefinition block = blocks.get(identifier);
        if (block == null) {
            throw new IllegalStateException(identifier + " is not defined");
        }
        return block;
    }

    public @Nullable BlockDefinition get(@NonNull String identifier) {
        return blocks.get(identifier);
    }

    public @NonNull Collection<BlockDefinition> blocks() {
        return blocks.values();
    }

    /**
     * The palette of the definitions, as Bedrock numbers it.
     *
     * @param states every state as {@code {name, states}}, in runtime id order: the blocks by the unsigned FNV-1 64 hash
     *               of their identifier ({@link BlockRegistry#nameHash}), the states of each block as the cartesian
     *               product of its values with the last property changing fastest; the keys of {@code states} sorted
     * @param defaults the default state of each block whose default is not its first state
     */
    public record Palette(@NonNull List<NbtMap> states, @NonNull Map<String, NbtMap> defaults) {
    }

    /** @return the palette of these definitions */
    public @NonNull Palette derive() {
        List<BlockDefinition> sorted = new ArrayList<>(blocks.values());
        sorted.sort((a, b) -> Long.compareUnsigned(BlockRegistry.nameHash(a.identifier()), BlockRegistry.nameHash(b.identifier())));
        for (int i = 1; i < sorted.size(); i++) {
            if (BlockRegistry.nameHash(sorted.get(i - 1).identifier()) == BlockRegistry.nameHash(sorted.get(i).identifier())) {
                throw new IllegalStateException(sorted.get(i - 1).identifier() + " and " + sorted.get(i).identifier()
                    + " have the same name hash: their order is unknown");
            }
        }
        int count = 0;
        for (BlockDefinition block : sorted) {
            count += block.stateCount();
        }
        List<NbtMap> states = new ArrayList<>(count);
        Map<String, NbtMap> defaults = new TreeMap<>();
        // The same values in other blocks (every log's pillar_axis) are one compound, as the data codec shares them
        Map<NbtMap, NbtMap> interner = new HashMap<>();
        for (BlockDefinition block : sorted) {
            StateValues[] properties = block.propertyArray();
            Integer[] byName = new Integer[properties.length];
            for (int i = 0; i < byName.length; i++) {
                byName[i] = i;
            }
            java.util.Arrays.sort(byName, (a, b) -> properties[a].name().compareTo(properties[b].name()));
            int first = states.size();
            int stateCount = block.stateCount();
            for (int state = 0; state < stateCount; state++) {
                int[] indices = block.valueIndices(state);
                NbtMapBuilder values = NbtMap.builder();
                for (int p : byName) {
                    values.put(properties[p].name(), properties[p].value(indices[p]));
                }
                states.add(BlockStates.of(block.identifier(), interner.computeIfAbsent(values.build(), compound -> compound)));
            }
            if (block.defaultState() != 0) {
                defaults.put(block.identifier(), states.get(first + block.defaultState()));
            }
        }
        return new Palette(List.copyOf(states), Map.copyOf(defaults));
    }
}
