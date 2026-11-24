package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.behaviour.BlockSchema;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Collects the notes and disputes of the values the model resolves, per deciding capture, key, block or item and version:
 * what {@code explain} says besides the value and its capture. A block's states may change between versions, so the
 * marks are fitted per version (over that version's states) and versions in a row with the same marks become one window.
 */
final class MarkCollector {
    /** The marks of one capture, key, block or item and version: by state hash (0 for an item) the note and the disputes in order. */
    private static final class Marks {
        final Map<Integer, String> notes = new LinkedHashMap<>();
        final Map<Integer, List<BehaviourStore.Dispute>> disputes = new LinkedHashMap<>();
        final List<Integer> order = new ArrayList<>();
    }

    private record Key(int capture, BehaviourKey<?> key, String id, boolean block) {
    }

    /** One version's marks of a key: the note's fit (or null) and each alternative's fit, in order. */
    private record VersionMarks(Fit note, List<Map.Entry<Integer, Fit>> disputes) {
    }

    private final Map<Key, TreeMap<Integer, Marks>> marks = new LinkedHashMap<>();

    void block(BehaviourStore.Resolved resolved, BehaviourKey<?> key, String id, int hash, int version) {
        add(new Key(resolved.capture().index(), key, id, true), version, resolved, hash);
    }

    void item(BehaviourStore.Resolved resolved, BehaviourKey<?> key, String id, int version) {
        add(new Key(resolved.capture().index(), key, id, false), version, resolved, 0);
    }

    private void add(Key key, int version, BehaviourStore.Resolved resolved, int hash) {
        Marks of = marks.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(version, v -> new Marks());
        of.notes.put(hash, resolved.postProcessing());
        of.disputes.put(hash, resolved.disputes());
        for (BehaviourStore.Dispute dispute : resolved.disputes()) {
            if (!of.order.contains(dispute.capture().index())) {
                of.order.add(dispute.capture().index());
            }
        }
    }

    /** @param versions the names of the generated versions, by position; the marks by capture, key and identifier */
    List<BehaviourModel.Mark> marks(Map<Integer, NbtMap> stateByHash, List<String> versions) {
        List<BehaviourModel.Mark> result = new ArrayList<>();
        List<Map.Entry<Key, TreeMap<Integer, Marks>>> sorted = new ArrayList<>(marks.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<Key, TreeMap<Integer, Marks>> e) -> e.getKey().capture())
            .thenComparingInt(e -> Access.behaviour().ordinal(e.getKey().key())).thenComparing(e -> e.getKey().id()));
        for (Map.Entry<Key, TreeMap<Integer, Marks>> entry : sorted) {
            Key key = entry.getKey();
            int from = -1;
            int last = -1;
            VersionMarks run = null;
            for (Map.Entry<Integer, Marks> version : entry.getValue().entrySet()) {
                VersionMarks these = fit(key, version.getValue(), stateByHash);
                if (run != null && (version.getKey() != last + 1 || !run.equals(these))) {
                    emit(result, key, run, versions.get(from), versions.get(last));
                    run = null;
                }
                if (run == null) {
                    run = these;
                    from = version.getKey();
                }
                last = version.getKey();
            }
            emit(result, key, run, versions.get(from), versions.get(last));
        }
        return result;
    }

    private static void emit(List<BehaviourModel.Mark> result, Key key, VersionMarks marks, String from, String to) {
        if (marks.note() != null) {
            result.add(new BehaviourModel.Mark(key.capture(), key.key(), key.id(), null, marks.note(), from, to));
        }
        for (Map.Entry<Integer, Fit> dispute : marks.disputes()) {
            result.add(new BehaviourModel.Mark(key.capture(), key.key(), key.id(), dispute.getKey(), dispute.getValue(), from, to));
        }
    }

    private static VersionMarks fit(Key key, Marks of, Map<Integer, NbtMap> stateByHash) {
        List<Integer> hashes = new ArrayList<>(of.notes.keySet());
        List<Map<String, Object>> states = new ArrayList<>();
        if (key.block()) {
            Map<Map<String, Object>, Integer> byProperties = new TreeMap<>(MarkCollector::compare);
            hashes.forEach(hash -> byProperties.put(BehaviourModel.properties(stateByHash.get(hash).getCompound("states")), hash));
            states.addAll(byProperties.keySet());
            hashes = new ArrayList<>(byProperties.values());
        } else {
            states.add(Map.of());
        }
        List<Object> notes = new ArrayList<>();
        hashes.forEach(hash -> notes.add(of.notes.get(hash)));
        Fit note = notes.stream().anyMatch(Objects::nonNull) ? CellFit.fit(states, notes) : null;
        List<Map.Entry<Integer, Fit>> disputes = new ArrayList<>();
        for (int alternative : of.order) {
            List<Object> values = new ArrayList<>();
            for (int hash : hashes) {
                List<BehaviourStore.Dispute> disputed = of.disputes.get(hash);
                checkOrder(disputed, of.order, key);
                values.add(disputed.stream().filter(d -> d.capture().index() == alternative).map(BehaviourStore.Dispute::value)
                    .findFirst().orElse(null));
            }
            disputes.add(Map.entry(alternative, CellFit.fit(states, values)));
        }
        return new VersionMarks(note, disputes);
    }

    /** The runtime lists a state's disputes in the order of the marks: every state must have them in that order. */
    private static void checkOrder(List<BehaviourStore.Dispute> disputes, List<Integer> order, Key key) {
        int last = -1;
        for (BehaviourStore.Dispute dispute : disputes) {
            int at = order.indexOf(dispute.capture().index());
            if (at <= last) {
                throw new IllegalStateException("The disputes of " + key + " are not in one order for all states");
            }
            last = at;
        }
    }

    private static int compare(Map<String, Object> a, Map<String, Object> b) {
        int c = a.keySet().toString().compareTo(b.keySet().toString());
        if (c != 0) {
            return c;
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            c = BlockSchema.ORDER.compare(entry.getValue(), b.get(entry.getKey()));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
