package org.geysermc.paletter.item;

import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.item.component.ItemMenuCategory;
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
    private final @Nullable ItemMenuCategory menuCategory;
    private final @Nullable CreativePlacement creative;
    private final BedrockItemType type;

    /**
     * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart} (description {@code identifier})
     * @param components the components (one per component name), in the order they were added
     * @param menuCategory the description's {@code menu_category}, or null: then it follows {@link #creative()} (and
     *                     without one, Bedrock's default)
     * @param creative where it goes in the creative inventory, or null to leave it out
     */
    private CustomItem(@NonNull String identifier, @NonNull List<ItemComponent> components, @Nullable ItemMenuCategory menuCategory,
                      @Nullable CreativePlacement creative) {
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
     * @return the description's {@code menu_category}, or null: then it follows {@link #creative()} (and without one,
     *         Bedrock's default)
     */
    public @Nullable ItemMenuCategory menuCategory() {
        return menuCategory;
    }

    /**
     * @return where it goes in the creative inventory, or null to leave it out
     */
    public @Nullable CreativePlacement creative() {
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
        private @Nullable ItemMenuCategory menuCategory;
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
        public @NonNull Builder menuCategory(@Nullable ItemMenuCategory menuCategory) {
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
