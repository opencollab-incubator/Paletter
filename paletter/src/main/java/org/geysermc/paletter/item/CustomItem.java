package org.geysermc.paletter.item;

import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativeCategory;
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
 *     .menuCategory(CreativeCategory.ITEMS, "itemGroup.name.minerals")
 *     .build();
 * }</pre>
 * <p>
 * An instance owns its {@link #type()}: the palette hands back that same type and its base variant for the item, so they
 * can be compared with {@code ==}. Other variants, given canonical ids with a layer's
 * {@link org.geysermc.paletter.PaletteLayer.Builder#variant(org.geysermc.paletter.vanilla.BedrockItemVariant) variant},
 * are equal, not identical. Equal items (same identifier and definition) are {@code equals}, but each has a type of its
 * own, see {@link #type()}.
 *
 * @since 1.0.0
 */
public final class CustomItem {
    private final String identifier;
    private final List<ItemComponent> components;
    private final @Nullable MenuCategory menuCategory;
    private final CreativePlacement creative;
    private final BedrockItemType type;

    /**
     * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
     * @param components the components (one per component name), in the order they were added
     * @param menuCategory the description's {@code menu_category}, or null: not in the creative inventory
     * @param creative the position within the category and group
     */
    private CustomItem(@NonNull String identifier, @NonNull List<ItemComponent> components, @Nullable MenuCategory menuCategory,
                      @NonNull CreativePlacement creative) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.components = List.copyOf(components);
        this.menuCategory = menuCategory;
        this.creative = creative;
        this.type = BedrockItemType.custom(identifier);
    }

    /**
     * @return the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * @return the components (one per component name), in the order they were added
     */
    public @NonNull List<ItemComponent> components() {
        return components;
    }

    /**
     * @return the description's {@code menu_category}, which alone decides whether the item is in the creative inventory
     *         and in which category and group, as in BDS; null or category {@code none}: it is not in it
     */
    public @Nullable MenuCategory menuCategory() {
        return menuCategory;
    }

    /**
     * @return the position within the category and group of the {@link #menuCategory()}, {@link CreativePlacement#DEFAULT}
     *         if not set
     */
    public @NonNull CreativePlacement creative() {
        return creative;
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
     * The type of the item, to use it like a vanilla {@link org.geysermc.paletter.vanilla.BedrockItems} constant. It is
     * created with the item and belongs to it: every call returns the same type (and the same {@link BedrockItemType#variant()
     * base variant}), and a palette the item is registered in hands back exactly these for it ({@code decode},
     * {@code resolve}, {@link RegisteredItem#key()}, ...), so {@code ==} works like for vanilla constants. Another, equal
     * item has a type of its own, equal to this one but not identical: a palette hands back the type of the instance its
     * layer registered.
     *
     * @return the type of the item
     * @since 1.0.0
     */
    public @NonNull BedrockItemType type() {
        return type;
    }

    @Internal
    public @NonNull ItemVariant variant() {
        return ItemVariant.of(identifier);
    }

    /**
     * @return true for an item with the same identifier and definition; its {@link #type()} is not compared
     */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CustomItem other && identifier.equals(other.identifier) && components.equals(other.components)
            && Objects.equals(menuCategory, other.menuCategory) && Objects.equals(creative, other.creative);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, components, menuCategory, creative);
    }

    @Override
    public String toString() {
        return "CustomItem[identifier=" + identifier + ", components=" + components + ", menuCategory=" + menuCategory
            + ", creative=" + creative + "]";
    }

    public static final class Builder {
        private final String identifier;
        private final List<ItemComponent> components = new ArrayList<>();
        private @Nullable MenuCategory menuCategory;
        private CreativePlacement creative = CreativePlacement.DEFAULT;

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
         * Sets the description's {@code menu_category}: the category, group and {@code is_hidden_in_commands} the add-on
         * JSON gives. It alone decides whether the item is in the creative inventory, as in BDS; without it, or with
         * category {@code none}, it is not.
         */
        public @NonNull Builder menuCategory(@Nullable MenuCategory menuCategory) {
            this.menuCategory = menuCategory;
            return this;
        }

        /**
         * Puts the item in a creative category: {@link #menuCategory(MenuCategory)} for the common case.
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category) {
            return menuCategory(MenuCategory.builder().category(MenuCategory.Category.fromValue(category.bedrockName())).build());
        }

        /**
         * Puts the item in a group of a creative category: {@link #menuCategory(MenuCategory)} for the common case.
         *
         * @param group the group, e.g. {@code itemGroup.name.minerals}
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category, @NonNull String group) {
            return menuCategory(MenuCategory.builder().category(MenuCategory.Category.fromValue(category.bedrockName()))
                .group(group).build());
        }

        /**
         * Adjusts the position of the item within its category and group, e.g. right after another item.
         */
        public @NonNull Builder creative(@NonNull CreativePlacement creative) {
            this.creative = Objects.requireNonNull(creative, "creative");
            return this;
        }

        public @NonNull CustomItem build() {
            return new CustomItem(identifier, components, menuCategory, creative);
        }
    }
}
