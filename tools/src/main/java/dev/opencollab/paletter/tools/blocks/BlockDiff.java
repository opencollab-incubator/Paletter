package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.vanilla.BlockDefinition;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What changed in the block definitions from one version to the next, as the operations of {@link BlockDefinitions}: a
 * new block, a removed one, properties inserted into a block's order or removed from it, and a new default state.
 * Anything else (other values, another order) redefines the block. {@link #diff} applies the operations to a copy of the
 * version before and fails unless that gives the version.
 */
final class BlockDiff {
    private BlockDiff() {
    }

    sealed interface Op {
        String block();

        void apply(BlockDefinitions definitions);
    }

    record Add(BlockDefinition definition) implements Op {
        public String block() {
            return definition.identifier();
        }

        public void apply(BlockDefinitions definitions) {
            BlockDefinitions.Defined defined = definitions.block(block(), definition.properties().toArray(StateValues[]::new));
            if (definition.defaultState() != 0) {
                defined.defaultState(defaultValues(definition));
            }
        }
    }

    record Remove(String block) implements Op {
        public void apply(BlockDefinitions definitions) {
            definitions.remove(block);
        }
    }

    record AddProperties(String block, int position, List<StateValues> properties) implements Op {
        public void apply(BlockDefinitions definitions) {
            definitions.addProperties(block, position, properties.toArray(StateValues[]::new));
        }
    }

    record RemoveProperty(String block, String property) implements Op {
        public void apply(BlockDefinitions definitions) {
            definitions.removeProperty(block, property);
        }
    }

    /** @param why what changed: other values, another order */
    record Redefine(BlockDefinition definition, String why) implements Op {
        public String block() {
            return definition.identifier();
        }

        public void apply(BlockDefinitions definitions) {
            BlockDefinitions.Defined defined = definitions.redefine(block(), definition.properties().toArray(StateValues[]::new));
            if (definition.defaultState() != 0) {
                defined.defaultState(defaultValues(definition));
            }
        }
    }

    /** @param before the default state before, as its values */
    record Default(String block, Object[] values, Object[] before, List<String> properties) implements Op {
        public void apply(BlockDefinitions definitions) {
            definitions.defaultState(block, values);
        }
    }

    /** @return the values of a block's default state, one per property in property order */
    static Object[] defaultValues(BlockDefinition definition) {
        int[] indices = definition.valueIndices(definition.defaultState());
        Object[] values = new Object[indices.length];
        for (int i = 0; i < indices.length; i++) {
            values[i] = definition.properties().get(i).value(indices[i]);
        }
        return values;
    }

    /** @return the operations that make {@code after} of {@code before}, by block identifier, checked */
    static List<Op> diff(String version, BlockDefinitions before, BlockDefinitions after) {
        TreeSet<String> names = new TreeSet<>();
        before.blocks().forEach(b -> names.add(b.identifier()));
        after.blocks().forEach(b -> names.add(b.identifier()));
        List<Op> ops = new ArrayList<>();
        BlockDefinitions applied = before.copy();
        for (String name : names) {
            BlockDefinition old = before.get(name);
            BlockDefinition now = after.get(name);
            List<Op> block = new ArrayList<>();
            if (now == null) {
                block.add(new Remove(name));
            } else if (old == null) {
                block.add(new Add(now));
            } else if (!old.properties().equals(now.properties())) {
                block.addAll(properties(old, now));
            }
            block.forEach(op -> op.apply(applied));
            BlockDefinition result = applied.get(name);
            if (now != null && result != null && result.defaultState() != now.defaultState()) {
                Default change = new Default(name, defaultValues(now), defaultValues(result),
                    now.properties().stream().map(StateValues::name).toList());
                change.apply(applied);
                block.add(change);
            }
            if (!Objects.equals(applied.get(name), now)) {
                throw new IllegalStateException(version + ": the changes " + block + " of " + name + " give " + applied.get(name) + ", not " + now);
            }
            ops.addAll(block);
        }
        return ops;
    }

    /** Properties inserted into the order or removed from it, or else a redefinition. */
    private static List<Op> properties(BlockDefinition old, BlockDefinition now) {
        List<StateValues> was = old.properties();
        List<StateValues> is = now.properties();
        String name = old.identifier();
        if (subsequence(was, is)) {
            List<Op> ops = new ArrayList<>();
            int at = 0;
            for (int i = 0; i < is.size(); ) {
                if (at < was.size() && was.get(at).equals(is.get(i))) {
                    at++;
                    i++;
                    continue;
                }
                int start = i;
                while (i < is.size() && !(at < was.size() && was.get(at).equals(is.get(i)))) {
                    i++;
                }
                ops.add(new AddProperties(name, start, List.copyOf(is.subList(start, i))));
            }
            return ops;
        }
        if (subsequence(is, was)) {
            return was.stream().filter(p -> !is.contains(p)).map(p -> (Op) new RemoveProperty(name, p.name())).toList();
        }
        List<String> wasNames = was.stream().map(StateValues::name).toList();
        List<String> isNames = is.stream().map(StateValues::name).toList();
        String why = new TreeSet<>(wasNames).equals(new TreeSet<>(isNames)) && wasNames.size() == isNames.size()
            ? (wasNames.equals(isNames) ? "other values: " + was + " -> " + is : "another property order: " + wasNames + " -> " + isNames)
            : "other properties: " + was + " -> " + is;
        return List.of(new Redefine(now, why));
    }

    /** Whether {@code shorter} is {@code longer} with some elements left out. */
    private static boolean subsequence(List<StateValues> shorter, List<StateValues> longer) {
        int at = 0;
        for (StateValues value : longer) {
            if (at < shorter.size() && shorter.get(at).equals(value)) {
                at++;
            }
        }
        return at == shorter.size();
    }
}
