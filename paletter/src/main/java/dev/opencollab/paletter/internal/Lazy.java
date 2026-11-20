package dev.opencollab.paletter.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Supplier;

/**
 * A value made on first use, once, however many threads ask at the same time. After it is made a read is a volatile
 * field read: no lock and no allocation. The value can be dropped again with {@link #release()} (the next
 * {@link #get()} makes it anew) or set ahead of use with {@link #preset(Object)}. Not API.
 *
 * @param <T> the type of the value, never null
 */
public final class Lazy<T> {
    private final Supplier<? extends T> supplier;
    private volatile @Nullable T value;

    public Lazy(@NonNull Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    /**
     * @return the value, made now if it was not made yet or was released
     */
    public @NonNull T get() {
        T result = value;
        if (result == null) {
            synchronized (this) {
                result = value;
                if (result == null) {
                    value = result = supplier.get();
                }
            }
        }
        return result;
    }

    /**
     * @return the value if it was made and not released, else null; never makes it
     */
    public @Nullable T peek() {
        return value;
    }

    /**
     * Sets the value without making it, where it is known already.
     */
    public void preset(@NonNull T preset) {
        synchronized (this) {
            value = preset;
        }
    }

    /**
     * Drops the value if it was made; the next {@link #get()} makes it anew.
     */
    public void release() {
        synchronized (this) {
            value = null;
        }
    }
}
