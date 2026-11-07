package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.block.CustomBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * A Bedrock block type: an identifier with its state properties. Vanilla types are the constants of
 * {@link BedrockBlocks}; a {@link CustomBlock} has one too ({@link CustomBlock#type()}), so both are used alike:
 * <pre>{@code
 * BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.state()
 *     .with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH)
 *     .with(BedrockStates.OPEN_BIT, true)
 *     .build();
 * int runtimeId = palette.runtimeId(door);
 * }</pre>
 * Referencing a type loads nothing; its states are resolved against the newest data of {@link
 * org.geysermc.paletter.Paletter#bundled()} on first use. A vanilla constant the data does not have (deprecated
 * because Bedrock removed the block, or a preview block without the preview loaded) is not {@link #isAvailable()
 * available}: its states are those of its fallback, normally {@code minecraft:unknown}.
 *
 * @since 2.0.0
 */
public final class BedrockBlockType {
    private static final Map<CustomBlock, BedrockBlockType> CUSTOM = new WeakHashMap<>();

    private final String identifier;
    private final @Nullable String fallback;
    private final boolean custom;
    private volatile @Nullable BlockTypeLayout layout;
    private volatile boolean available;

    /** A vanilla type, linked by {@link VanillaIndex}. */
    BedrockBlockType(String identifier, String fallback) {
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = fallback;
        this.custom = false;
    }

    private BedrockBlockType(CustomBlock block) {
        this.identifier = block.identifier();
        this.fallback = null;
        this.custom = true;
        this.layout = BlockTypeLayout.custom(this, block);
        this.available = true;
    }

    /**
     * @return the type of a custom block. Equal blocks have the same type
     */
    public static @NonNull BedrockBlockType of(@NonNull CustomBlock block) {
        synchronized (CUSTOM) {
            return CUSTOM.computeIfAbsent(block, BedrockBlockType::new);
        }
    }

    /**
     * @return the vanilla type of an identifier in the newest bundled data, or null if there is none. Loads the data
     */
    public static @Nullable BedrockBlockType vanilla(@NonNull String identifier) {
        return VanillaIndex.get().block(identifier);
    }

    /**
     * @return every vanilla block type of the newest bundled data, in palette order. Loads the data
     */
    public static @NonNull List<BedrockBlockType> vanillaTypes() {
        return VanillaIndex.get().blockTypes;
    }

    void link(BlockTypeLayout layout) {
        this.layout = layout;
        this.available = true;
    }

    void linkAbsent(@Nullable BedrockBlockType fallbackType) {
        if (fallbackType == null) {
            throw new IllegalStateException("Neither " + identifier + " nor its fallback " + fallback + " is in the bundled data");
        }
        this.layout = fallbackType.layout();
        this.available = false;
    }

    BlockTypeLayout layout() {
        BlockTypeLayout layout = this.layout;
        if (layout == null) {
            VanillaIndex index = VanillaIndex.get();
            synchronized (this) {
                layout = this.layout;
                if (layout == null) {
                    // Registered after the index was built: not in the data
                    linkAbsent(index.block(fallback));
                    layout = this.layout;
                }
            }
        }
        return layout;
    }

    @Nullable String fallback() {
        return fallback;
    }

    /**
     * @return the block identifier, e.g. {@code minecraft:wooden_door}
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * @return true for the type of a {@link CustomBlock}
     */
    public boolean isCustom() {
        return custom;
    }

    /**
     * @return false for a vanilla constant the bundled data does not have: its states are those of its fallback type
     *         (see {@link #resolved()})
     */
    public boolean isAvailable() {
        if (!custom) {
            layout();
        }
        return available;
    }

    /**
     * @return this type, or for a type that is not {@link #isAvailable() available} the type its states belong to
     */
    public @NonNull BedrockBlockType resolved() {
        return layout().owner;
    }

    /**
     * @return the state properties, sorted by name for vanilla blocks and in declaration order for custom blocks
     */
    public @NonNull List<BedrockState<?>> properties() {
        return Collections.unmodifiableList(Arrays.asList(layout().properties));
    }

    /**
     * @return true if the type has a property with the state's name
     */
    public boolean hasProperty(@NonNull BedrockState<?> state) {
        return layout().property(state) != -1;
    }

    /**
     * @return the values of a state this block accepts, in its state order
     * @throws IllegalArgumentException if the block does not have the state
     */
    public <T> @NonNull List<T> values(@NonNull BedrockState<T> state) {
        BlockTypeLayout layout = layout();
        int property = requireProperty(layout, state);
        List<T> values = new ArrayList<>();
        for (Object value : layout.values[property]) {
            T converted = state.fromNbt(value);
            if (converted != null) {
                values.add(converted);
            }
        }
        return List.copyOf(values);
    }

    static int requireProperty(BlockTypeLayout layout, BedrockState<?> state) {
        int property = layout.property(state);
        if (property == -1) {
            throw new IllegalArgumentException(layout.owner.identifier + " has no state " + state.name());
        }
        return property;
    }

    /**
     * @return the default state: what the block is placed as without further information
     */
    public @NonNull BedrockBlockState defaultState() {
        return layout().defaultState();
    }

    /**
     * @return a builder starting from the default state
     */
    public @NonNull StateBuilder state() {
        return new StateBuilder(defaultState());
    }

    /**
     * @return every state, in palette (canonical id) order
     */
    public @NonNull List<BedrockBlockState> states() {
        BlockTypeLayout layout = layout();
        List<BedrockBlockState> states = new ArrayList<>(layout.count);
        for (int i = 0; i < layout.count; i++) {
            states.add(layout.byOffset(i));
        }
        return Collections.unmodifiableList(states);
    }

    public int stateCount() {
        return layout().count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        // Vanilla types are singletons; custom types are equal if their blocks have the same identifier and states
        return o instanceof BedrockBlockType other && custom && other.custom && identifier.equals(other.identifier)
            && Arrays.equals(layout().properties, other.layout().properties)
            && Arrays.deepEquals(layout().values, other.layout().values);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return identifier;
    }

    /**
     * Builds a state from the default state of a type.
     *
     * @since 2.0.0
     */
    public static final class StateBuilder {
        private BedrockBlockState state;

        private StateBuilder(BedrockBlockState state) {
            this.state = state;
        }

        public <T> @NonNull StateBuilder with(@NonNull BedrockState<T> property, @NonNull T value) {
            state = state.with(property, value);
            return this;
        }

        public @NonNull StateBuilder with(@NonNull BoolState property, boolean value) {
            state = state.with(property, value);
            return this;
        }

        public @NonNull StateBuilder with(@NonNull IntState property, int value) {
            state = state.with(property, value);
            return this;
        }

        public @NonNull BedrockBlockState build() {
            return state;
        }
    }
}
