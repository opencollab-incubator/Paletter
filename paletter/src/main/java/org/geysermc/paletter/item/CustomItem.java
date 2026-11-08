package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.item.component.MenuCategory;
import org.geysermc.paletter.item.component.Tags;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A custom (data driven) item: an add-on JSON {@code minecraft:item} in Java. The palette assigns its runtime id and
 * canonical id, compiles its components to the item component data of each palette's protocol, and places it in the
 * creative inventory.
 * <pre>{@code
 * CustomItem ruby = CustomItem.builder("example:ruby")
 *     .component(Icon.of("ruby"))
 *     .component(MaxStackSize.of(16))
 *     .component(Durability.builder().maxDurability(250).build())
 *     .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minerals"))
 *     .build();
 * }</pre>
 *
 * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
 * @param components the components (one per component name), in the order they were added
 * @param menuCategory the description's {@code menu_category}, or null: then it follows {@link #creative()} (and without
 *                     one, Bedrock's default)
 * @param creative where it goes in the creative inventory, or null to leave it out
 * @since 1.0.0
 */
public record CustomItem(@NonNull String identifier, @NonNull List<ItemComponent> components, @Nullable MenuCategory menuCategory,
                         @Nullable CreativePlacement creative) {
    public CustomItem {
        Objects.requireNonNull(identifier, "identifier");
        components = List.copyOf(components);
        checkUnique(identifier, components);
    }

    static void checkUnique(String identifier, List<ItemComponent> components) {
        for (int i = 0; i < components.size(); i++) {
            for (int j = i + 1; j < components.size(); j++) {
                if (components.get(i).name().equals(components.get(j).name())) {
                    throw new IllegalArgumentException(identifier + " has two " + components.get(i).name() + " components");
                }
            }
        }
    }

    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    /**
     * @return the component of a type, or null if the item does not have it
     */
    public <T extends ItemComponent> @Nullable T component(@NonNull Class<T> type) {
        for (ItemComponent component : components) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
        }
        return null;
    }

    /**
     * @return the item tags ({@code minecraft:tags})
     */
    public @NonNull List<String> tags() {
        Tags tags = component(Tags.class);
        return tags == null ? List.of() : tags.tags();
    }

    /**
     * @return the type of the item, to use it like a vanilla {@link org.geysermc.paletter.vanilla.BedrockItems} constant
     * @since 1.0.0
     */
    public @NonNull BedrockItemType type() {
        return BedrockItemType.of(this);
    }

    public @NonNull ItemVariant variant() {
        return ItemVariant.of(identifier);
    }

    public static final class Builder {
        private final String identifier;
        private final List<ItemComponent> components = new ArrayList<>();
        private @Nullable MenuCategory menuCategory;
        private @Nullable CreativePlacement creative;

        private Builder(String identifier) {
            this.identifier = Objects.requireNonNull(identifier);
        }

        /**
         * Adds a component, replacing one of the same name.
         */
        public @NonNull Builder component(@NonNull ItemComponent component) {
            components.removeIf(existing -> existing.name().equals(component.name()));
            components.add(Objects.requireNonNull(component, "component"));
            return this;
        }

        /**
         * Adds components, replacing those of the same names.
         */
        public @NonNull Builder components(@NonNull ItemComponent... components) {
            for (ItemComponent component : components) {
                component(component);
            }
            return this;
        }

        /**
         * Adds components, replacing those of the same names.
         */
        public @NonNull Builder components(@NonNull List<? extends ItemComponent> components) {
            components.forEach(this::component);
            return this;
        }

        /**
         * Sets the description's {@code menu_category} explicitly. Without it, the item's {@link #creative} placement
         * decides it.
         */
        public @NonNull Builder menuCategory(@Nullable MenuCategory menuCategory) {
            this.menuCategory = menuCategory;
            return this;
        }

        public @NonNull Builder creative(@Nullable CreativePlacement creative) {
            this.creative = creative;
            return this;
        }

        public @NonNull CustomItem build() {
            return new CustomItem(identifier, components, menuCategory, creative);
        }
    }
}
