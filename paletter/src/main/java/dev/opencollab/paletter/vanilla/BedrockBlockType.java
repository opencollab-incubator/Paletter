package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * dev.opencollab.paletter.Paletter#bundled()} on first use. A vanilla constant the newest bundled release does not have
 * (deprecated because Bedrock removed the block, or a
 * {@link dev.opencollab.paletter.annotation.SincePreview @SincePreview} block) is not {@link #isAvailable() available},
 * but has states of its own, written into the constant when it was generated (no data is loaded for them): a preview
 * block those of the preview, a removed block those it last had in a bundled release (one state without properties if
 * none could be written). A palette that has the block (a preview block in a Paletter with previews, a removed one in
 * an older version) sends the block itself; any other palette resolves its states through its
 * {@link dev.opencollab.paletter.Fallbacks}, {@code minecraft:unknown} by default.
 * <p>
 * Equality: like its states (see {@link BedrockBlockState}), {@code equals} always works, and {@code ==} works for the
 * vanilla constants, the preview's blocks among them, and for the type a registered {@link CustomBlock} owns. A vanilla
 * type is equal to another by identifier, so equality never depends on the version or on
 * {@link dev.opencollab.paletter.Paletter.Builder#previews(boolean) previews}, and never loads data: in a Paletter with
 * previews, a block of the release that the preview changed has a type of its own (not {@code ==} the constant), which
 * equals the constant. A custom type is equal to one with the same identifier and properties.
 *
 * @since 1.0.0
 */
public final class BedrockBlockType {
    private final @Nullable String constantName;
    private final String identifier;
    private final @Nullable String fallback;
    private final boolean custom;
    private volatile @Nullable BlockTypeLayout layout;
    private volatile boolean available;
    private final boolean education;
    /** The states of a block the newest bundled release lacks (a preview's, a removed one), as generated; null for any other type. */
    private final VanillaTypes.@Nullable PreviewStates previewStates;

    /**
     * A vanilla type, linked by {@link VanillaIndex}.
     *
     * @param constantName the name of the generated constant that made it, or null for a type the data alone has
     * @param ownStates the states of a block the newest bundled release lacks, as generated, or null
     */
    BedrockBlockType(@Nullable String constantName, String identifier, String fallback, boolean education,
                     VanillaTypes.@Nullable PreviewStates ownStates) {
        this.constantName = constantName;
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = fallback;
        this.custom = false;
        this.education = education;
        this.previewStates = ownStates;
    }

    private BedrockBlockType(String identifier, boolean bare) {
        this.constantName = null;
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = null;
        this.custom = true;
        this.education = false;
        this.previewStates = null;
        this.layout = BlockTypeLayout.bare(this);
        this.available = true;
    }

    private BedrockBlockType(String identifier, List<BlockProperty<?>> properties, Map<String, Object> defaultStates) {
        this.constantName = null;
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = null;
        this.custom = true;
        this.education = false;
        this.previewStates = null;
        this.layout = BlockTypeLayout.custom(this, properties, defaultStates);
        this.available = true;
    }

    /**
     * Internal: plumbing of {@link CustomBlock}, which creates its type once and owns it: use
     * {@link CustomBlock#type()}.
     *
     * @param identifier the block identifier
     * @param properties the state properties, in declaration order ({@link CustomBlock#stateProperties()})
     * @param defaultStates the values of the default state; properties not listed use their first value
     * @return a new custom type. Loads no data
     */
    @Internal
    public static @NonNull BedrockBlockType custom(@NonNull String identifier, @NonNull List<BlockProperty<?>> properties,
                                                   @NonNull Map<String, Object> defaultStates) {
        return new BedrockBlockType(identifier, properties, defaultStates);
    }

    /**
     * Internal: the key of an identifier: the vanilla constant if the identifier (after renames) is a vanilla block of
     * the newest bundled data or has a generated constant, else a custom type known by its identifier only (no
     * states). Such types are not interned: equal for equal identifiers, not identical, so compare them with
     * {@code equals}, and unknown identifiers never accumulate. Use {@link #byIdentifier(String)}. The type of a
     * {@link CustomBlock} is {@link CustomBlock#type()}. Loads the data.
     *
     * @param identifier a block identifier
     * @return the type of the identifier
     */
    @Internal
    public static @NonNull BedrockBlockType fromIdentifier(@NonNull String identifier) {
        BedrockBlockType known = byIdentifier(identifier);
        return known != null ? known : new BedrockBlockType(identifier, true);
    }

    /**
     * Finds the vanilla type of an identifier, without making a type for an unknown identifier. The identifier is first
     * mapped through the renames of the bundled data, so the name an older version gave a block finds its type. Loads
     * the data.
     *
     * @param identifier a block identifier
     * @return the vanilla constant, or null if the identifier (after renames) is not a block of the newest bundled data and
     *         has no generated constant
     * @throws NullPointerException if the identifier is null
     * @since 1.0.0
     */
    public static @Nullable BedrockBlockType byIdentifier(@NonNull String identifier) {
        return byIdentifier(identifier, VanillaIndex.get().renames);
    }

    /** {@link #byIdentifier(String)} with the renames given, for a test of them. */
    static @Nullable BedrockBlockType byIdentifier(String identifier, Renames renames) {
        Objects.requireNonNull(identifier);
        VanillaIndex index = VanillaIndex.get();
        String renamed = renames.block(identifier);
        if (renamed != null && index.blockOrConstant(renamed) != null) {
            identifier = renamed;
        }
        return index.blockOrConstant(identifier);
    }

    /**
     * Plumbing of {@link dev.opencollab.paletter.Palette}.
     *
     * @param identifier the block identifier
     * @return the vanilla type of the newest bundled data with an identifier, or null
     */
    @Internal
    public static @Nullable BedrockBlockType vanillaType(@NonNull String identifier) {
        return VanillaIndex.get().block(identifier);
    }

    /**
     * Lists the vanilla block types of the newest bundled data. Loads the data.
     *
     * @return every vanilla block type, in palette order
     */
    public static @NonNull List<BedrockBlockType> vanillaTypes() {
        return VanillaIndex.get().blockTypes;
    }

    void link(BlockTypeLayout layout) {
        this.layout = layout;
        this.available = true;
    }

    /**
     * Links a vanilla type the newest bundled release does not have to states of its own: those the generator wrote
     * (a preview block's, a removed block's last ones), else a single state without properties. Palettes that lack the
     * block resolve them through their {@link dev.opencollab.paletter.Fallbacks}.
     */
    void linkUnavailable() {
        VanillaTypes.PreviewStates preview = previewStates;
        this.layout = preview == null ? BlockTypeLayout.bare(this) : BlockTypeLayout.previewConstant(this, preview);
        this.available = false;
    }

    /**
     * Finds a state of this type by its values, without a palette: the inverse of {@link BedrockBlockState#values()},
     * so {@code type.stateOf(state.values())} is {@code state}. For a vanilla type these are the states of the newest
     * bundled release (loads the data); {@link dev.opencollab.paletter.Palette#blockState(String, Map,
     * dev.opencollab.paletter.Lookup)} finds those of a palette's version.
     *
     * @param values the state values by state name: {@link Boolean}, {@link Integer} or {@link String}, every state of
     *               the block
     * @return the state with exactly these values, or null if the block has no such state: a missing or extra name, a
     *         value of another type or one the block does not have
     * @throws NullPointerException if the values are null
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState stateOf(@NonNull Map<String, ?> values) {
        BlockTypeLayout layout = layout();
        if (values.size() != layout.properties.length) {
            return null;
        }
        int combination = 0;
        for (int p = 0; p < layout.properties.length; p++) {
            int index = layout.indexOfValue(p, values.get(layout.properties[p].name()));
            if (index == -1) {
                return null;
            }
            combination += index * layout.radix[p];
        }
        return layout.byCombination()[combination];
    }

    BlockTypeLayout layout() {
        BlockTypeLayout layout = this.layout;
        if (layout == null) {
            VanillaIndex.get();
            synchronized (this) {
                layout = this.layout;
                if (layout == null) {
                    // Registered after the index was built: not in the data
                    linkUnavailable();
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
     * The block identifier.
     *
     * @return the block identifier, e.g. {@code minecraft:wooden_door}
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * The name of the generated constant of {@link BedrockBlocks} (or the preview blocks) that holds this type, preferring
     * one that is not deprecated. Loads no data.
     *
     * @return the constant's name, e.g. {@code STONE}, or null for a custom type or one without a constant
     * @since 1.0.0
     */
    public @Nullable String constantName() {
        return constantName;
    }

    /**
     * Whether this is a custom type.
     *
     * @return true for the type of a {@link CustomBlock}, and for a type made for an identifier the bundled data does
     *         not know
     */
    public boolean isCustom() {
        return custom;
    }

    /**
     * Whether BDS only offers this block to worlds with Education Edition features (the same content the generated
     * constant carries {@link dev.opencollab.paletter.annotation.Education @Education} for). Decided from BDS captures when
     * the constants were generated; always false for custom blocks and for types made for an unknown identifier.
     *
     * @return true if BDS only offers the block with Education Edition features
     * @since 1.0.0
     */
    public boolean isEducation() {
        return education;
    }

    /**
     * Whether the newest bundled release has this type. Says nothing about one palette: a palette whose newest version
     * has the block (a preview block in a Paletter with previews) sends it although it is not available.
     *
     * @return false for a vanilla constant the newest bundled release does not have: palettes without the block resolve
     *         its states through their {@link dev.opencollab.paletter.Fallbacks}
     */
    public boolean isAvailable() {
        if (!custom) {
            layout();
        }
        return available;
    }

    /**
     * The type the newest bundled release links this type to. Palettes resolve a type that is not available through
     * their {@link dev.opencollab.paletter.Fallbacks}, not through this.
     *
     * @return this type, or for a type that is not {@link #isAvailable() available} its fallback type
     */
    public @NonNull BedrockBlockType resolved() {
        if (isAvailable() || custom) {
            return this;
        }
        BedrockBlockType target = VanillaIndex.get().block(fallback);
        return target != null ? target : this;
    }

    /**
     * The state properties of the type.
     *
     * @return the state properties, sorted by name for vanilla blocks and in declaration order for custom blocks
     */
    public @NonNull List<BedrockState<?>> properties() {
        return Collections.unmodifiableList(Arrays.asList(layout().properties));
    }

    /**
     * Whether the type has a state property.
     *
     * @param state a state property; matched by name
     * @return true if the type has a property with the state's name
     */
    public boolean hasProperty(@NonNull BedrockState<?> state) {
        return layout().property(state) != -1;
    }

    /**
     * The values of a state property that this block accepts.
     *
     * @param <T> the value type of the state
     * @param state a state property of this block
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
     * The default state of the type.
     *
     * @return the default state: what the block is placed as without further information
     */
    public @NonNull BedrockBlockState defaultState() {
        return layout().defaultState();
    }

    /**
     * Starts building a state.
     *
     * @return a builder starting from the default state
     */
    public @NonNull StateBuilder state() {
        return new StateBuilder(defaultState());
    }

    /**
     * Every state of the type.
     *
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

    /**
     * The number of states of the type.
     *
     * @return the number of states
     */
    public int stateCount() {
        return layout().count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BedrockBlockType other) || custom != other.custom || !identifier.equals(other.identifier)) {
            return false;
        }
        // A vanilla type is its identifier, whatever states the version that made it has: the type a preview palette makes
        // for a block the preview changed (see PreviewBlocks) equals the constant, and this loads no data. Its states
        // tell the versions apart (BedrockBlockState#equals). Custom types carry their properties, set when they are made
        return !custom || Arrays.equals(layout().properties, other.layout().properties)
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
     * @since 1.0.0
     */
    public static final class StateBuilder {
        private BedrockBlockState state;

        private StateBuilder(BedrockBlockState state) {
            this.state = state;
        }

        /**
         * Changes the value of a state property.
         *
         * @param <T> the value type of the property
         * @param property the state property to change
         * @param value the new value
         * @return this builder
         * @throws IllegalArgumentException if the block does not have the property or the value
         */
        public <T> @NonNull StateBuilder with(@NonNull BedrockState<T> property, @NonNull T value) {
            state = state.with(property, value);
            return this;
        }

        /**
         * Changes the value of a boolean state property.
         *
         * @param property the state property to change
         * @param value the new value
         * @return this builder
         * @throws IllegalArgumentException if the block does not have the property
         */
        public @NonNull StateBuilder with(@NonNull BoolState property, boolean value) {
            state = state.with(property, value);
            return this;
        }

        /**
         * Changes the value of an integer state property.
         *
         * @param property the state property to change
         * @param value the new value
         * @return this builder
         * @throws IllegalArgumentException if the block does not have the property or the value
         */
        public @NonNull StateBuilder with(@NonNull IntState property, int value) {
            state = state.with(property, value);
            return this;
        }

        /**
         * Builds the state.
         *
         * @return the state with the values set so far
         */
        public @NonNull BedrockBlockState build() {
            return state;
        }
    }
}
