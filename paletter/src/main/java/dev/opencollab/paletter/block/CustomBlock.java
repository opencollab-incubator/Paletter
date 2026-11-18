package dev.opencollab.paletter.block;

import dev.opencollab.paletter.internal.block.BlockStates;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import dev.opencollab.paletter.block.component.BlockComponent;
import dev.opencollab.paletter.block.component.BlockTrait;
import dev.opencollab.paletter.block.component.EmbeddedVisual;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.ItemVisual;
import dev.opencollab.paletter.block.component.BlockMenuCategory;
import dev.opencollab.paletter.block.component.UvLock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.internal.component.TraitStates;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A custom (data driven) block: an add-on JSON {@code minecraft:block} in Java. Registering one in a layer generates its
 * states, its block property data, its block item and its creative entry; runtime ids and palette ordering are handled
 * by the palette.
 * <pre>{@code
 * CustomBlock lamp = CustomBlock.builder("example:lamp")
 *     .property(BlockProperty.bool("example:lit"))
 *     .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
 *     .component(Geometry.of("minecraft:geometry.full_block"))
 *     .component(MaterialInstances.builder().all(MaterialInstances.Instance.builder().texture("lamp").build()).build())
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
    private final String identifier;
    private final List<BlockProperty<?>> properties;
    private final List<BlockTrait> traits;
    private final List<BlockComponent> components;
    private final List<Permutation> permutations;
    private final @Nullable BlockMenuCategory menuCategory;
    private final BlockItem item;
    private final CreativePlacement creative;
    private final Map<String, Object> defaultStates;
    private final List<String> tags;
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
     * @param tags block tags, queryable with Molang (sent as the block's {@code blockTags})
     */
    private CustomBlock(@NonNull String identifier, @NonNull List<BlockProperty<?>> properties, @NonNull List<BlockTrait> traits,
                       @NonNull List<BlockComponent> components, @NonNull List<Permutation> permutations,
                       @Nullable BlockMenuCategory menuCategory, @NonNull BlockItem item, @NonNull CreativePlacement creative,
                       @NonNull Map<String, Object> defaultStates, @NonNull List<String> tags) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.properties = List.copyOf(properties);
        this.traits = List.copyOf(traits);
        this.components = List.copyOf(components);
        this.permutations = List.copyOf(permutations);
        this.menuCategory = menuCategory;
        this.item = Objects.requireNonNull(item, "item");
        this.creative = creative;
        this.tags = List.copyOf(tags);
        checkUvLock(identifier, this.components);
        for (Permutation permutation : this.permutations) {
            checkUvLock(identifier, permutation.components());
        }
        List<BlockProperty<?>> stateProperties = stateProperties(this.properties, this.traits);
        long combinations = 1;
        for (BlockProperty<?> property : stateProperties) {
            combinations *= property.values().size();
            if (combinations > MAX_STATES) {
                throw new IllegalArgumentException(identifier + " has more than 2^24 state combinations");
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
        this.itemType = BedrockItemType.custom(identifier);
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
     * The block tags.
     *
     * @return block tags, queryable with Molang (sent as the block's {@code blockTags})
     */
    public @NonNull List<String> tags() {
        return tags;
    }

    /** The most state combinations a block may have, like vanilla blocks. */
    private static final int MAX_STATES = 1 << 24;
    /**
     * The most {@code uv_lock} bones of a geometry ({@code minecraft:geometry} and the geometry of {@code item_visual} and
     * {@code embedded_visual}); BDS 1.26.63.1 rejects the whole block above it, counting duplicates.
     */
    private static final int MAX_UV_LOCK_BONES = 64;

    private static List<BlockProperty<?>> stateProperties(List<BlockProperty<?>> properties, List<BlockTrait> traits) {
        if (traits.isEmpty()) {
            return properties;
        }
        List<BlockProperty<?>> all = new ArrayList<>(properties);
        all.addAll(TraitStates.of(traits));
        return all;
    }

    /** A default state value as the property stores it: a boolean property has the bytes 0 and 1. */
    private static Object valueOf(BlockProperty<?> property, Object value) {
        if (property.type() == BlockProperty.Type.BOOLEAN) {
            if (value instanceof Boolean flag) {
                return (byte) (flag ? 1 : 0);
            }
            if (value instanceof Integer number && (number == 0 || number == 1)) {
                return number.byteValue();
            }
        }
        return value;
    }

    private static void checkUvLock(String identifier, List<BlockComponent> components) {
        for (BlockComponent component : components) {
            UvLock uvLock = switch (component) {
                case Geometry geometry -> geometry.uvLock();
                case ItemVisual visual -> visual.geometry().uvLock();
                case EmbeddedVisual visual -> visual.geometry().uvLock();
                default -> null;
            };
            if (uvLock instanceof UvLock.Bones bones && bones.value().size() > MAX_UV_LOCK_BONES) {
                throw new IllegalArgumentException(identifier + "'s " + component.name() + " lists " + bones.value().size()
                    + " uv_lock bones, more than the " + MAX_UV_LOCK_BONES + " BDS allows");
            }
        }
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
     * An entry of {@code permutations}.
     *
     * @param condition a Molang condition, e.g. {@code q.block_state('geyser:lit') == 1}
     * @param components the components applied when it holds
     */
    public record Permutation(@NonNull String condition, @NonNull List<BlockComponent> components) {
        /**
         * Creates a permutation; the components are copied.
         *
         * @throws NullPointerException if {@code condition} is null
         * @throws IllegalArgumentException if two components have the same name
         */
        public Permutation {
            Objects.requireNonNull(condition, "condition");
            components = List.copyOf(components);
            checkUnique("permutation '" + condition + "'", components);
        }
    }

    /**
     * How the item of a custom block is registered. An item of its own that replaces it, e.g. to give the block an icon, is
     * an ordinary {@link dev.opencollab.paletter.item.CustomItem} with the identifier of the block and a
     * {@code block_placer} with {@code replace_block_item}, registered in the same layer: the block's item is then that item.
     */
    public enum BlockItem {
        /**
         * No item; the block cannot be held.
         */
        NONE,
        /**
         * A plain block item. The client renders it as its block. Like BDS, the palette gives it the runtime id
         * {@code 255 - block_id} (negative: block ids start at 10000), so it moves when a later layer adds blocks and
         * the palette numbers all custom blocks again.
         */
        SIMPLE
    }

    /**
     * Starts a custom block.
     *
     * @param identifier the block identifier, e.g. {@code example:lamp}
     * @return a builder with the {@link BlockItem#SIMPLE simple} block item and no creative placement
     * @throws NullPointerException if {@code identifier} is null
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
        List<BlockProperty<?>> properties = stateProperties();
        int total = stateCount();
        List<NbtMap> states = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            NbtMapBuilder builder = NbtMap.builder();
            int index = i;
            for (BlockProperty<?> property : properties) {
                builder.put(property.name(), property.values().get(index % property.values().size()));
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
     * {@link BlockItem#NONE}. When a custom item of the layer replaces the block item, the palette's item has that item's type
     * instead, not this one: {@link dev.opencollab.paletter.Palette#itemOf} always gives the right one.
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
        NbtMapBuilder builder = NbtMap.builder();
        for (BlockProperty<?> property : stateProperties()) {
            Object value = defaultStates.get(property.name());
            builder.put(property.name(), value != null ? value : property.values().getFirst());
        }
        return BlockStates.of(identifier, builder.build());
    }

    /**
     * @return true for a block with the same identifier and definition; its types are not compared
     */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CustomBlock other && identifier.equals(other.identifier) && properties.equals(other.properties)
            && traits.equals(other.traits) && components.equals(other.components) && permutations.equals(other.permutations)
            && Objects.equals(menuCategory, other.menuCategory) && item.equals(other.item) && creative.equals(other.creative)
            && defaultStates.equals(other.defaultStates) && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, properties, traits, components, permutations, menuCategory, item, creative, defaultStates, tags);
    }

    @Override
    public String toString() {
        return "CustomBlock[identifier=" + identifier + ", properties=" + properties + ", traits=" + traits + ", components="
            + components + ", permutations=" + permutations + ", menuCategory=" + menuCategory + ", item=" + item + ", creative="
            + creative + ", defaultStates=" + defaultStates + ", tags=" + tags + "]";
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
        private final List<String> tags = new ArrayList<>();

        private Builder(String identifier) {
            this.identifier = Objects.requireNonNull(identifier);
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
         * @param components the components
         * @return this builder
         */
        public @NonNull Builder components(@NonNull BlockComponent... components) {
            for (BlockComponent component : components) {
                component(component);
            }
            return this;
        }

        /**
         * Adds a permutation: components applied when a Molang condition on the state holds.
         *
         * @param condition a Molang condition, e.g. {@code q.block_state('example:lit') == 1}
         * @param components the components applied when it holds
         * @return this builder
         * @throws IllegalArgumentException if two components have the same name
         */
        public @NonNull Builder permutation(@NonNull String condition, @NonNull BlockComponent... components) {
            permutations.add(new Permutation(condition, List.of(components)));
            return this;
        }

        /**
         * Adds a permutation.
         *
         * @param permutation the permutation
         * @return this builder
         */
        public @NonNull Builder permutation(@NonNull Permutation permutation) {
            permutations.add(permutation);
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
            return menuCategory(BlockMenuCategory.builder().category(BlockMenuCategory.Category.fromValue(category.bedrockName())).build());
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
            return menuCategory(BlockMenuCategory.builder().category(BlockMenuCategory.Category.fromValue(category.bedrockName()))
                .group(group).build());
        }

        /**
         * Sets how the block item is registered. The default is {@link BlockItem#SIMPLE}.
         *
         * @param item how the block item is registered
         * @return this builder
         * @throws NullPointerException if the item is null
         */
        public @NonNull Builder item(@NonNull BlockItem item) {
            this.item = Objects.requireNonNull(item, "item");
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
         *              property, which stores the bytes 0 and 1
         * @return this builder
         * @throws NullPointerException if the property or the value is null
         */
        public @NonNull Builder defaultState(@NonNull String property, @NonNull Object value) {
            defaultStates.put(Objects.requireNonNull(property, "property"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets a boolean value of the default state: the value of a boolean property, which stores the bytes 0 and 1.
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
         * property, which stores the bytes 0 and 1. {@link #build()} rejects a value the property does not list.
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
         * Adds block tags, queryable with Molang. Tags already added are skipped.
         *
         * @param tags the tags
         * @return this builder
         * @throws NullPointerException if a tag is null
         */
        public @NonNull Builder tags(@NonNull String... tags) {
            for (String tag : tags) {
                if (!this.tags.contains(tag)) {
                    this.tags.add(Objects.requireNonNull(tag, "tag"));
                }
            }
            return this;
        }

        /**
         * Adds one block tag, see {@link #tags(String...)}.
         *
         * @param tag the tag
         * @return this builder
         */
        public @NonNull Builder tag(@NonNull String tag) {
            return tags(tag);
        }

        /**
         * Builds the block, creating its type and item type.
         *
         * @return the block
         * @throws IllegalArgumentException if a {@code uv_lock} list has more than 64 bones, the block has more than
         *         2^24 state combinations, or a default state names a missing property or value
         */
        public @NonNull CustomBlock build() {
            return new CustomBlock(identifier, properties, traits, components, permutations, menuCategory, item, creative, defaultStates, tags);
        }
    }
}
