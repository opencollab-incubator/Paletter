package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.attach.AttachmentKey;
import org.geysermc.paletter.creative.CreativePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A custom (data driven) item. The palette assigns its runtime id, writes its component data, and places it in the
 * creative inventory.
 *
 * @param identifier the item identifier, e.g. {@code geysermc:furnace_minecart}
 * @param components the components
 * @param creative where it goes in the creative inventory, or null to leave it out
 * @param attachments values attached to the item's base variant
 */
public record CustomItem(@NonNull String identifier, @NonNull ItemComponents components, @Nullable CreativePlacement creative,
                         @NonNull List<Attached<?>> attachments) {

    public CustomItem {
        attachments = List.copyOf(attachments);
    }

    public record Attached<T>(@NonNull AttachmentKey<T> key, @NonNull T value) {
        public Attached {
            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException(key + " expects " + key.type().getName() + ", got " + value.getClass().getName());
            }
        }
    }

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
        private final List<Attached<?>> attachments = new ArrayList<>();

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

        /**
         * Attaches a value to this item, e.g. the Java item it translates back to.
         */
        public <T> @NonNull Builder attach(@NonNull AttachmentKey<T> key, @NonNull T value) {
            attachments.add(new Attached<>(key, value));
            return this;
        }

        public @NonNull CustomItem build() {
            return new CustomItem(identifier, components, creative, attachments);
        }
    }
}
