package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.internal.block.StateConditions;
import org.geysermc.paletter.internal.property.PropertySplit;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockBlockType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A block (type level) inside one palette: its {@linkplain #stateProperties() state properties}, {@linkplain #components()
 * components} and {@linkplain #origin() origin}, and its {@linkplain #states() states}, which carry the runtime ids.
 * <p>
 * In {@link BlockNetworkIds#HASHED} palettes, vanilla states are shared between the palettes of all versions, while
 * the state set of a block can differ between versions, so a state does not know its block:
 * {@link BlockRegistry#block(String)} and {@link BlockRegistry#block(RegisteredBlockState)} give the block of the
 * palette you ask.
 *
 * @since 1.0.0
 */
public final class RegisteredBlock {
    private final String identifier;
    private final Origin origin;
    private final List<RegisteredBlockState> states;
    private final @Nullable CustomBlock customBlock;
    private final @Nullable NbtMap propertyData;
    private final @Nullable NbtMap defaultStateNbt;
    private volatile @Nullable RegisteredBlockState defaultState;
    private volatile @Nullable List<BlockProperty<?>> properties;
    private volatile @Nullable BedrockBlockType key;
    private volatile @Nullable Properties propertiesCache;

    @Internal
    public RegisteredBlock(String identifier, Origin origin, List<RegisteredBlockState> states, @Nullable CustomBlock customBlock,
                    @Nullable NbtMap propertyData, @Nullable NbtMap defaultStateNbt) {
        this.identifier = identifier;
        this.origin = origin;
        this.states = List.copyOf(states);
        this.customBlock = customBlock;
        this.propertyData = propertyData;
        this.defaultStateNbt = defaultStateNbt;
        if (customBlock != null) {
            for (int i = 0; i < this.states.size(); i++) {
                this.states.get(i).bind(customBlock, i);
            }
        }
    }

    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * @return the key of this block: a vanilla type, or the type of its {@link CustomBlock}
     */
    public @NonNull BedrockBlockType key() {
        BedrockBlockType key = this.key;
        if (key == null) {
            key = customBlock != null ? BedrockBlockType.of(customBlock) : BedrockBlockType.of(identifier);
            this.key = key;
        }
        return key;
    }

    /**
     * @return {@link Origin#VANILLA}, or for custom blocks the layer id passed as {@code source} when they were added
     */
    public @NonNull Origin origin() {
        return origin;
    }

    /**
     * @return the states, in palette order
     */
    public @NonNull List<RegisteredBlockState> states() {
        return states;
    }

    public @NonNull RegisteredBlockState defaultState() {
        RegisteredBlockState result = defaultState;
        if (result == null) {
            result = states.getFirst();
            if (defaultStateNbt != null) {
                for (RegisteredBlockState state : states) {
                    if (state.getState().equals(defaultStateNbt)) {
                        result = state;
                        break;
                    }
                }
            }
            defaultState = result;
        }
        return result;
    }

    /**
     * @return the properties that make up the states. Vanilla: derived from the states, values in palette order;
     * custom: the {@link CustomBlock#stateProperties()}
     */
    public @NonNull List<BlockProperty<?>> stateProperties() {
        List<BlockProperty<?>> result = properties;
        if (result == null) {
            result = customBlock != null ? customBlock.stateProperties() : derive();
            properties = result;
        }
        return result;
    }

    private List<BlockProperty<?>> derive() {
        List<String> names = new ArrayList<>();
        List<Set<Object>> values = new ArrayList<>();
        for (RegisteredBlockState state : states) {
            for (var entry : state.states().entrySet()) {
                int index = names.indexOf(entry.getKey());
                if (index == -1) {
                    index = names.size();
                    names.add(entry.getKey());
                    values.add(new LinkedHashSet<>());
                }
                values.get(index).add(entry.getValue());
            }
        }
        List<BlockProperty<?>> result = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            List<Object> list = new ArrayList<>(values.get(i));
            BlockProperty.Type type = list.getFirst() instanceof Byte ? BlockProperty.Type.BOOLEAN
                : list.getFirst() instanceof Integer ? BlockProperty.Type.INTEGER : BlockProperty.Type.STRING;
            result.add(new BlockProperty<>(names.get(i), type, list));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return the components of the type: those of the {@link CustomBlock}, empty for vanilla blocks. See
     * {@link RegisteredBlock#components(RegisteredBlockState)} for what applies to one state
     */
    public @NonNull List<BlockComponent> components() {
        return customBlock != null ? customBlock.components() : List.of();
    }

    public @Nullable CustomBlock customBlock() {
        return customBlock;
    }

    /**
     * @return the component and wire properties of this block, from its {@link #propertyData()} (named like the bundled
     * property store: {@code component:}, {@code wire:}); empty for blocks without property data
     * @since 1.0.0
     */
    public @NonNull Properties properties() {
        Properties result = propertiesCache;
        if (result == null) {
            propertiesCache = result = PropertySplit.split(propertyData);
        }
        return result;
    }

    /**
     * @return the components in effect for a state of this block: those of the block, plus those of the permutations of
     * its {@link CustomBlock} whose condition matches the state, replacing components of the same name. Empty for
     * vanilla blocks
     */
    public @NonNull List<BlockComponent> components(@NonNull RegisteredBlockState state) {
        if (customBlock == null || customBlock.permutations().isEmpty()) {
            return components();
        }
        Map<String, BlockComponent> merged = new LinkedHashMap<>();
        components().forEach(component -> merged.put(component.name(), component));
        NbtMap states = state.states();
        for (CustomBlock.Permutation permutation : customBlock.permutations()) {
            if (StateConditions.matches(permutation.condition(), states)) {
                permutation.components().forEach(component -> merged.put(component.name(), component));
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * @return the properties in effect for a state of this block: those of the block, with the components of the
     * permutations that apply to the state set on top
     */
    public @NonNull Properties properties(@NonNull RegisteredBlockState state) {
        List<NbtMap> overlay = new ArrayList<>();
        if (propertyData != null && propertyData.get("permutations") instanceof List<?> permutations) {
            NbtMap states = state.states();
            for (Object entry : permutations) {
                if (entry instanceof NbtMap permutation
                    && StateConditions.matches(permutation.getString("condition"), states)) {
                    overlay.add(permutation.getCompound("components"));
                }
            }
        }
        return overlay.isEmpty() ? properties() : PropertySplit.split(propertyData, overlay);
    }

    /**
     * @return the block property data sent for this block in this palette (data driven vanilla blocks and custom
     * blocks), else null. A palette patched with more custom blocks has a {@code RegisteredBlock} of its own for every
     * custom block, because the numeric block ids inside the data change when blocks are added
     */
    public @Nullable NbtMap propertyData() {
        return propertyData;
    }

    @Override
    public String toString() {
        return "RegisteredBlock{" + identifier + ", " + origin + ", " + states.size() + " states}";
    }
}
