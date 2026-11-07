package org.geysermc.paletter.attach;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * A typed key for data attached to items or blocks by a layer, e.g. the Java item a Bedrock item maps back to.
 * Keys are compared by identity: create each once and keep it in a constant.
 * <pre>{@code
 * public static final AttachmentKey<Item> JAVA_ITEM = AttachmentKey.create("geyser:java_item", Item.class);
 * }</pre>
 *
 * @param <T> the attached value type
 */
public final class AttachmentKey<T> {
    private final String name;
    private final Class<T> type;
    private final Conflict conflict;

    private AttachmentKey(String name, Class<T> type, Conflict conflict) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.conflict = Objects.requireNonNull(conflict);
    }

    /**
     * Creates a key that fails compilation when two values are attached to the same target ({@link Conflict#ERROR}).
     * That is the right default for mappings, where a duplicate claim is always a bug.
     */
    public static <T> @NonNull AttachmentKey<T> create(@NonNull String name, @NonNull Class<T> type) {
        return new AttachmentKey<>(name, type, Conflict.ERROR);
    }

    public static <T> @NonNull AttachmentKey<T> create(@NonNull String name, @NonNull Class<T> type, @NonNull Conflict conflict) {
        return new AttachmentKey<>(name, type, conflict);
    }

    public @NonNull String name() {
        return name;
    }

    public @NonNull Class<T> type() {
        return type;
    }

    public @NonNull Conflict conflict() {
        return conflict;
    }

    public T cast(Object value) {
        return type.cast(value);
    }

    @Override
    public String toString() {
        return name;
    }
}
