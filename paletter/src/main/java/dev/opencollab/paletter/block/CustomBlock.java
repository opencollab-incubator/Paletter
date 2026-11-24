package dev.opencollab.paletter.block;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.block.StateConditions;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import dev.opencollab.paletter.block.component.BlockComponent;
import dev.opencollab.paletter.block.component.BlockTrait;
import dev.opencollab.paletter.block.component.BlockMenuCategory;
import dev.opencollab.paletter.block.component.BlockTags;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.access.BlockAccess;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.BlockPlacer;
import dev.opencollab.paletter.internal.component.Check;
import dev.opencollab.paletter.internal.component.TraitStates;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A custom (data driven) block: an add-on JSON {@code minecraft:block} in Java. Registering one in a layer generates its
 * states, its block property data, its block item and its creative entry; runtime ids and palette ordering are handled
 * by the palette.
 * <pre>{@code
 * CustomBlock lamp = CustomBlock.builder("example:lamp")
 *     .property(BlockProperty.bool("example:lit"))
 *     .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
 *     .component(Geometry.of("minecraft:geometry.full_block"))
 *     .component(MaterialInstances.builder().all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("lamp").build())).build())
 *     .permutation("q.block_state('example:lit')", LightEmission.of(15))
 *     .menuCategory(CreativeCategory.CONSTRUCTION)
 *     .build();
 * }</pre>
 * <p>
 * An instance owns its {@link #type()} (with its states) and its {@link #itemType()}: the palette hands back exactly those
 * for the block, so they can be compared with {@code ==}. Equal blocks (same identifier and definition) are
 * {@code equals}, but each has types of its own, see {@link #type()}.
 *
 * @since 1.0.0
 */
public final class CustomBlock {
    static {
        Access.setBlock(new BlockAccess() {
            @Override
            public List<BlockProperty<?>> stateProperties(CustomBlock block, int protocol) {
                return block.stateProperties(protocol);
            }

            @Override
            public List<NbtMap> states(CustomBlock block, int protocol) {
                return block.states(protocol);
            }

            @Override
            public NbtMap defaultState(CustomBlock block, int protocol) {
                return block.defaultState(protocol);
            }

            @Override
            public BlockProperty<?> property(String name, BlockProperty.Type type, List<?> values) {
                return BlockProperty.of(name, type, values);
            }
        });
    }

    private final String identifier;
    private final List<BlockProperty<?>> properties;
    private final List<BlockTrait> traits;
    private final List<BlockComponent> components;
    private final List<Permutation> permutations;
    private final @Nullable BlockMenuCategory menuCategory;
    private final BlockItem item;
    private final CreativePlacement creative;
    private final Map<String, Object> defaultStates;
    private final BedrockBlockType type;
    private final BedrockItemType itemType;

    /**
     * @param identifier the block identifier (description {@code identifier})
     * @param properties the state properties (description {@code states}), in declaration order
     * @param traits the traits (description {@code traits}); the states they add are part of {@link #states()}
     * @param components the base components (one per component name)
     * @param permutations components applied when a Molang condition on the state holds
     * @param menuCategory the description's {@code menu_category}, or null: not in the creative inventory
     * @param item how the block item is registered
     * @param creative the position of the block item within its category and group
     * @param defaultStates state values of the default state; properties not listed use their first value
     */
    private CustomBlock(@NonNull String identifier, @NonNull List<BlockProperty<?>> properties, @NonNull List<BlockTrait> traits,
                       @NonNull List<BlockComponent> components, @NonNull List<Permutation> permutations,
                       @Nullable BlockMenuCategory menuCategory, @NonNull BlockItem item, @NonNull CreativePlacement creative,
                       @NonNull Map<String, Object> defaultStates) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.properties = List.copyOf(properties);
        this.traits = List.copyOf(traits);
        this.components = List.copyOf(components);
        this.permutations = List.copyOf(permutations);
        this.menuCategory = menuCategory;
        this.item = item instanceof BlockItem.Custom custom ? custom.resolve(identifier) : Objects.requireNonNull(item, "item");
        this.creative = creative;
        List<BlockProperty<?>> stateProperties = stateProperties(this.properties, this.traits);
        long combinations = 1;
        for (BlockProperty<?> property : stateProperties) {
            combinations *= property.values().size();
            if (combinations > MAX_STATES) {
                throw new IllegalArgumentException(identifier + " has more than 2^24 state combinations");
            }
        }
        for (Permutation permutation : this.permutations) {
            for (String state : StateConditions.stateNames(permutation.condition())) {
                if (stateProperties.stream().noneMatch(p -> p.name().equals(state))) {
                    throw new IllegalArgumentException(identifier + "'s permutation '" + permutation.condition() + "' reads the state "
                        + state + ", which the block does not have");
                }
            }
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaultStates.entrySet()) {
            BlockProperty<?> property = stateProperties.stream().filter(p -> p.name().equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(identifier + " has no property " + entry.getKey()));
            Object value = valueOf(property, entry.getValue());
            if (!property.values().contains(value)) {
                throw new IllegalArgumentException(entry.getValue() + " is not a value of " + identifier + "'s " + entry.getKey());
            }
            converted.put(entry.getKey(), value);
        }
        this.defaultStates = Map.copyOf(converted);
        this.type = BedrockBlockType.custom(identifier, stateProperties, this.defaultStates);
        this.itemType = this.item instanceof BlockItem.Custom custom ? custom.item().type() : BedrockItemType.custom(identifier);
    }

    /**
     * The block identifier.
     *
     * @return the block identifier (description {@code identifier})
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * The state properties declared by the block.
     *
     * @return the state properties (description {@code states}), in declaration order
     */
    public @NonNull List<BlockProperty<?>> properties() {
        return properties;
    }

    /**
     * The traits of the block.
     *
     * @return the traits (description {@code traits}); the states they add are part of {@link #states()}
     */
    public @NonNull List<BlockTrait> traits() {
        return traits;
    }

    /**
     * The base components of the block.
     *
     * @return the base components (one per component name)
     */
    public @NonNull List<BlockComponent> components() {
        return components;
    }

    /**
     * The permutations of the block.
     *
     * @return components applied when a Molang condition on the state holds
     */
    public @NonNull List<Permutation> permutations() {
        return permutations;
    }

    /**
     * Returns the description's {@code menu_category}, which alone decides whether the block item is in the creative
     * inventory and in which category and group, as in BDS.
     *
     * @return the menu category; null or category {@code none}: the block item is not in the creative inventory
     */
    public @Nullable BlockMenuCategory menuCategory() {
        return menuCategory;
    }

    /**
     * The registration of the block item.
     *
     * @return how the block item is registered
     */
    public @NonNull BlockItem item() {
        return item;
    }

    /**
     * Returns where the block item goes within the category and group of the {@link #menuCategory()}.
     *
     * @return the placement, {@link CreativePlacement#DEFAULT} if not set
     */
    public @NonNull CreativePlacement creativePlacement() {
        return creative;
    }

    /**
     * The default state values.
     *
     * @return state values of the default state; properties not listed use their first value
     */
    public @NonNull Map<String, Object> defaultStates() {
        return defaultStates;
    }

    /**
     * The block tags: the {@link BlockTags} component's, which {@link Builder#tag} adds to.
     *
     * @return block tags, queryable with Molang (sent as the block's {@code blockTags}); empty without the component
     */
    public @NonNull List<String> tags() {
        BlockTags tags = component(BlockTags.class);
        return tags == null ? List.of() : tags.tags();
    }

    /**
     * Starts a builder with everything of this block, to make a changed copy. The copy has types of its own.
     *
     * @return a builder with this block's definition
     */
    public @NonNull Builder toBuilder() {
        Builder builder = new Builder(identifier);
        builder.properties.addAll(properties);
        builder.traits.addAll(traits);
        builder.components.addAll(components);
        builder.permutations.addAll(permutations);
        builder.menuCategory = menuCategory;
        builder.item = item;
        builder.creative = creative;
        builder.defaultStates.putAll(defaultStates);
        return builder;
    }

    /** The most state combinations a block may have, like vanilla blocks. */
    private static final int MAX_STATES = 1 << 24;

    private static List<BlockProperty<?>> stateProperties(List<BlockProperty<?>> properties, List<BlockTrait> traits) {
        if (traits.isEmpty()) {
            return properties;
        }
        List<BlockProperty<?>> all = new ArrayList<>(properties);
        all.addAll(TraitStates.of(traits));
        return all;
    }

    /** A default state value as the property holds it: a boolean property takes the integers 0 and 1 too. */
    private static Object valueOf(BlockProperty<?> property, Object value) {
        if (property.type() == BlockProperty.Type.BOOLEAN && value instanceof Integer number && (number == 0 || number == 1)) {
            return number == 1;
        }
        return value;
    }

    private static void checkUnique(String identifier, List<BlockComponent> components) {
        for (int i = 0; i < components.size(); i++) {
            for (int j = i + 1; j < components.size(); j++) {
                if (components.get(i).name().equals(components.get(j).name())) {
                    throw new IllegalArgumentException(identifier + " has two " + components.get(i).name() + " components");
                }
            }
        }
    }

    /**
     * An entry of {@code permutations}: components applied when a Molang condition on the state holds. A final class, not a
     * record, so that it can grow with Bedrock (docs/api.md, "Evolution").
     *
     * @since 1.0.0
     */
    public static final class Permutation {
        private final String condition;
        private final List<BlockComponent> components;

        private Permutation(String condition, List<? extends BlockComponent> components) {
            this.condition = Objects.requireNonNull(condition, "condition");
            this.components = List.copyOf(components);
            checkUnique("permutation '" + condition + "'", this.components);
            for (BlockComponent component : this.components) {
                if (component instanceof BlockTags) {
                    // BDS sends minecraft:tags as the block's top level blockTags; a permutation has no such field
                    throw new IllegalArgumentException("permutation '" + condition + "' has minecraft:tags: block tags belong"
                        + " to the block, not to a permutation");
                }
            }
        }

        /**
         * Creates a permutation.
         *
         * @param condition a Molang condition, e.g. {@code q.block_state('example:lit')}
         * @param components the components applied when it holds, one per component name
         * @return the permutation
         * @throws NullPointerException if an argument or a component is null
         * @throws IllegalArgumentException if two components have the same name, or one is {@code minecraft:tags}
         */
        public static @NonNull Permutation of(@NonNull String condition, @NonNull List<? extends BlockComponent> components) {
            return new Permutation(condition, components);
        }

        /**
         * Creates a permutation.
         *
         * @param condition a Molang condition, e.g. {@code q.block_state('example:lit')}
         * @param first the first component applied when it holds
         * @param rest the other components, one per component name
         * @return the permutation
         * @throws NullPointerException if an argument or a component is null
         * @throws IllegalArgumentException if two components have the same name, or one is {@code minecraft:tags}
         */
        public static @NonNull Permutation of(@NonNull String condition, @NonNull BlockComponent first, @NonNull BlockComponent... rest) {
            List<BlockComponent> components = new ArrayList<>(rest.length + 1);
            components.add(first);
            components.addAll(Arrays.asList(rest));
            return new Permutation(condition, components);
        }

        /**
         * Returns the condition.
         *
         * @return a Molang condition, e.g. {@code q.block_state('example:lit') == 1}
         */
        public @NonNull String condition() {
            return condition;
        }

        /**
         * Returns the components.
         *
         * @return the components applied when the condition holds, in the order they were given
         */
        public @NonNull List<BlockComponent> components() {
            return components;
        }

        /**
         * @return true for a permutation with the same condition and components, in any order
         */
        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Permutation other && condition.equals(other.condition) && sameComponents(components, other.components);
        }

        @Override
        public int hashCode() {
            return 31 * condition.hashCode() + Set.copyOf(components).hashCode();
        }

        @Override
        public String toString() {
            return "Permutation[condition=" + condition + ", components=" + components + "]";
        }
    }

    /** Components are one per name: two lists are the same components in any order. */
    static boolean sameComponents(List<?> a, List<?> b) {
        return a.size() == b.size() && Set.copyOf(a).equals(Set.copyOf(b));
    }

    /**
     * How the item of a custom block is registered: {@link #NONE}, {@link #SIMPLE} (the default) or
     * {@link #custom(UnaryOperator) custom}, an item of its own that the block owns. Closed: Paletter defines the variants.
     *
     * @since 1.0.0
     */
    public sealed interface BlockItem permits BlockItem.None, BlockItem.Simple, BlockItem.Custom {
        /**
         * No item; the block cannot be held.
         */
        BlockItem NONE = new None();
        /**
         * A plain block item. The client renders it as its block. Like BDS, the palette gives it the runtime id
         * {@code 255 - block_id} (negative: block ids start at 10000), so it moves when a later layer adds blocks and
         * the palette numbers all custom blocks again.
         */
        BlockItem SIMPLE = new Simple();

        /**
         * An item of its own that replaces the block item, e.g. to give the block an icon: an ordinary data driven item with
         * the block's identifier and a {@code block_placer} for the block with {@code replace_block_item}, as BDS takes it.
         * The block owns it, so registering the block registers the item in the same layer, with the next sequential id
         * after the layer's custom items. Its {@code menu_category} is its own; the block keeps its own too.
         *
         * @param item configures the item: the builder starts with the block's identifier and that {@code block_placer},
         *             which the item must keep (other fields of it may change)
         * @return the block item, resolved when the block is built
         * @throws NullPointerException if {@code item} is null
         */
        static @NonNull BlockItem custom(@NonNull UnaryOperator<CustomItem.@NonNull Builder> item) {
            return new Custom(Objects.requireNonNull(item, "item"), null);
        }

        /** {@link BlockItem#NONE}. */
        final class None implements BlockItem {
            private None() {
            }

            @Override
            public String toString() {
                return "NONE";
            }
        }

        /** {@link BlockItem#SIMPLE}. */
        final class Simple implements BlockItem {
            private Simple() {
            }

            @Override
            public String toString() {
                return "SIMPLE";
            }
        }

        /**
         * {@link BlockItem#custom}: the item the block owns. The {@link CustomBlock#item()} of a built block holds the item;
         * equal when the items are equal.
         */
        final class Custom implements BlockItem {
            private final @Nullable UnaryOperator<CustomItem.Builder> configure;
            private final @Nullable CustomItem item;

            private Custom(@Nullable UnaryOperator<CustomItem.Builder> configure, @Nullable CustomItem item) {
                this.configure = configure;
                this.item = item;
            }

            /**
             * Returns the item.
             *
             * @return the item of the block
             * @throws IllegalStateException if this is not the item of a built block ({@link CustomBlock#item()})
             */
            public @NonNull CustomItem item() {
                if (item == null) {
                    throw new IllegalStateException("The item is built with its block: use CustomBlock#item()");
                }
                return item;
            }

            /** Builds the item for a block. */
            private Custom resolve(String identifier) {
                if (item != null) {
                    if (!item.identifier().equals(identifier)) {
                        throw new IllegalArgumentException(item.identifier() + "'s item cannot be the item of " + identifier);
                    }
                    return this;
                }
                CustomItem.Builder builder = configure.apply(CustomItem.builder(identifier).component(placer(identifier)));
                CustomItem built = Objects.requireNonNull(builder, "the item's builder").build();
                BlockPlacer placer = built.component(BlockPlacer.class);
                if (!built.identifier().equals(identifier) || placer == null || !placer.replaceBlockItem()
                    || !identifier.equals(placer.block())) {
                    throw new IllegalArgumentException(identifier + "'s custom item must keep its identifier and its block_placer for "
                        + identifier + " with replace_block_item");
                }
                return new Custom(null, built);
            }

            private static BlockPlacer placer(String identifier) {
                return BlockPlacer.builder().block(identifier).replaceBlockItem(true).build();
            }

            @Override
            public boolean equals(Object o) {
                return this == o || o instanceof Custom other && item != null && item.equals(other.item);
            }

            @Override
            public int hashCode() {
                return Objects.hashCode(item);
            }

            @Override
            public String toString() {
                return "Custom[" + (item == null ? "unbuilt" : item) + "]";
            }
        }
    }

    /**
     * Starts a custom block.
     *
     * @param identifier the block identifier, e.g. {@code example:lamp}
     * @return a builder with the {@link BlockItem#SIMPLE simple} block item and no creative placement
     * @throws NullPointerException if {@code identifier} is null
     * @throws IllegalArgumentException if it is not {@code namespace:name}
     */
    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    /**
     * Looks a base component up by type.
     *
     * @param type the component class
     * @param <T> the component type
     * @return the component of a type among the base components, or null
     */
    public <T extends BlockComponent> @Nullable T component(@NonNull Class<T> type) {
        for (BlockComponent component : components) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
        }
        return null;
    }

    /**
     * The properties of the states.
     *
     * @return the properties that make up the block's states: {@link #properties()}, then the states its traits add
     *         (e.g. {@code minecraft:cardinal_direction} of {@code minecraft:placement_direction})
     */
    public @NonNull List<BlockProperty<?>> stateProperties() {
        return stateProperties(properties, traits);
    }

    /**
     * The number of states.
     *
     * @return the amount of states: the product of the values of {@link #stateProperties()}, at most 2^24
     */
    public int stateCount() {
        int total = 1;
        for (BlockProperty<?> property : stateProperties()) {
            total *= property.values().size();
        }
        return total;
    }

    /**
     * All states of the block.
     *
     * @return all states, in the order Bedrock enumerates them: the first property changes fastest
     */
    public @NonNull List<NbtMap> states() {
        return states(stateProperties());
    }

    /**
     * The state properties in one protocol: without the states of trait values that protocol's client adds no well-defined
     * states for ({@link TraitStates#of(List, int)}). The palette of a version registers the block with these.
     */
    List<BlockProperty<?>> stateProperties(int protocol) {
        if (traits.isEmpty()) {
            return properties;
        }
        List<BlockProperty<?>> all = new ArrayList<>(properties);
        all.addAll(TraitStates.of(traits, protocol));
        return all;
    }

    /** All states over {@link #stateProperties(int)}, in the order of {@link #states()}. */
    List<NbtMap> states(int protocol) {
        return states(stateProperties(protocol));
    }

    /** The default state over {@link #stateProperties(int)}: a default value of a state left out there goes with it. */
    NbtMap defaultState(int protocol) {
        return defaultState(stateProperties(protocol));
    }

    private List<NbtMap> states(List<BlockProperty<?>> properties) {
        int total = 1;
        for (BlockProperty<?> property : properties) {
            total *= property.values().size();
        }
        List<NbtMap> states = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            NbtMapBuilder builder = NbtMap.builder();
            int index = i;
            for (BlockProperty<?> property : properties) {
                builder.put(property.name(), BlockStates.nbtValue(property.values().get(index % property.values().size())));
                index /= property.values().size();
            }
            states.add(BlockStates.of(identifier, builder.build()));
        }
        return states;
    }

    /**
     * The type of the block, to use it like a vanilla {@link dev.opencollab.paletter.vanilla.BedrockBlocks} constant. Its
     * states have canonical ids per layer lineage, see
     * {@link dev.opencollab.paletter.Palette#runtimeId(dev.opencollab.paletter.vanilla.BedrockBlockState)}.
     * <p>
     * It is created with the block and belongs to it: every call returns the same type with the same states, and a
     * palette the block is registered in hands back exactly these for it ({@code block}, {@code decode},
     * {@code Palette#blockState}, ...), so {@code ==} works like for vanilla
     * constants. Another, equal block has a type of its own, equal to this one but not identical: a palette hands back
     * the type of the instance its layer registered.
     *
     * @return the type of the block
     * @since 1.0.0
     */
    public @NonNull BedrockBlockType type() {
        return type;
    }

    /**
     * The type of the block item, owned by the block like {@link #type()}: a palette hands back this type and its base
     * variant for the block item. A palette has the item if it is registered: with the block unless {@link #item()} is
     * {@link BlockItem#NONE}. For a {@link BlockItem#custom custom} item it is that item's type. When a separate custom item
     * of the layer replaces the block item, the palette's item has that item's type instead, not this one:
     * {@link dev.opencollab.paletter.Palette#itemOf} always gives the right one.
     *
     * @return the type of the block item
     * @since 1.0.0
     */
    public @NonNull BedrockItemType itemType() {
        return itemType;
    }

    /**
     * The default state, as {@code {name, states}}.
     *
     * @return the state with the {@link #defaultStates()} values and the first value of every other property
     */
    public @NonNull NbtMap defaultState() {
        return defaultState(stateProperties());
    }

    private NbtMap defaultState(List<BlockProperty<?>> properties) {
        NbtMapBuilder builder = NbtMap.builder();
        for (BlockProperty<?> property : properties) {
            Object value = defaultStates.get(property.name());
            builder.put(property.name(), BlockStates.nbtValue(value != null ? value : property.values().getFirst()));
        }
        return BlockStates.of(identifier, builder.build());
    }

    /**
     * @return true for a block with the same identifier and definition (components in any order); its types are not compared
     */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CustomBlock other && identifier.equals(other.identifier) && properties.equals(other.properties)
            && traits.equals(other.traits) && sameComponents(components, other.components) && permutations.equals(other.permutations)
            && Objects.equals(menuCategory, other.menuCategory) && item.equals(other.item) && creative.equals(other.creative)
            && defaultStates.equals(other.defaultStates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, properties, traits, Set.copyOf(components), permutations, menuCategory, item, creative, defaultStates);
    }

    @Override
    public String toString() {
        return "CustomBlock[identifier=" + identifier + ", properties=" + properties + ", traits=" + traits + ", components="
            + components + ", permutations=" + permutations + ", menuCategory=" + menuCategory + ", item=" + item + ", creative="
            + creative + ", defaultStates=" + defaultStates + "]";
    }

    /**
     * Builder of a {@link CustomBlock}. Not thread safe.
     */
    public static final class Builder {
        private final String identifier;
        private final List<BlockProperty<?>> properties = new ArrayList<>();
        private final List<BlockTrait> traits = new ArrayList<>();
        private final List<BlockComponent> components = new ArrayList<>();
        private final List<Permutation> permutations = new ArrayList<>();
        private @Nullable BlockMenuCategory menuCategory;
        private BlockItem item = BlockItem.SIMPLE;
        private CreativePlacement creative = CreativePlacement.DEFAULT;
        private final Map<String, Object> defaultStates = new LinkedHashMap<>();

        private Builder(String identifier) {
            this.identifier = Check.customIdentifier(identifier, false);
        }

        /**
         * Adds a state property.
         *
         * @param property the property
         * @return this builder
         * @throws IllegalArgumentException if a property with the same name was already added
         */
        public @NonNull Builder property(@NonNull BlockProperty<?> property) {
            for (BlockProperty<?> existing : properties) {
                if (existing.name().equals(property.name())) {
                    throw new IllegalArgumentException(identifier + " already has a property named " + property.name());
                }
            }
            properties.add(property);
            return this;
        }

        /**
         * Adds state properties, see {@link #property(BlockProperty)}.
         *
         * @param properties the properties, in order
         * @return this builder
         * @throws IllegalArgumentException if a property with the same name was already added
         */
        public @NonNull Builder properties(@NonNull List<? extends BlockProperty<?>> properties) {
            properties.forEach(this::property);
            return this;
        }

        /**
         * Adds a trait, replacing one of the same name.
         *
         * @param trait the trait
         * @return this builder
         * @throws NullPointerException if {@code trait} is null
         */
        public @NonNull Builder trait(@NonNull BlockTrait trait) {
            traits.removeIf(existing -> existing.name().equals(trait.name()));
            traits.add(Objects.requireNonNull(trait, "trait"));
            return this;
        }

        /**
         * Adds traits, replacing those of the same names.
         *
         * @param traits the traits
         * @return this builder
         * @throws NullPointerException if a trait is null
         */
        public @NonNull Builder traits(@NonNull List<? extends BlockTrait> traits) {
            traits.forEach(this::trait);
            return this;
        }

        /**
         * Adds a base component, replacing one of the same name.
         *
         * @param component the component
         * @return this builder
         * @throws NullPointerException if {@code component} is null
         */
        public @NonNull Builder component(@NonNull BlockComponent component) {
            components.removeIf(existing -> existing.name().equals(component.name()));
            components.add(Objects.requireNonNull(component, "component"));
            return this;
        }

        /**
         * Adds base components, replacing those of the same names.
         *
         * @param first the first component
         * @param rest the other components
         * @return this builder
         * @throws NullPointerException if a component is null
         */
        public @NonNull Builder components(@NonNull BlockComponent first, @NonNull BlockComponent @NonNull ... rest) {
            component(first);
            for (BlockComponent component : rest) {
                component(component);
            }
            return this;
        }

        /**
         * Adds base components, replacing those of the same names.
         *
         * @param components the components
         * @return this builder
         * @throws NullPointerException if a component is null
         */
        public @NonNull Builder components(@NonNull List<? extends BlockComponent> components) {
            components.forEach(this::component);
            return this;
        }

        /**
         * Adds a permutation: components applied when a Molang condition on the state holds.
         *
         * @param condition a Molang condition, e.g. {@code q.block_state('example:lit') == 1}
         * @param first the first component applied when it holds
         * @param rest the other components
         * @return this builder
         * @throws IllegalArgumentException if two components have the same name, or one is {@code minecraft:tags}
         */
        public @NonNull Builder permutation(@NonNull String condition, @NonNull BlockComponent first, @NonNull BlockComponent @NonNull ... rest) {
            permutations.add(Permutation.of(condition, first, rest));
            return this;
        }

        /**
         * Adds a permutation.
         *
         * @param permutation the permutation
         * @return this builder
         */
        public @NonNull Builder permutation(@NonNull Permutation permutation) {
            permutations.add(Objects.requireNonNull(permutation, "permutation"));
            return this;
        }

        /**
         * Adds permutations, in order.
         *
         * @param permutations the permutations
         * @return this builder
         * @throws NullPointerException if a permutation is null
         */
        public @NonNull Builder permutations(@NonNull List<Permutation> permutations) {
            permutations.forEach(this::permutation);
            return this;
        }

        /**
         * Sets the description's {@code menu_category}: the category, group and {@code is_hidden_in_commands} the add-on
         * JSON gives. It alone decides whether the block item is in the creative inventory, as in BDS; without it, or with
         * category {@code none}, it is not. An item that replaces the block item has a {@code menu_category} of its own.
         *
         * @param menuCategory the menu category, or null for none
         * @return this builder
         */
        public @NonNull Builder menuCategory(@Nullable BlockMenuCategory menuCategory) {
            this.menuCategory = menuCategory;
            return this;
        }

        /**
         * Puts the block item in a creative category: {@link #menuCategory(BlockMenuCategory)} for the common case.
         *
         * @param category the creative category
         * @return this builder
         * @throws NullPointerException if the category is null
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category) {
            return menuCategory(BlockMenuCategory.builder().category(BlockMenuCategory.Category.of(category.bedrockName())).build());
        }

        /**
         * Puts the block item in a group of a creative category: {@link #menuCategory(BlockMenuCategory)} for the common case.
         *
         * @param category the creative category
         * @param group the group, not null, e.g. {@code itemGroup.name.minerals}
         * @return this builder
         * @throws NullPointerException if the category is null
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category, @NonNull String group) {
            return menuCategory(BlockMenuCategory.builder().category(BlockMenuCategory.Category.of(category.bedrockName()))
                .group(group).build());
        }

        /**
         * Sets how the block item is registered. The default is {@link BlockItem#SIMPLE}; {@link BlockItem#custom} gives the
         * block an item of its own.
         *
         * @param item how the block item is registered
         * @return this builder
         * @throws NullPointerException if the item is null
         */
        public @NonNull Builder item(@NonNull BlockItem item) {
            this.item = item instanceof BlockItem.Custom custom ? custom.resolve(identifier) : Objects.requireNonNull(item, "item");
            return this;
        }

        /**
         * Adjusts the position of the block item within its category and group, e.g. right after another item.
         *
         * @param creative where the block item goes
         * @return this builder
         * @throws NullPointerException if the placement is null
         */
        public @NonNull Builder creativePlacement(@NonNull CreativePlacement creative) {
            this.creative = Objects.requireNonNull(creative, "creative");
            return this;
        }

        /**
         * Sets a value of the default state; properties not set use their first value. {@link #build()} rejects a
         * property the block does not have and a value it does not list.
         *
         * @param property the property name
         * @param value the value, one of the values of the property; a {@link Boolean} or the integer 0 or 1 for a boolean
         *              property
         * @return this builder
         * @throws NullPointerException if the property or the value is null
         */
        public @NonNull Builder defaultState(@NonNull String property, @NonNull Object value) {
            defaultStates.put(Objects.requireNonNull(property, "property"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets a boolean value of the default state: the value of a boolean property ({@link Boolean} values).
         * {@link #build()} rejects a property that is not a boolean one.
         *
         * @param property the property name
         * @param value the value
         * @return this builder
         * @throws NullPointerException if the property is null
         */
        public @NonNull Builder defaultState(@NonNull String property, boolean value) {
            return defaultState(property, (Object) value);
        }

        /**
         * Sets an integer value of the default state: the value of an integer property, or the 0 or 1 of a boolean
         * property. {@link #build()} rejects a value the property does not list.
         *
         * @param property the property name
         * @param value the value
         * @return this builder
         * @throws NullPointerException if the property is null
         */
        public @NonNull Builder defaultState(@NonNull String property, int value) {
            return defaultState(property, (Object) value);
        }

        /**
         * Adds block tags, queryable with Molang, to the block's {@link BlockTags} component (created if there is none).
         * Tags it has already are skipped. This and {@link #component(BlockComponent) component(BlockTags)} are one
         * mechanism: the component holds the tags.
         *
         * @param first the first tag
         * @param rest the other tags
         * @return this builder
         * @throws NullPointerException if a tag is null
         */
        public @NonNull Builder tags(@NonNull String first, @NonNull String @NonNull ... rest) {
            List<String> tags = new ArrayList<>(rest.length + 1);
            tags.add(first);
            tags.addAll(Arrays.asList(rest));
            return tags(tags);
        }

        /**
         * Adds block tags, see {@link #tags(String, String...)}.
         *
         * @param tags the tags
         * @return this builder
         * @throws NullPointerException if a tag is null
         */
        public @NonNull Builder tags(@NonNull List<String> tags) {
            List<String> merged = new ArrayList<>();
            for (BlockComponent component : components) {
                if (component instanceof BlockTags existing) {
                    merged.addAll(existing.tags());
                }
            }
            for (String tag : tags) {
                if (!merged.contains(Objects.requireNonNull(tag, "tag"))) {
                    merged.add(tag);
                }
            }
            return merged.isEmpty() ? this : component(BlockTags.builder().tags(merged).build());
        }

        /**
         * Adds one block tag, see {@link #tags(String, String...)}.
         *
         * @param tag the tag
         * @return this builder
         * @throws NullPointerException if the tag is null
         */
        public @NonNull Builder tag(@NonNull String tag) {
            return tags(List.of(tag));
        }

        /**
         * Builds the block, creating its type and item type.
         *
         * @return the block
         * @throws IllegalArgumentException if the block has more than 2^24 state combinations, a default state names a
         *         missing property or value, or a permutation reads a state the block does not have
         */
        public @NonNull CustomBlock build() {
            return new CustomBlock(identifier, properties, traits, components, permutations, menuCategory, item, creative, defaultStates);
        }
    }
}
