package org.geysermc.paletter.tools.behaviour;

import org.geysermc.paletter.behaviour.BehaviourKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What one source says about one data version: per key, the value of each id (block state hash or item identifier),
 * and the source fields no key reads.
 *
 * @param <I> the id type: {@link Integer} for block states, {@link String} for items
 */
final class Values<I> {
    final Map<BehaviourKey<?>, Map<I, Object>> byKey = new LinkedHashMap<>();
    final Set<I> ids = new TreeSet<>();
    final Map<String, Integer> unmappedFields = new TreeMap<>();

    void put(BehaviourKey<?> key, I id, Object value) {
        byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, value);
        ids.add(id);
    }

    void unmapped(String field) {
        unmappedFields.merge(field, 1, Integer::sum);
    }

    boolean isEmpty() {
        return ids.isEmpty();
    }
}
