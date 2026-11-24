package dev.opencollab.paletter.item;

import dev.opencollab.paletter.internal.component.Check;
import dev.opencollab.paletter.internal.item.ItemVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.item.component.ItemComponent;
import dev.opencollab.paletter.item.component.ItemMenuCategory;
import dev.opencollab.paletter.item.component.Tags;
import dev.opencollab.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * {@link dev.opencollab.paletter.PaletteLayer.Builder#variant(dev.opencollab.paletter.vanilla.BedrockItemVariant) variant},
 * are equal, not identical. Equal items (same identifier and definition) are {@code equals}, but each has a type of its
 * own, see {@link #type()}.
 *
 * @since 1.0.0
 */
public final class CustomItem {
    private final String identifier;
    private final List<ItemComponent> components;
    private final @Nullable ItemMenuCategory menuCategory;
    private final CreativePlacement creative;
    private final BedrockItemType type;

    /**
     * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
     * @param components the components (one per component name), in the order they were added
     * @param menuCategory the description's {@code menu_category}, or null: not in the creative inventory
     * @param creative the position within the category and group
     */
    private CustomItem(@NonNull String identifier, @NonNull List<ItemComponent> components, @Nullable ItemMenuCategory menuCategory,
                      @NonNull CreativePlacement creative) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.components = List.copyOf(components);
        this.menuCategory = menuCategory;
        this.creative = creative;
        this.type = Access.vanilla().customItemType(identifier);
    }

    /**
     * Returns the item identifier.
     *
     * @return the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * Returns the components of the item.
     *
     * @return the components (one per component name), in the order they were added
     */
    public @NonNull List<ItemComponent> components() {
        return components;
    }

    /**
     * Returns the description's {@code menu_category}, which alone decides whether the item is in the creative inventory
     * and in which category and group, as in BDS.
     *
     * @return the menu category; null or category {@code none}: the item is not in the creative inventory
     */
    public @Nullable ItemMenuCategory menuCategory() {
        return menuCategory;
    }

    /**
     * Returns where the item goes within the category and group of the {@link #menuCategory()}.
     *
     * @return the placement, {@link CreativePlacement#DEFAULT} if not set
     */
    public @NonNull CreativePlacement creativePlacement() {
        return creative;
    }

    /**
     * Starts building a custom item.
     *
     * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart}
     * @return a new builder
     * @throws NullPointerException if the identifier is null
     * @throws IllegalArgumentException if it is not {@code namespace:name}, or in the {@code minecraft} namespace, which BDS
     *         rejects for custom items
     */
    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    /**
     * Finds a component by its class.
     *
     * @param type the component class
     * @param <T> the component type
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
     * Returns the item tags.
     *
     * @return the item tags ({@code minecraft:tags}), empty if the item has no {@link Tags} component
     */
    public @NonNull List<String> tags() {
        Tags tags = component(Tags.class);
        return tags == null ? List.of() : tags.tags();
    }

    /**
     * The type of the item, to use it like a vanilla {@link dev.opencollab.paletter.vanilla.BedrockItems} constant. It is
     * created with the item and belongs to it: every call returns the same type (and the same {@link BedrockItemType#variant()
     * base variant}), and a palette the item is registered in hands back exactly these for it ({@code decode},
     * {@code resolve}, {@link RegisteredItem#type()}, ...), so {@code ==} works like for vanilla constants. Another, equal
     * item has a type of its own, equal to this one but not identical: a palette hands back the type of the instance its
     * layer registered.
     *
     * @return the type of the item
     * @since 1.0.0
     */
    public @NonNull BedrockItemType type() {
        return type;
    }

    /**
     * Starts a builder with everything of this item, to make a changed copy. The copy has a type of its own.
     *
     * @return a builder with this item's definition
     */
    public @NonNull Builder toBuilder() {
        Builder builder = new Builder(identifier);
        builder.components.addAll(components);
        builder.menuCategory = menuCategory;
        builder.creative = creative;
        return builder;
    }

    /**
     * The internal variant of the item, for its identifier.
     *
     * @return the internal variant with aux value 0 and no block state
     */
    @NonNull ItemVariant variant() {
        return ItemVariant.of(identifier);
    }

    /**
     * @return true for an item with the same identifier and definition (components in any order); its {@link #type()} is not
     *         compared
     */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CustomItem other && identifier.equals(other.identifier)
            && components.size() == other.components.size() && Set.copyOf(components).equals(Set.copyOf(other.components))
            && Objects.equals(menuCategory, other.menuCategory) && Objects.equals(creative, other.creative);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, Set.copyOf(components), menuCategory, creative);
    }

    @Override
    public String toString() {
        return "CustomItem[identifier=" + identifier + ", components=" + components + ", menuCategory=" + menuCategory
            + ", creative=" + creative + "]";
    }

    /**
     * Builds a {@link CustomItem}.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private final String identifier;
        private final List<ItemComponent> components = new ArrayList<>();
        private @Nullable ItemMenuCategory menuCategory;
        private CreativePlacement creative = CreativePlacement.DEFAULT;

        private Builder(String identifier) {
            this.identifier = Check.customIdentifier(identifier, true);
        }

        /**
         * Adds a component, replacing one of the same name.
         *
         * @param component the component
         * @return this builder
         * @throws NullPointerException if the component is null
         */
        public @NonNull Builder component(@NonNull ItemComponent component) {
            components.removeIf(existing -> existing.name().equals(component.name()));
            components.add(Objects.requireNonNull(component, "component"));
            return this;
        }

        /**
         * Adds components, replacing those of the same names.
         *
         * @param first the first component
         * @param rest the other components
         * @return this builder
         * @throws NullPointerException if a component is null
         */
        public @NonNull Builder components(@NonNull ItemComponent first, @NonNull ItemComponent @NonNull ... rest) {
            component(first);
            for (ItemComponent component : rest) {
                component(component);
            }
            return this;
        }

        /**
         * Adds item tags to the item's {@link Tags} component ({@code minecraft:tags}, created if there is none). Tags it
         * has already are skipped. This and {@link #component(ItemComponent) component(Tags)} are one mechanism: the
         * component holds the tags.
         *
         * @param first the first tag, e.g. {@code minecraft:is_food}
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
         * Adds item tags, see {@link #tags(String, String...)}.
         *
         * @param tags the tags
         * @return this builder
         * @throws NullPointerException if a tag is null
         */
        public @NonNull Builder tags(@NonNull List<String> tags) {
            List<String> merged = new ArrayList<>();
            for (ItemComponent component : components) {
                if (component instanceof Tags existing) {
                    merged.addAll(existing.tags());
                }
            }
            for (String tag : tags) {
                if (!merged.contains(Objects.requireNonNull(tag, "tag"))) {
                    merged.add(tag);
                }
            }
            return merged.isEmpty() ? this : component(Tags.builder().tags(merged).build());
        }

        /**
         * Adds one item tag, see {@link #tags(String, String...)}.
         *
         * @param tag the tag
         * @return this builder
         * @throws NullPointerException if the tag is null
         */
        public @NonNull Builder tag(@NonNull String tag) {
            return tags(List.of(tag));
        }

        /**
         * Adds components, replacing those of the same names.
         *
         * @param components the components
         * @return this builder
         * @throws NullPointerException if a component is null
         */
        public @NonNull Builder components(@NonNull List<? extends ItemComponent> components) {
            components.forEach(this::component);
            return this;
        }

        /**
         * Sets the description's {@code menu_category}: the category, group and {@code is_hidden_in_commands} the add-on
         * JSON gives. It alone decides whether the item is in the creative inventory, as in BDS; without it, or with
         * category {@code none}, it is not.
         *
         * @param menuCategory the menu category, or null for none
         * @return this builder
         */
        public @NonNull Builder menuCategory(@Nullable ItemMenuCategory menuCategory) {
            this.menuCategory = menuCategory;
            return this;
        }

        /**
         * Puts the item in a creative category: {@link #menuCategory(ItemMenuCategory)} for the common case.
         *
         * @param category the creative category
         * @return this builder
         * @throws NullPointerException if the category is null
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category) {
            return menuCategory(ItemMenuCategory.builder().category(ItemMenuCategory.Category.of(category.bedrockName())).build());
        }

        /**
         * Puts the item in a group of a creative category: {@link #menuCategory(ItemMenuCategory)} for the common case.
         *
         * @param category the creative category
         * @param group the group, not null, e.g. {@code itemGroup.name.minerals}
         * @return this builder
         * @throws NullPointerException if the category is null
         */
        public @NonNull Builder menuCategory(@NonNull CreativeCategory category, @NonNull String group) {
            return menuCategory(ItemMenuCategory.builder().category(ItemMenuCategory.Category.of(category.bedrockName()))
                .group(group).build());
        }

        /**
         * Adjusts the position of the item within its category and group, e.g. right after another item.
         *
         * @param creative where the item goes
         * @return this builder
         * @throws NullPointerException if the placement is null
         */
        public @NonNull Builder creativePlacement(@NonNull CreativePlacement creative) {
            this.creative = Objects.requireNonNull(creative, "creative");
            return this;
        }

        /**
         * Builds the item. Each call makes a new item with a type of its own.
         *
         * @return the custom item
         */
        public @NonNull CustomItem build() {
            return new CustomItem(identifier, components, menuCategory, creative);
        }
    }
}
