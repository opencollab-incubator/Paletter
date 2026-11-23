package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.internal.behaviour.BlockSchema;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The behaviour values of each generated version as the generated code holds them: per block a schema and per key a
 * {@link Fit} of its states' values, and the capture that decided them (a default per key and version, and per block or
 * item where it differs). Computed from the capture store with its own lookup, so the generated values are the store's
 * by construction (and {@code GeneratedBehaviourTest} checks it). Every state's value and capture is kept for the comments.
 */
final class BehaviourModel {
    static final List<BehaviourKey<?>> BLOCK_KEYS = BlockBehaviour.keys();
    static final List<BehaviourKey<?>> ITEM_KEYS = ItemBehaviour.keys();

    /**
     * One block of one version.
     *
     * @param states the properties of each state, in {@link BlockSchema#ORDER} of their values
     * @param values per key the value of each state (null: unknown)
     * @param captures per key the deciding capture of each state (-1 where the value is unknown)
     * @param fits per key the values as a function of the states
     * @param sources per key the captures where they are not the version's default, as a function of the states; null: the default
     */
    record Block(String id, String schema, List<Map<String, Object>> states, int[] hashes, Object[][] values, int[][] captures,
                 Fit[] fits, @Nullable Fit[] sources) {
    }

    /** @param sources per key the capture where it is not the version's default, else null */
    record Item(String id, Object[] values, int[] captures, @Nullable Integer[] sources) {
    }

    /**
     * @param blockSources per key the capture most block states with a value of the key have (null: none has one)
     * @param schemas the schema of every block of the palette, with values or not
     * @param paletteItems every item of the palette, with values or not
     */
    record Version(DataVersion version, Map<String, Block> blocks, Map<String, Item> items, @Nullable Integer[] blockSources,
                   @Nullable Integer[] itemSources, Map<String, String> schemas, java.util.Set<String> paletteItems) {
    }

    /** A note ({@code alternative} null) or a dispute of a capture, for a block (a fit over the states it decides) or an item. */
    record Mark(int capture, BehaviourKey<?> key, String id, @Nullable Integer alternative, Fit value) {
    }

    final BehaviourStore store;
    final List<Version> versions = new ArrayList<>();
    final List<Mark> marks;
    /** The properties of every state of a generated version, by hash. */
    private final Map<Integer, NbtMap> stateByHash = new HashMap<>();
    private final MarkCollector collector = new MarkCollector();

    /** @param bundled the palettes of the versions to generate, oldest first */
    BehaviourModel(BehaviourStore store, List<BehaviourImport.Bundled> bundled) {
        this.store = store;
        for (BehaviourImport.Bundled palette : bundled) {
            versions.add(version(palette));
        }
        marks = collector.marks(stateByHash);
    }

    private Version version(BehaviourImport.Bundled palette) {
        DataVersion version = palette.version();
        Map<String, List<Integer>> byBlock = new TreeMap<>();
        palette.states().forEach((hash, state) -> {
            byBlock.computeIfAbsent(state.getString("name"), n -> new ArrayList<>()).add(hash);
            stateByHash.put(hash, state);
        });
        Map<String, Block> blocks = new LinkedHashMap<>();
        List<Map<Integer, Integer>> counts = new ArrayList<>();
        BLOCK_KEYS.forEach(k -> counts.add(new HashMap<>()));
        Map<String, String> schemas = new TreeMap<>();
        byBlock.forEach((id, hashes) -> {
            schemas.put(id, BlockSchema.of(hashes.stream().map(hash -> stateByHash.get(hash).getCompound("states")).toList()));
            Block block = block(id, hashes, version, counts);
            if (block != null) {
                blocks.put(id, block);
            }
        });
        Integer[] blockSources = modes(counts);
        blocks.replaceAll((id, block) -> withSources(block, blockSources));
        Map<String, Item> items = new LinkedHashMap<>();
        List<Map<Integer, Integer>> itemCounts = new ArrayList<>();
        ITEM_KEYS.forEach(k -> itemCounts.add(new HashMap<>()));
        for (String id : palette.items().stream().sorted().toList()) {
            Object[] values = new Object[ITEM_KEYS.size()];
            int[] captures = new int[ITEM_KEYS.size()];
            boolean any = false;
            for (int k = 0; k < values.length; k++) {
                BehaviourStore.Resolved resolved = store.item(ITEM_KEYS.get(k), id, version);
                captures[k] = resolved == null ? -1 : resolved.capture().index();
                values[k] = resolved == null ? null : resolved.value();
                if (resolved != null) {
                    any = true;
                    itemCounts.get(k).merge(captures[k], 1, Integer::sum);
                    collector.item(resolved, ITEM_KEYS.get(k), id);
                }
            }
            if (any) {
                items.put(id, new Item(id, values, captures, null));
            }
        }
        Integer[] itemSources = modes(itemCounts);
        items.replaceAll((id, item) -> {
            Integer[] sources = new Integer[ITEM_KEYS.size()];
            for (int k = 0; k < sources.length; k++) {
                sources[k] = item.captures()[k] < 0 || item.captures()[k] == itemSources[k] ? null : item.captures()[k];
            }
            return new Item(id, item.values(), item.captures(), sources);
        });
        return new Version(version, Collections.unmodifiableMap(blocks), Collections.unmodifiableMap(items), blockSources, itemSources,
            Collections.unmodifiableMap(schemas), java.util.Set.copyOf(palette.items()));
    }

    private @Nullable Block block(String id, List<Integer> unsorted, DataVersion version, List<Map<Integer, Integer>> counts) {
        List<NbtMap> compounds = unsorted.stream().map(hash -> stateByHash.get(hash).getCompound("states")).toList();
        List<Map<String, Object>> properties = compounds.stream().map(BehaviourModel::properties).toList();
        checkEveryCombination(id, version, properties);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < unsorted.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> compareStates(properties.get(a), properties.get(b)));
        List<Map<String, Object>> states = order.stream().map(properties::get).toList();
        int[] hashes = order.stream().mapToInt(unsorted::get).toArray();
        Object[][] values = new Object[BLOCK_KEYS.size()][hashes.length];
        int[][] captures = new int[BLOCK_KEYS.size()][hashes.length];
        boolean any = false;
        for (int k = 0; k < values.length; k++) {
            BehaviourKey<?> key = BLOCK_KEYS.get(k);
            for (int s = 0; s < hashes.length; s++) {
                BehaviourStore.Resolved resolved = store.block(key, hashes[s], version);
                values[k][s] = resolved == null ? null : resolved.value();
                captures[k][s] = resolved == null ? -1 : resolved.capture().index();
                if (resolved != null) {
                    any = true;
                    counts.get(k).merge(captures[k][s], 1, Integer::sum);
                    collector.block(resolved, key, id, hashes[s]);
                }
            }
        }
        if (!any) {
            return null;
        }
        Fit[] fits = new Fit[values.length];
        for (int k = 0; k < values.length; k++) {
            fits[k] = CellFit.fit(states, java.util.Arrays.asList(values[k]));
        }
        return new Block(id, BlockSchema.of(compounds), states, hashes, values, captures, fits, null);
    }

    private static Block withSources(Block block, Integer[] defaults) {
        Fit[] sources = new Fit[BLOCK_KEYS.size()];
        for (int k = 0; k < sources.length; k++) {
            List<Object> captures = new ArrayList<>();
            boolean own = false;
            for (int capture : block.captures()[k]) {
                captures.add(capture < 0 ? CellFit.ANY : capture);
                own |= capture >= 0 && !Integer.valueOf(capture).equals(defaults[k]);
            }
            sources[k] = own ? CellFit.fit(block.states(), captures) : null;
        }
        return new Block(block.id(), block.schema(), block.states(), block.hashes(), block.values(), block.captures(), block.fits(), sources);
    }

    /** @return per key the most frequent capture (the lowest index of a tie), or null if there is none */
    private static Integer[] modes(List<Map<Integer, Integer>> counts) {
        Integer[] modes = new Integer[counts.size()];
        for (int k = 0; k < modes.length; k++) {
            modes[k] = new TreeMap<>(counts.get(k)).entrySet().stream()
                .reduce((a, b) -> b.getValue() > a.getValue() ? b : a).map(Map.Entry::getKey).orElse(null);
        }
        return modes;
    }

    /** @return the BDS defaults of custom content, by key */
    Map<BehaviourKey<?>, BehaviourStore.Default> customDefaults() {
        Map<BehaviourKey<?>, BehaviourStore.Default> defaults = new LinkedHashMap<>();
        for (BehaviourKey<?> key : BLOCK_KEYS) {
            if (store.defaultOf(key) != null) {
                defaults.put(key, store.defaultOf(key));
            }
        }
        for (BehaviourKey<?> key : ITEM_KEYS) {
            if (store.defaultOf(key) != null) {
                defaults.put(key, store.defaultOf(key));
            }
        }
        return defaults;
    }

    /** @return the properties of a state as the generated code reads them, by name */
    static Map<String, Object> properties(NbtMap states) {
        Map<String, Object> properties = new TreeMap<>();
        states.forEach((name, value) -> properties.put(name, BlockSchema.value(value)));
        return Collections.unmodifiableMap(properties);
    }

    private static int compareStates(Map<String, Object> a, Map<String, Object> b) {
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            int c = BlockSchema.ORDER.compare(entry.getValue(), b.get(entry.getKey()));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The schema of a block stands for its states only if they are every combination of its property values. */
    private static void checkEveryCombination(String id, DataVersion version, List<Map<String, Object>> states) {
        int combinations = CellFit.domains(states).values().stream().mapToInt(List::size).reduce(1, (a, b) -> a * b);
        if (combinations != states.size()) {
            throw new IllegalStateException(id + " in " + version.name() + " has " + states.size() + " states, not every combination of its "
                + "property values (" + combinations + "): its schema does not name its states");
        }
    }
}
