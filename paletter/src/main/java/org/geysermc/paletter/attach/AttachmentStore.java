package org.geysermc.paletter.attach;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.PaletteException;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Attachments keyed by target (item variant or block state). Copy on write, so a palette extended by a session
 * layer shares all unchanged attachments with its parent. Internal to Paletter; read through {@link Attachments}.
 *
 * @param <K> the target type
 */
public final class AttachmentStore<K> {
    private final Map<K, Values> values;
    private final Set<Values> owned = Collections.newSetFromMap(new IdentityHashMap<>());

    public AttachmentStore() {
        this.values = new HashMap<>();
    }

    private AttachmentStore(Map<K, Values> values) {
        this.values = new HashMap<>(values);
    }

    /**
     * @return a store with the same contents. Changes to either do not affect the other
     */
    public @NonNull AttachmentStore<K> copy() {
        // Values are now shared with the copy; neither side may write to them in place anymore
        owned.clear();
        return new AttachmentStore<>(values);
    }

    public @NonNull Attachments get(@NonNull K target) {
        Values found = values.get(target);
        return found == null ? Attachments.EMPTY : found;
    }

    public @Nullable Values getIfPresent(@NonNull K target) {
        return values.get(target);
    }

    /**
     * @param weak true if the value only reached this target through a version fallback
     * @param source who attached it, for error messages
     * @throws PaletteException on a {@link Conflict#ERROR} conflict between two direct attachments
     */
    public void put(@NonNull K target, @NonNull AttachmentKey<?> key, @NonNull Object value, boolean weak, @NonNull String source) {
        if (!key.type().isInstance(value)) {
            throw new PaletteException("Attachment " + key + " expects " + key.type().getName() + " but " + source
                + " attached " + value.getClass().getName() + " to " + target);
        }
        Values existing = values.get(target);
        Object current = existing == null ? null : existing.values.get(key);
        if (current != null) {
            boolean currentWeak = existing.weak.contains(key);
            if (weak) {
                // Fallback values never replace anything, and are never an error
                return;
            }
            if (!currentWeak) {
                switch (key.conflict()) {
                    case ERROR -> throw new PaletteException("Both " + existing.sources.get(key) + " and " + source + " attach "
                        + key + " to " + target + " (" + current + " vs " + value + "). " + key
                        + " must be unique; attach one of them to a more specific variant, or use another Conflict mode");
                    case KEEP_FIRST -> {
                        return;
                    }
                    case REPLACE -> {
                    }
                }
            }
        }

        Values writable = writable(target, existing);
        writable.values.put(key, value);
        writable.sources.put(key, source);
        if (weak) {
            writable.weak.add(key);
        } else {
            writable.weak.remove(key);
        }
    }

    private Values writable(K target, @Nullable Values existing) {
        if (existing != null && owned.contains(existing)) {
            return existing;
        }
        Values copy = existing == null ? new Values() : existing.copy();
        owned.add(copy);
        values.put(target, copy);
        return copy;
    }

    public int size() {
        return values.size();
    }

    public @NonNull Set<K> targets() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public static final class Values implements Attachments {
        private final Map<AttachmentKey<?>, Object> values = new IdentityHashMap<>(4);
        private final Map<AttachmentKey<?>, String> sources = new IdentityHashMap<>(4);
        private final Set<AttachmentKey<?>> weak = Collections.newSetFromMap(new IdentityHashMap<>(2));

        private Values copy() {
            Values copy = new Values();
            copy.values.putAll(values);
            copy.sources.putAll(sources);
            copy.weak.addAll(weak);
            return copy;
        }

        @Override
        public <T> @Nullable T get(@NonNull AttachmentKey<T> key) {
            Object value = values.get(key);
            return value == null ? null : key.cast(value);
        }

        @Override
        public @NonNull Set<AttachmentKey<?>> keys() {
            return Collections.unmodifiableSet(values.keySet());
        }

        /**
         * @return true if the value of this key only reached this target through a version fallback
         */
        public boolean isFallback(@NonNull AttachmentKey<?> key) {
            return weak.contains(key);
        }

        @Override
        public String toString() {
            return values.toString();
        }
    }
}
