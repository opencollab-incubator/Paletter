package dev.opencollab.paletter.internal.access;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The bridge to members of the exported packages that Paletter's other packages (and its tools) need but that are not
 * API. Each exported package keeps such members package-private and implements one accessor interface of this package
 * in the static initializer of an owning class, which sets it here; a getter initializes that class first, so the
 * accessor is always there when asked for. Not API.
 */
public final class Access {
    private static volatile BlockAccess block;
    private static volatile ItemAccess item;

    private Access() {
    }

    /**
     * @return the accessor of {@code dev.opencollab.paletter.block}, set by {@code CustomBlock}
     */
    public static @NonNull BlockAccess block() {
        return get(() -> block, "dev.opencollab.paletter.block.CustomBlock");
    }

    /**
     * @param access the accessor of {@code dev.opencollab.paletter.block}; set once
     */
    public static void setBlock(@NonNull BlockAccess access) {
        check(block);
        block = Objects.requireNonNull(access);
    }

    /**
     * @return the accessor of {@code dev.opencollab.paletter.item}, set by {@code RegisteredItem}
     */
    public static @NonNull ItemAccess item() {
        return get(() -> item, "dev.opencollab.paletter.item.RegisteredItem");
    }

    /**
     * @param access the accessor of {@code dev.opencollab.paletter.item}; set once
     */
    public static void setItem(@NonNull ItemAccess access) {
        check(item);
        item = Objects.requireNonNull(access);
    }

    private static <T> T get(Supplier<T> field, String owner) {
        T access = field.get();
        if (access == null) {
            try {
                Class.forName(owner, true, Access.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("No owner " + owner + " of an accessor", e);
            }
            access = field.get();
            if (access == null) {
                throw new IllegalStateException(owner + " did not set its accessor");
            }
        }
        return access;
    }

    private static void check(Object current) {
        if (current != null) {
            throw new IllegalStateException("The accessor is already set");
        }
    }
}
