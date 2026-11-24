package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * A Molang expression of a component field, with the Molang version it is written for or none. Add-on JSON writes the
 * expression as a string, or as an object {@code {"expression": ..., "version": ...}}; a field without a version gets the
 * one BDS gives the string form (for {@code bone_visibility} the layer's, from its
 * {@link PaletteLayer.Builder#minEngineVersion min_engine_version}; for {@code repair_amount} 0). Where a version reaches
 * the wire and what BDS does with one past the newest Molang version is the field's: see its documentation.
 *
 * @since 1.0.0
 */
public final class Molang {
    /** See {@link #minVersion()}; not a public constant, so a later release can change it without recompiling users. */
    private static final int MIN_VERSION = -1;
    /** See {@link #maxVersion()}. */
    private static final int MAX_VERSION = 100;

    private final String expression;
    private final @Nullable Integer version;

    private Molang(String expression, @Nullable Integer version) {
        this.expression = Objects.requireNonNull(expression, "Molang.expression");
        if (version != null && (version < MIN_VERSION || version > MAX_VERSION)) {
            throw new IllegalArgumentException("Molang.version must be between " + MIN_VERSION + " and " + MAX_VERSION
                + " (the versions BDS was tried with): " + version);
        }
        this.version = version;
    }

    /**
     * The lowest Molang version {@link #of(String, int)} takes: the lowest the captures tried (BDS takes {@code -1};
     * lower ones were never tried). A later release may lower it.
     *
     * @return the lowest version
     * @since 1.0.0
     */
    public static int minVersion() {
        return MIN_VERSION;
    }

    /**
     * The highest Molang version {@link #of(String, int)} takes: the highest the captures tried. A later release may
     * raise it.
     *
     * @return the highest version
     * @since 1.0.0
     */
    public static int maxVersion() {
        return MAX_VERSION;
    }

    /**
     * An expression without a version: the field's default version.
     *
     * @param expression the expression as add-on JSON writes it
     * @return the expression
     * @since 1.0.0
     */
    public static @NonNull Molang of(@NonNull String expression) {
        return new Molang(expression, null);
    }

    /**
     * An expression with the Molang version it is written for.
     *
     * @param expression the expression as add-on JSON writes it
     * @param version    the Molang version, {@link #minVersion()} to {@link #maxVersion()}
     * @return the expression
     * @throws IllegalArgumentException if the version is outside that range
     * @since 1.0.0
     */
    public static @NonNull Molang of(@NonNull String expression, int version) {
        return new Molang(expression, version);
    }

    /**
     * Returns the expression.
     *
     * @return the expression as add-on JSON writes it
     * @since 1.0.0
     */
    public @NonNull String expression() {
        return expression;
    }

    /**
     * Returns the version.
     *
     * @return the Molang version, or null for the field's default
     * @since 1.0.0
     */
    public @Nullable Integer version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Molang other && expression.equals(other.expression) && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return 31 * expression.hashCode() + Objects.hashCode(version);
    }

    @Override
    public String toString() {
        return version == null ? "Molang[" + expression + "]" : "Molang[" + expression + ", version " + version + "]";
    }
}
