package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativePlacement;

import java.util.Objects;

/**
 * A custom (data driven) item. The palette assigns its runtime id and canonical id, writes its component data, and
 * places it in the creative inventory.
 *
 * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart}
 * @param components the components
 * @param creative where it goes in the creative inventory, or null to leave it out
 */
public record CustomItem(@NonNull String identifier, @NonNull ItemComponents components, @Nullable CreativePlacement creative) {

    public static @NonNull Builder builder(@NonNull String identifier) {
        return new Builder(identifier);
    }

    public @NonNull ItemVariant variant() {
        return ItemVariant.of(identifier);
    }

    public static final class Builder {
        private final String identifier;
        private ItemComponents components = ItemComponents.EMPTY;
        private @Nullable CreativePlacement creative;

        private Builder(String identifier) {
            this.identifier = Objects.requireNonNull(identifier);
        }

        public @NonNull Builder components(@NonNull ItemComponents components) {
            this.components = components;
            return this;
        }

        public @NonNull Builder creative(@Nullable CreativePlacement creative) {
            this.creative = creative;
            return this;
        }

        public @NonNull CustomItem build() {
            return new CustomItem(identifier, components, creative);
        }
    }
}
