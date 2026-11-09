package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A block (type level) inside one palette: its {@linkplain #properties() properties}, {@linkplain #components()
 * components} and {@linkplain #origin() origin}, and its {@linkplain #states() states}, which carry the runtime ids.
 * <p>
 * In {@link BlockNetworkIds#HASHED} palettes, vanilla states are shared between the palettes of all versions, while
 * the state set of a block can differ between versions. {@link RegisteredBlockState#block()} of such a state is the
 * block of the first palette that registered it; {@link BlockRegistry#block(String)} always gives the block of
 * the palette you ask.
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
    private volatile @Nullable List<BedrockBlockState> customKeys;

    RegisteredBlock(String identifier, Origin origin, List<RegisteredBlockState> states, @Nullable CustomBlock customBlock,
                    @Nullable NbtMap propertyData, @Nullable NbtMap defaultStateNbt) {
        this.identifier = identifier;
        this.origin = origin;
        this.states = List.copyOf(states);
        this.customBlock = customBlock;
        this.propertyData = propertyData;
        this.defaultStateNbt = defaultStateNbt;
        for (int i = 0; i < this.states.size(); i++) {
            this.states.get(i).bind(this, i);
        }
    }

    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * @return the key of this block: a vanilla type, or the type of its {@link CustomBlock}
     * @throws IllegalStateException if this is a vanilla block that is not in the newest bundled data
     */
    public @NonNull BedrockBlockType key() {
        BedrockBlockType key = this.key;
        if (key == null) {
            key = customBlock != null ? BedrockBlockType.of(customBlock) : BedrockBlockType.vanilla(identifier);
            if (key == null) {
                throw new IllegalStateException(identifier + " is not in the newest bundled data, so it has no key");
            }
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
    public @NonNull List<BlockProperty<?>> properties() {
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
     * {@link RegisteredBlockState#components()} for what applies to one state
     */
    public @NonNull List<BlockComponent> components() {
        return customBlock != null ? customBlock.components() : List.of();
    }

    public @Nullable CustomBlock customBlock() {
        return customBlock;
    }

    /**
     * @return the block property data sent for this block (data driven vanilla blocks and custom blocks), else null.
     * In a palette patched with more custom blocks, ids inside the data of earlier custom blocks can be older than
     * what {@link BlockRegistry#blockProperties()} sends; that list is authoritative
     */
    public @Nullable NbtMap propertyData() {
        return propertyData;
    }

    @Nullable BedrockBlockState customKey(int index) {
        List<BedrockBlockState> keys = customKeys;
        if (keys == null) {
            customKeys = keys = key().states();
        }
        return index < keys.size() ? keys.get(index) : null;
    }

    @Override
    public String toString() {
        return "RegisteredBlock{" + identifier + ", " + origin + ", " + states.size() + " states}";
    }
}
