package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.tools.behaviour.BehaviourOps.Op;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The calls that turn the state of the version before ({@link BehaviourOps.Sim}) into a version of the model: few, so a
 * version's class has only what changed. A key that changes to the same value for most blocks (or items) is set for every
 * one ({@code everyBlock()}), and the others are written after it; it is also what blocks added later start from.
 */
final class BehaviourDiff {
    private BehaviourDiff() {
    }

    /** @return the calls, which are also applied to {@code sim} (and checked against the version) */
    static List<Op> diff(BehaviourOps.Sim sim, BehaviourModel.Version target) {
        List<Op> ops = new ArrayList<>();
        // ---- Blocks ----
        Map<Integer, Object> every = new TreeMap<>();
        for (int k = 0; k < sim.blockDefaults.length; k++) {
            Map<Object, Integer> counts = new HashMap<>();
            int key = k;
            target.blocks().values().forEach(block -> {
                if (block.fits()[key] instanceof Constant constant) {
                    counts.merge(new Holder(constant.value()), 1, Integer::sum);
                }
            });
            Holder mode = mode(counts);
            if (mode == null || Objects.equals(mode.value(), sim.blockDefaults[k]) && !sim.blocks.isEmpty()) {
                continue;
            }
            long toMode = target.blocks().values().stream().filter(block -> {
                BehaviourOps.Sim.SimBlock before = sim.blocks.get(block.id());
                return before != null && before.schema().equals(block.schema()) && !before.values()[key].equals(block.fits()[key])
                    && block.fits()[key].equals(new Constant(mode.value()));
            }).count();
            boolean base = sim.blocks.isEmpty();
            if (base ? mode.value() != null : toMode * 2 > target.blocks().size()) {
                every.put(k, mode.value());
            }
        }
        add(ops, sim, every.isEmpty() ? null : new BehaviourOps.EveryBlock(every));
        for (String id : sim.blocks.keySet().stream().filter(id -> !target.blocks().containsKey(id)).toList()) {
            add(ops, sim, new BehaviourOps.RemoveBlock(id));
        }
        for (BehaviourModel.Block block : target.blocks().values()) {
            BehaviourOps.Sim.SimBlock before = sim.blocks.get(block.id());
            Map<Integer, Fit> values = new TreeMap<>();
            boolean declare = before == null || !before.schema().equals(block.schema());
            for (int k = 0; k < block.fits().length; k++) {
                Fit was = declare ? new Constant(sim.blockDefaults[k]) : before.values()[k];
                if (!was.equals(block.fits()[k])) {
                    values.put(k, block.fits()[k]);
                }
            }
            if (declare) {
                add(ops, sim, new BehaviourOps.DeclareBlock(block.id(), block.schema(), values, before != null));
            } else if (!values.isEmpty()) {
                add(ops, sim, new BehaviourOps.ChangeBlock(block.id(), values));
            }
        }
        // ---- Items ----
        Map<Integer, Object> everyItem = new TreeMap<>();
        for (int k = 0; k < sim.itemDefaults.length; k++) {
            Map<Object, Integer> counts = new HashMap<>();
            int key = k;
            target.items().values().forEach(item -> counts.merge(new Holder(item.values()[key]), 1, Integer::sum));
            Holder mode = mode(counts);
            if (mode == null || Objects.equals(mode.value(), sim.itemDefaults[k]) && !sim.items.isEmpty()) {
                continue;
            }
            long toMode = target.items().values().stream().filter(item -> sim.items.containsKey(item.id())
                && !Objects.equals(sim.items.get(item.id())[key], item.values()[key]) && Objects.equals(item.values()[key], mode.value())).count();
            boolean base = sim.items.isEmpty();
            if (base ? mode.value() != null : toMode * 2 > target.items().size()) {
                everyItem.put(k, mode.value());
            }
        }
        add(ops, sim, everyItem.isEmpty() ? null : new BehaviourOps.EveryItem(everyItem));
        for (String id : sim.items.keySet().stream().filter(id -> !target.items().containsKey(id)).toList()) {
            add(ops, sim, new BehaviourOps.RemoveItem(id));
        }
        for (BehaviourModel.Item item : target.items().values()) {
            Object[] before = sim.items.get(item.id());
            Map<Integer, Object> values = new TreeMap<>();
            for (int k = 0; k < item.values().length; k++) {
                if (!Objects.equals(before == null ? sim.itemDefaults[k] : before[k], item.values()[k])) {
                    values.put(k, item.values()[k]);
                }
            }
            if (before == null) {
                add(ops, sim, new BehaviourOps.DeclareItem(item.id(), values));
            } else if (!values.isEmpty()) {
                add(ops, sim, new BehaviourOps.ChangeItem(item.id(), values));
            }
        }
        sources(ops, sim, target);
        sim.check(target);
        return ops;
    }

    /** The captures: the version's defaults, then every block and item whose own captures changed. */
    private static void sources(List<Op> ops, BehaviourOps.Sim sim, BehaviourModel.Version target) {
        Map<Integer, Integer> defaults = new TreeMap<>();
        for (int k = 0; k < sim.blockSourceDefaults.length; k++) {
            if (!Objects.equals(sim.blockSourceDefaults[k], target.blockSources()[k])) {
                defaults.put(k, target.blockSources()[k]);
            }
        }
        add(ops, sim, defaults.isEmpty() ? null : new BehaviourOps.BlockSourceDefaults(defaults));
        for (BehaviourModel.Block block : target.blocks().values()) {
            Fit[] before = sim.blockSources.getOrDefault(block.id(), new Fit[block.sources().length]);
            Map<Integer, Fit> values = new TreeMap<>();
            for (int k = 0; k < before.length; k++) {
                if (!Objects.equals(before[k], block.sources()[k])) {
                    values.put(k, block.sources()[k]);
                }
            }
            add(ops, sim, values.isEmpty() ? null : new BehaviourOps.BlockSources(block.id(), values));
        }
        Map<Integer, Integer> itemDefaults = new TreeMap<>();
        for (int k = 0; k < sim.itemSourceDefaults.length; k++) {
            if (!Objects.equals(sim.itemSourceDefaults[k], target.itemSources()[k])) {
                itemDefaults.put(k, target.itemSources()[k]);
            }
        }
        add(ops, sim, itemDefaults.isEmpty() ? null : new BehaviourOps.ItemSourceDefaults(itemDefaults));
        for (BehaviourModel.Item item : target.items().values()) {
            Integer[] before = sim.itemSources.getOrDefault(item.id(), new Integer[item.sources().length]);
            Map<Integer, Integer> values = new TreeMap<>();
            for (int k = 0; k < before.length; k++) {
                if (!Objects.equals(before[k], item.sources()[k])) {
                    values.put(k, item.sources()[k]);
                }
            }
            add(ops, sim, values.isEmpty() ? null : new BehaviourOps.ItemSources(item.id(), values));
        }
    }

    private static void add(List<Op> ops, BehaviourOps.Sim sim, Op op) {
        if (op != null) {
            ops.add(op);
            sim.apply(op);
        }
    }

    /** A value that may be null, as a map key. */
    private record Holder(Object value) {
    }

    /** @return the most frequent value, ties to the first in {@link Object#toString()} order; null if there is none */
    private static Holder mode(Map<Object, Integer> counts) {
        return counts.entrySet().stream().min((a, b) -> a.getValue().equals(b.getValue())
            ? String.valueOf(((Holder) a.getKey()).value()).compareTo(String.valueOf(((Holder) b.getKey()).value()))
            : Integer.compare(b.getValue(), a.getValue())).map(e -> (Holder) e.getKey()).orElse(null);
    }
}
