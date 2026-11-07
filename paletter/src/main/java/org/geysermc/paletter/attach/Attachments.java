package org.geysermc.paletter.attach;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

/**
 * Read only view of the values attached to one item variant or block state.
 */
public interface Attachments {
    Attachments EMPTY = new Attachments() {
        @Override
        public <T> @Nullable T get(@NonNull AttachmentKey<T> key) {
            return null;
        }

        @Override
        public @NonNull Set<AttachmentKey<?>> keys() {
            return Set.of();
        }
    };

    <T> @Nullable T get(@NonNull AttachmentKey<T> key);

    @NonNull Set<AttachmentKey<?>> keys();

    default <T> @NonNull T getOrDefault(@NonNull AttachmentKey<T> key, @NonNull T fallback) {
        T value = get(key);
        return value == null ? fallback : value;
    }

    default boolean has(@NonNull AttachmentKey<?> key) {
        return get(key) != null;
    }

    default boolean isEmpty() {
        return keys().isEmpty();
    }
}
