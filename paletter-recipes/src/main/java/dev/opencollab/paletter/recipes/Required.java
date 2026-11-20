package dev.opencollab.paletter.recipes;

/**
 * The convention of the builders, as in the generated components: a {@code @NonNull} setter given null throws
 * {@link NullPointerException} at once, and {@code build()} throws {@link IllegalStateException} for a required field that
 * was never set.
 */
final class Required {
    private Required() {
    }

    /**
     * @param name the field, as {@code Type.field}
     * @return the value of a required field
     * @throws IllegalStateException if it was never set
     */
    static <T> T of(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
