package dev.opencollab.paletter.internal.block;

import org.cloudburstmc.nbt.NbtMap;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The map behind the {@link NbtMap} of a block state: its two entries, {@code name} and then {@code states}, in two fields.
 * Over a {@code LinkedHashMap} (what {@code NbtMap.builder()} makes) a state takes about 260 bytes: the map, its table, an
 * object per entry and a cached entry set view. A palette holds tens of thousands of states for its whole life, so this
 * holds the two values alone. The entries are made when iterated, in the same order, so the state writes the same bytes.
 * <p>
 * Immutable. Equal to every other map with the same two entries, with the same hash code ({@link Map#hashCode}).
 */
final class StateMap implements Map<String, Object> {
    private static final String NAME = "name";
    private static final String STATES = "states";
    /** The keys are the same for every state: one set for all. */
    private static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(NAME, STATES)));

    private final String name;
    private final NbtMap states;

    StateMap(String name, NbtMap states) {
        this.name = name;
        this.states = states;
    }

    @Override
    public int size() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean containsKey(Object key) {
        return NAME.equals(key) || STATES.equals(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return name.equals(value) || states.equals(value);
    }

    @Override
    public Object get(Object key) {
        return NAME.equals(key) ? name : STATES.equals(key) ? states : null;
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ?> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> keySet() {
        return KEYS;
    }

    @Override
    public Collection<Object> values() {
        return List.of(name, states);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, Object>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < 2;
                    }

                    @Override
                    public Entry<String, Object> next() {
                        return switch (next++) {
                            case 0 -> Map.entry(NAME, name);
                            case 1 -> Map.entry(STATES, states);
                            default -> throw new NoSuchElementException();
                        };
                    }
                };
            }

            @Override
            public int size() {
                return 2;
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        return o == this || o instanceof Map<?, ?> map && map.size() == 2 && name.equals(map.get(NAME)) && states.equals(map.get(STATES));
    }

    @Override
    public int hashCode() {
        return (NAME.hashCode() ^ name.hashCode()) + (STATES.hashCode() ^ states.hashCode());
    }

    @Override
    public String toString() {
        return "{" + NAME + "=" + name + ", " + STATES + "=" + states + "}";
    }
}
