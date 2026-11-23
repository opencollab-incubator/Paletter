package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the generated code of one version does, as data: the calls of {@code VanillaVersionBuilder} (through the generated
 * setters) that turn the version before into this one, or nothing into the oldest. {@link Sim} applies them the way the
 * runtime does, so the generator checks that its output gives exactly the model.
 */
final class BehaviourOps {
    private BehaviourOps() {
    }

    /** One call and its setters; {@code values} by key index, a {@link Fit} for blocks, a value (or capture index) else. */
    sealed interface Op permits EveryBlock, DeclareBlock, ChangeBlock, RemoveBlock, EveryItem, DeclareItem, ChangeItem, RemoveItem,
        BlockSourceDefaults, BlockSources, ItemSourceDefaults, ItemSources {
    }

    record EveryBlock(Map<Integer, Object> values) implements Op {
    }

    /** @param replacing whether a block of the identifier with other states is replaced */
    record DeclareBlock(String id, String schema, Map<Integer, Fit> values, boolean replacing) implements Op {
    }

    record ChangeBlock(String id, Map<Integer, Fit> values) implements Op {
    }

    record RemoveBlock(String id) implements Op {
    }

    record EveryItem(Map<Integer, Object> values) implements Op {
    }

    record DeclareItem(String id, Map<Integer, Object> values) implements Op {
    }

    record ChangeItem(String id, Map<Integer, Object> values) implements Op {
    }

    record RemoveItem(String id) implements Op {
    }

    /** @param values by key the capture index, or null for none */
    record BlockSourceDefaults(Map<Integer, Integer> values) implements Op {
    }

    /** @param values by key a fit of capture indexes, or null for the default */
    record BlockSources(String id, Map<Integer, Fit> values) implements Op {
    }

    record ItemSourceDefaults(Map<Integer, Integer> values) implements Op {
    }

    record ItemSources(String id, Map<Integer, Integer> values) implements Op {
    }

    /** The state the runtime builder is in, as far as the generated calls can tell. */
    static final class Sim {
        record SimBlock(String schema, Fit[] values) {
        }

        final Map<String, SimBlock> blocks = new TreeMap<>();
        final Map<String, Object[]> items = new TreeMap<>();
        final Object[] blockDefaults = new Object[BehaviourModel.BLOCK_KEYS.size()];
        final Object[] itemDefaults = new Object[BehaviourModel.ITEM_KEYS.size()];
        final Integer[] blockSourceDefaults = new Integer[BehaviourModel.BLOCK_KEYS.size()];
        final Integer[] itemSourceDefaults = new Integer[BehaviourModel.ITEM_KEYS.size()];
        final Map<String, Fit[]> blockSources = new HashMap<>();
        final Map<String, Integer[]> itemSources = new HashMap<>();

        void apply(Op op) {
            switch (op) {
                case EveryBlock every -> every.values().forEach((k, v) -> {
                    blockDefaults[k] = v;
                    blocks.values().forEach(block -> block.values()[k] = new Constant(v));
                });
                case DeclareBlock declare -> {
                    Fit[] values = new Fit[blockDefaults.length];
                    for (int k = 0; k < values.length; k++) {
                        values[k] = declare.values().getOrDefault(k, new Constant(blockDefaults[k]));
                    }
                    blocks.put(declare.id(), new SimBlock(declare.schema(), values));
                    blockSources.remove(declare.id());
                }
                case ChangeBlock change -> change.values().forEach((k, v) -> blocks.get(change.id()).values()[k] = v);
                case RemoveBlock remove -> {
                    blocks.remove(remove.id());
                    blockSources.remove(remove.id());
                }
                case EveryItem every -> every.values().forEach((k, v) -> {
                    itemDefaults[k] = v;
                    items.values().forEach(item -> item[k] = v);
                });
                case DeclareItem declare -> {
                    Object[] values = itemDefaults.clone();
                    declare.values().forEach((k, v) -> values[k] = v);
                    items.put(declare.id(), values);
                    itemSources.remove(declare.id());
                }
                case ChangeItem change -> change.values().forEach((k, v) -> items.get(change.id())[k] = v);
                case RemoveItem remove -> {
                    items.remove(remove.id());
                    itemSources.remove(remove.id());
                }
                case BlockSourceDefaults defaults -> defaults.values().forEach((k, v) -> blockSourceDefaults[k] = v);
                case BlockSources sources -> sources.values().forEach((k, v) ->
                    blockSources.computeIfAbsent(sources.id(), id -> new Fit[blockDefaults.length])[k] = v);
                case ItemSourceDefaults defaults -> defaults.values().forEach((k, v) -> itemSourceDefaults[k] = v);
                case ItemSources sources -> sources.values().forEach((k, v) ->
                    itemSources.computeIfAbsent(sources.id(), id -> new Integer[itemDefaults.length])[k] = v);
            }
        }

        /** @throws IllegalStateException if the state is not the version's values and captures */
        void check(BehaviourModel.Version version) {
            String name = version.version().name();
            require(blocks.keySet().equals(version.blocks().keySet()), name + ": other blocks");
            require(items.keySet().equals(version.items().keySet()), name + ": other items");
            require(Arrays.equals(blockSourceDefaults, version.blockSources()) && Arrays.equals(itemSourceDefaults, version.itemSources()),
                name + ": other default captures");
            version.blocks().forEach((id, block) -> {
                SimBlock sim = blocks.get(id);
                require(sim.schema().equals(block.schema()) && Arrays.equals(sim.values(), block.fits()), name + ": other values of " + id);
                require(Arrays.equals(orNone(blockSources.get(id), block.sources().length), block.sources()), name + ": other captures of " + id);
            });
            version.items().forEach((id, item) -> {
                require(Arrays.equals(items.get(id), item.values()), name + ": other values of " + id);
                require(Arrays.equals(orNone(itemSources.get(id), item.sources().length), item.sources()), name + ": other captures of " + id);
            });
        }

        private static Object[] orNone(Object @Nullable [] array, int size) {
            return array == null ? new Object[size] : array;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException("The generated calls do not give the model: " + message);
            }
        }
    }
}
