package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.BlockTrait;
import org.geysermc.paletter.block.component.MenuCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.internal.component.TraitStates;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;

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
 *     .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
 *     .build();
 * }</pre>
 *
 * @param identifier the block identifier (description {@code identifier})
 * @param properties the state properties (description {@code states}), in declaration order
 * @param traits the traits (description {@code traits}); the states they add are part of {@link #states()}
 * @param components the base components (one per component name)
 * @param permutations components applied when a Molang condition on the state holds
 * @param menuCategory the description's {@code menu_category}, or null: then it follows {@link #creative()}, else
 *                     {@code none}
 * @param item how the block item is registered
 * @param creative where the block item goes in the creative inventory, or null to leave it out
 * @param defaultStates state values of the default state; properties not listed use their first value
 * @param tags block tags, queryable with Molang (sent as the block's {@code blockTags})
 * @since 1.0.0
 */
public record CustomBlock(
    @NonNull String identifier,
    @NonNull List<BlockProperty<?>> properties,
    @NonNull List<BlockTrait> traits,
    @NonNull List<BlockComponent> components,
    @NonNull List<Permutation> permutations,
    @Nullable MenuCategory menuCategory,
    @NonNull BlockItem item,
    @Nullable CreativePlacement creative,
    @NonNull Map<String, Object> defaultStates,
    @NonNull List<String> tags
) {
    public CustomBlock {
        properties = List.copyOf(properties);
        traits = List.copyOf(traits);
        components = List.copyOf(components);
        permutations = List.copyOf(permutations);
        defaultStates = Map.copyOf(defaultStates);
        tags = List.copyOf(tags);
        checkUnique(identifier, components);
        for (Map.Entry<String, Object> entry : defaultStates.entrySet()) {
            BlockProperty<?> property = properties.stream().filter(p -> p.name().equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(identifier + " has no property " + entry.getKey()));
            if (!property.values().contains(entry.getValue())) {
                throw new IllegalArgumentException(entry.getValue() + " is not a value of " + identifier + "'s " + entry.getKey());
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
        public Permutation {
            Objects.requireNonNull(condition, "condition");
            components = List.copyOf(components);
            checkUnique("permutation '" + condition + "'", components);
        }
    }

    /**
     * How the item of a custom block is registered.
     */
    public sealed interface BlockItem {
        /**
         * No item; the block cannot be held.
         */
        BlockItem NONE = new None();
        /**
         * A plain block item. The client renders it as its block.
         */
        BlockItem SIMPLE = new Simple();

        /**
         * A data driven item that places the block, e.g. to give it its own icon. The palette adds the
         * {@code minecraft:block_placer} component for the block unless the components have one.
         */
        static @NonNull BlockItem dataDriven(@NonNull ItemComponent... components) {
            return new DataDriven(List.of(components));
        }

        record None() implements BlockItem {
        }

        record Simple() implements BlockItem {
        }

        record DataDriven(@NonNull List<ItemComponent> components) implements BlockItem {
            public DataDriven {
                components = List.copyOf(components);
            }
        }
    }

    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    /**
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
     * @return the properties that make up the block's states: {@link #properties()}, then the states its traits add
     *         (e.g. {@code minecraft:cardinal_direction} of {@code minecraft:placement_direction})
     */
    public @NonNull List<BlockProperty<?>> stateProperties() {
        if (traits.isEmpty()) {
            return properties;
        }
        List<BlockProperty<?>> all = new ArrayList<>(properties);
        all.addAll(TraitStates.of(traits));
        return all;
    }

    /**
     * @return all states, in the order Bedrock enumerates them: the first property changes fastest
     */
    public @NonNull List<NbtMap> states() {
        List<BlockProperty<?>> properties = stateProperties();
        int total = 1;
        for (BlockProperty<?> property : properties) {
            total *= property.values().size();
        }
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
     * @return the type of the block, to use it like a vanilla {@link org.geysermc.paletter.vanilla.BedrockBlocks}
     *         constant. Its states have canonical ids per layer lineage, see
     *         {@link org.geysermc.paletter.Palette#canonicalBlock(org.geysermc.paletter.vanilla.BedrockBlockState)}
     * @since 1.0.0
     */
    public @NonNull BedrockBlockType type() {
        return BedrockBlockType.of(this);
    }

    /**
     * @return the type of the block item, or null if the block has none ({@link BlockItem#NONE})
     * @since 1.0.0
     */
    public @Nullable BedrockItemType itemType() {
        return item instanceof BlockItem.None ? null : BedrockItemType.custom(identifier);
    }

    public @NonNull NbtMap defaultState() {
        NbtMapBuilder builder = NbtMap.builder();
        for (BlockProperty<?> property : stateProperties()) {
            Object value = defaultStates.get(property.name());
            builder.put(property.name(), value != null ? value : property.values().getFirst());
        }
        return BlockStates.of(identifier, builder.build());
    }

    public static final class Builder {
        private final String identifier;
        private final List<BlockProperty<?>> properties = new ArrayList<>();
        private final List<BlockTrait> traits = new ArrayList<>();
        private final List<BlockComponent> components = new ArrayList<>();
        private final List<Permutation> permutations = new ArrayList<>();
        private @Nullable MenuCategory menuCategory;
        private BlockItem item = BlockItem.SIMPLE;
        private @Nullable CreativePlacement creative;
        private final Map<String, Object> defaultStates = new LinkedHashMap<>();
        private final List<String> tags = new ArrayList<>();

        private Builder(String identifier) {
            this.identifier = Objects.requireNonNull(identifier);
        }

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
         */
        public @NonNull Builder trait(@NonNull BlockTrait trait) {
            traits.removeIf(existing -> existing.name().equals(trait.name()));
            traits.add(Objects.requireNonNull(trait, "trait"));
            return this;
        }

        /**
         * Adds a base component, replacing one of the same name.
         */
        public @NonNull Builder component(@NonNull BlockComponent component) {
            components.removeIf(existing -> existing.name().equals(component.name()));
            components.add(Objects.requireNonNull(component, "component"));
            return this;
        }

        /**
         * Adds base components, replacing those of the same names.
         */
        public @NonNull Builder components(@NonNull BlockComponent... components) {
            for (BlockComponent component : components) {
                component(component);
            }
            return this;
        }

        public @NonNull Builder permutation(@NonNull String condition, @NonNull BlockComponent... components) {
            permutations.add(new Permutation(condition, List.of(components)));
            return this;
        }

        public @NonNull Builder permutation(@NonNull Permutation permutation) {
            permutations.add(permutation);
            return this;
        }

        /**
         * Sets the description's {@code menu_category} explicitly. Without it, {@link #creative} decides it.
         */
        public @NonNull Builder menuCategory(@Nullable MenuCategory menuCategory) {
            this.menuCategory = menuCategory;
            return this;
        }

        public @NonNull Builder item(@NonNull BlockItem item) {
            this.item = item;
            return this;
        }

        /**
         * Puts the block item in the creative inventory, and uses the same category and group for the block's menu
         * category unless {@link #menuCategory} was set explicitly.
         */
        public @NonNull Builder creative(@Nullable CreativePlacement creative) {
            this.creative = creative;
            return this;
        }

        public @NonNull Builder defaultState(@NonNull String property, @NonNull Object value) {
            defaultStates.put(property, value);
            return this;
        }

        /**
         * Adds block tags, queryable with Molang.
         */
        public @NonNull Builder tags(@NonNull String... tags) {
            for (String tag : tags) {
                if (!this.tags.contains(tag)) {
                    this.tags.add(Objects.requireNonNull(tag, "tag"));
                }
            }
            return this;
        }

        public @NonNull Builder tag(@NonNull String tag) {
            return tags(tag);
        }

        public @NonNull CustomBlock build() {
            MenuCategory menu = this.menuCategory;
            if (menu == null && creative != null) {
                menu = MenuCategory.builder()
                    .category(MenuCategory.Category.fromValue(creative.category().bedrockName()))
                    .group(creative.group())
                    .build();
            }
            return new CustomBlock(identifier, properties, traits, components, permutations, menu, item, creative, defaultStates, tags);
        }
    }
}
