package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Arrays;

/**
 * A behaviour value that depends on state properties, as data: the value for each combination of the values of some
 * properties, keyed by the property values (never by palette order). One property is a map ({@link #map}), several a
 * table ({@link #table}) whose values run through the combinations with the last property fastest. A value may be null:
 * nothing is known for those states. Immutable.
 * <p>
 * The generated behaviour data uses cells for values (and for the captures that decided them) that are not the same
 * for every state of a block; a value that is is stored as it is.
 */
@Internal
public final class Cell {
    private final String[] properties;
    /** The values of each property, as {@link BlockSchema#value} reads them. */
    private final Object[][] domains;
    private final @Nullable Object[] values;

    private Cell(String[] properties, Object[][] domains, @Nullable Object[] values) {
        int size = 1;
        for (int i = 0; i < properties.length; i++) {
            size *= domains[i].length;
        }
        if (properties.length != domains.length || values.length != size) {
            throw new IllegalArgumentException("A cell of " + Arrays.toString(properties) + " needs " + size + " values, got " + values.length);
        }
        this.properties = properties;
        this.domains = domains;
        this.values = values;
    }

    /**
     * @param pairs a property value, then the value for it, and so on
     * @return the value as a function of one property
     */
    public static @NonNull Cell map(@NonNull String property, @Nullable Object @NonNull ... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Pairs of a property value and a value expected for " + property);
        }
        Object[] keys = new Object[pairs.length / 2];
        Object[] values = new Object[pairs.length / 2];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = pairs[i * 2];
            values[i] = pairs[i * 2 + 1];
        }
        return new Cell(new String[] {property}, new Object[][] {keys}, values);
    }

    /**
     * @param domains the values of each property
     * @param values the value of each combination, the last property fastest
     * @return the value as a function of several properties
     */
    public static @NonNull Cell table(@NonNull String @NonNull [] properties, @NonNull Object @NonNull [] @NonNull [] domains,
                                      @Nullable Object @NonNull ... values) {
        return new Cell(properties, domains, values);
    }

    /**
     * @param states the {@code states} compound of a block state
     * @return the value for the state, or null if nothing is known (or a property value is not one of the cell's)
     */
    public @Nullable Object get(@NonNull NbtMap states) {
        int index = 0;
        for (int i = 0; i < properties.length; i++) {
            Object value = BlockSchema.value(states.get(properties[i]));
            Object[] domain = domains[i];
            int at = -1;
            for (int j = 0; j < domain.length; j++) {
                if (domain[j].equals(value)) {
                    at = j;
                    break;
                }
            }
            if (at < 0) {
                return null;
            }
            index = index * domain.length + at;
        }
        return values[index];
    }

    @Override
    public String toString() {
        return "Cell" + Arrays.toString(properties) + Arrays.deepToString(domains) + "=" + Arrays.toString(values);
    }
}
