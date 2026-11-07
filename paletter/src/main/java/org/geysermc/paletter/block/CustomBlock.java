package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.ItemComponents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A custom (data driven) block. Registering one in a layer generates its states, its block property data, its
 * block item and its creative entry; runtime ids and palette ordering are handled by the palette.
 *
 * @param identifier the block identifier
 * @param properties the state properties, in declaration order
 * @param components the base components
 * @param permutations components applied when a Molang condition on the state holds
 * @param menu where the block shows up in the client's block menu data, or null for none
 * @param item how the block item is registered
 * @param creative where the block item goes in the creative inventory, or null to leave it out
 * @param defaultStates state values of the default state; properties not listed use their first value
 */
public record CustomBlock(
    @NonNull String identifier,
    @NonNull List<BlockProperty<?>> properties,
    @NonNull BlockComponents components,
    @NonNull List<Permutation> permutations,
    @Nullable Menu menu,
    @NonNull BlockItem item,
    @Nullable CreativePlacement creative,
    @NonNull Map<String, Object> defaultStates
) {

    public CustomBlock {
        properties = List.copyOf(properties);
        permutations = List.copyOf(permutations);
        defaultStates = Map.copyOf(defaultStates);
        for (Map.Entry<String, Object> entry : defaultStates.entrySet()) {
            BlockProperty<?> property = properties.stream().filter(p -> p.name().equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(identifier + " has no property " + entry.getKey()));
            if (!property.values().contains(entry.getValue())) {
                throw new IllegalArgumentException(entry.getValue() + " is not a value of " + identifier + "'s " + entry.getKey());
            }
        }
    }

    /**
     * @param condition a Molang condition, e.g. {@code q.block_state('geyser:lit') == 1}
     * @param components the components applied when it holds
     */
    public record Permutation(@NonNull String condition, @NonNull BlockComponents components) {
    }

    /**
     * The {@code menu_category} of a block, which the client uses for its own block menus. Independent from the
     * creative inventory placement, though {@link Builder#creative} sets both.
     */
    public record Menu(@NonNull CreativeCategory category, @NonNull String group, boolean hiddenInCommands) {
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
         * {@code minecraft:block_placer} component for the block.
         */
        static @NonNull BlockItem dataDriven(@NonNull ItemComponents components) {
            return new DataDriven(components);
        }

        record None() implements BlockItem {
        }

        record Simple() implements BlockItem {
        }

        record DataDriven(@NonNull ItemComponents components) implements BlockItem {
        }
    }

    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    /**
     * @return all states, in the order Bedrock enumerates them: the first property changes fastest
     */
    public @NonNull List<NbtMap> states() {
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

    public @NonNull NbtMap defaultState() {
        NbtMapBuilder builder = NbtMap.builder();
        for (BlockProperty<?> property : properties) {
            Object value = defaultStates.get(property.name());
            builder.put(property.name(), value != null ? value : property.values().getFirst());
        }
        return BlockStates.of(identifier, builder.build());
    }

    /**
     * @param blockId the numeric block id, unique among custom blocks of a palette
     * @return the block property data NBT, as sent in the start game packet
     */
    public @NonNull NbtMap propertyData(int blockId) {
        List<NbtMap> permutationsNbt = new ArrayList<>(permutations.size());
        for (Permutation permutation : permutations) {
            permutationsNbt.add(NbtMap.builder()
                .putCompound("components", permutation.components().toNbt())
                .putString("condition", permutation.condition())
                .build());
        }
        List<NbtMap> propertiesNbt = new ArrayList<>(properties.size());
        for (BlockProperty<?> property : properties) {
            propertiesNbt.add(property.toNbt());
        }

        // The client crashes without a menu category
        Menu menu = this.menu;
        return NbtMap.builder()
            .putCompound("components", components.toNbt())
            .putCompound("menu_category", NbtMap.builder()
                .putString("category", menu == null ? CreativeCategory.NONE_NAME : menu.category().bedrockName())
                .putString("group", menu == null ? "" : menu.group())
                .putBoolean("is_hidden_in_commands", menu != null && menu.hiddenInCommands())
                .build())
            // Meaning unknown, but required for tags to work
            .putInt("molangVersion", 1)
            .putList("permutations", NbtType.COMPOUND, permutationsNbt)
            .putList("properties", NbtType.COMPOUND, propertiesNbt)
            .putCompound("vanilla_block_data", NbtMap.builder()
                .putInt("block_id", blockId)
                .build())
            .build();
    }

    public static final class Builder {
        private final String identifier;
        private final List<BlockProperty<?>> properties = new ArrayList<>();
        private BlockComponents components = BlockComponents.EMPTY;
        private final List<Permutation> permutations = new ArrayList<>();
        private @Nullable Menu menu;
        private BlockItem item = BlockItem.SIMPLE;
        private @Nullable CreativePlacement creative;
        private final Map<String, Object> defaultStates = new LinkedHashMap<>();

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

        public @NonNull Builder components(@NonNull BlockComponents components) {
            this.components = components;
            return this;
        }

        public @NonNull Builder permutation(@NonNull String condition, @NonNull BlockComponents components) {
            permutations.add(new Permutation(condition, components));
            return this;
        }

        public @NonNull Builder menu(@Nullable Menu menu) {
            this.menu = menu;
            return this;
        }

        public @NonNull Builder item(@NonNull BlockItem item) {
            this.item = item;
            return this;
        }

        /**
         * Puts the block item in the creative inventory, and uses the same category and group for the block's menu
         * category unless {@link #menu} was set explicitly.
         */
        public @NonNull Builder creative(@Nullable CreativePlacement creative) {
            this.creative = creative;
            return this;
        }

        public @NonNull Builder defaultState(@NonNull String property, @NonNull Object value) {
            defaultStates.put(property, value);
            return this;
        }

        public @NonNull CustomBlock build() {
            Menu menu = this.menu;
            if (menu == null && creative != null) {
                menu = new Menu(creative.category(), creative.group() == null ? "" : creative.group(), false);
            }
            return new CustomBlock(identifier, properties, components, permutations, menu, item, creative, defaultStates);
        }
    }
}
