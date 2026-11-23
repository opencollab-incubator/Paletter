package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
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
 * Collects the notes and disputes of the values the model resolves, per deciding capture, key and block or item: what
 * {@code explain} says besides the value and its capture. A capture's mark of a state is the same in every version the
 * capture decides it in, so the marks of the generated versions are all of them that a lookup can see.
 */
final class MarkCollector {
    /** The marks of one capture, key and block or item: by state hash (0 for an item) the note and the disputes in order. */
    private static final class Marks {
        final Map<Integer, String> notes = new LinkedHashMap<>();
        final Map<Integer, List<BehaviourStore.Dispute>> disputes = new LinkedHashMap<>();
        final List<Integer> order = new ArrayList<>();
    }

    private record Key(int capture, BehaviourKey<?> key, String id, boolean block) {
    }

    private final Map<Key, Marks> marks = new LinkedHashMap<>();

    void block(BehaviourStore.Resolved resolved, BehaviourKey<?> key, String id, int hash) {
        add(new Key(resolved.capture().index(), key, id, true), resolved, hash);
    }

    void item(BehaviourStore.Resolved resolved, BehaviourKey<?> key, String id) {
        add(new Key(resolved.capture().index(), key, id, false), resolved, 0);
    }

    private void add(Key key, BehaviourStore.Resolved resolved, int hash) {
        Marks of = marks.computeIfAbsent(key, k -> new Marks());
        of.notes.putIfAbsent(hash, resolved.postProcessing());
        of.disputes.putIfAbsent(hash, resolved.disputes());
        for (BehaviourStore.Dispute dispute : resolved.disputes()) {
            if (!of.order.contains(dispute.capture().index())) {
                of.order.add(dispute.capture().index());
            }
        }
    }

    /** @return the marks, by capture, key and identifier */
    List<BehaviourModel.Mark> marks(Map<Integer, NbtMap> stateByHash) {
        List<BehaviourModel.Mark> result = new ArrayList<>();
        List<Map.Entry<Key, Marks>> sorted = new ArrayList<>(marks.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<Key, Marks> e) -> e.getKey().capture())
            .thenComparingInt(e -> e.getKey().key().ordinal()).thenComparing(e -> e.getKey().id()));
        for (Map.Entry<Key, Marks> entry : sorted) {
            Key key = entry.getKey();
            Marks of = entry.getValue();
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
            if (notes.stream().anyMatch(Objects::nonNull)) {
                result.add(new BehaviourModel.Mark(key.capture(), key.key(), key.id(), null, CellFit.fit(states, notes)));
            }
            for (int alternative : of.order) {
                List<Object> values = new ArrayList<>();
                for (int hash : hashes) {
                    List<BehaviourStore.Dispute> disputes = of.disputes.get(hash);
                    checkOrder(disputes, of.order, key);
                    values.add(disputes.stream().filter(d -> d.capture().index() == alternative).map(BehaviourStore.Dispute::value)
                        .findFirst().orElse(null));
                }
                Fit fit = CellFit.fit(states, values);
                result.add(new BehaviourModel.Mark(key.capture(), key.key(), key.id(), alternative, fit));
            }
        }
        return result;
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
            c = dev.opencollab.paletter.internal.behaviour.BlockSchema.ORDER.compare(entry.getValue(), b.get(entry.getKey()));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
