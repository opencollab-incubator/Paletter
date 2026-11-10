package org.geysermc.paletter.tools.behaviour;

import org.geysermc.paletter.behaviour.BehaviourKey;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What one source says about one data version: per key, the value of each id (block state hash or item identifier),
 * the values the source says it post-processed, and the source fields no key reads.
 *
 * @param <I> the id type: {@link Integer} for block states, {@link String} for items
 */
final class Values<I> {
    final Map<BehaviourKey<?>, Map<I, Object>> byKey = new LinkedHashMap<>();
    final Set<I> ids = new TreeSet<>();
    final Map<String, Integer> unmappedFields = new TreeMap<>();
    /** Per key: how the source says it post-processed the value of an id (e.g. {@code door_state_correction}). */
    final Map<BehaviourKey<?>, Map<I, String>> postProcessed = new LinkedHashMap<>();
    /** Keys this source says which of its values it post-processed: an id without a note was not. */
    final Set<BehaviourKey<?>> reportsPostProcessing = new HashSet<>();

    void put(BehaviourKey<?> key, I id, Object value) {
        byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, value);
        ids.add(id);
    }

    void postProcessed(BehaviourKey<?> key, I id, String note) {
        postProcessed.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, note);
    }

    void unmapped(String field) {
        unmappedFields.merge(field, 1, Integer::sum);
    }

    boolean isEmpty() {
        return ids.isEmpty();
    }
}
