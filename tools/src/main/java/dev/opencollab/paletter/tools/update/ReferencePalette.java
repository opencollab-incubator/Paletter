package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.Versions;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.internal.data.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** The palette block hashes are resolved against, and the state order inferred for blocks that are new. */
final class ReferencePalette {
    private final UpdateContext ctx;

    ReferencePalette(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The palette block hashes are resolved against: the bundled version of the protocol, or for a new protocol the
     * newest bundled release adjusted with the Script API: removed blocks dropped, new blocks and blocks whose states
     * changed generated from their Script API states (legacy alias states, known from the baseline dump of that
     * release, left out). Within a generated block the state order follows {@link #keyOrder}; it is only partly
     * observable (creative stacks), but the order of blocks, and so every other block's sequential id, does not depend
     * on it. The block hash and sequential order checks verify the result.
     */
    VanillaData reference(String existing, BdsDump dump, Runs runs) throws IOException {
        if (existing != null) {
            runs.referenceName = existing;
            return BundledFiles.read(ctx.resources.resolve(existing + ".nbt"));
        }
        Importer.IndexEntry latest = Importer.readIndex(ctx.resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
        runs.referenceName = latest.name();
        VanillaData data = BundledFiles.read(ctx.resources.resolve(latest.name() + ".nbt"));
        Path baselineFile = latestDump();
        BdsDump baseline = baselineFile == null ? null : BdsDump.parse(UpdateContext.readGzipText(baselineFile), baselineFile.getFileName().toString());

        Map<String, List<NbtMap>> palette = new LinkedHashMap<>();
        data.blockStates().forEach(state -> palette.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        Map<String, List<String>> strides = new LinkedHashMap<>();
        palette.forEach((name, blockStates) -> strides.put(name, strideOrder(blockStates)));
        List<NbtMap> states = new ArrayList<>();
        List<String> generated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        palette.forEach((name, blockStates) -> {
            Map<String, List<Object>> scriptStates = dump.blockStates().get(name);
            if (!dump.blockStates().isEmpty() && scriptStates == null) {
                removed.add(name);
                return;
            }
            // Unchanged in the Script API since the baseline: keep the palette (it knows better, e.g. minecraft:chalkboard)
            Map<String, List<Object>> before = baseline == null ? null : baseline.blockStates().get(name);
            Map<String, List<Object>> current = values(blockStates);
            Map<String, List<Object>> network = scriptStates == null || (before != null && sameValues(before, scriptStates))
                ? null : networkStates(name, scriptStates, baseline, current.keySet());
            if (network == null || sameValues(network, current)) {
                states.addAll(blockStates);
                return;
            }
            // Existing values keep their order, new ones are appended
            Map<String, List<Object>> merged = new LinkedHashMap<>();
            network.forEach((key, values) -> {
                List<Object> list = new ArrayList<>();
                current.getOrDefault(key, List.of()).stream().filter(v -> contains(values, v)).forEach(list::add);
                values.stream().filter(v -> !contains(list, v)).forEach(list::add);
                merged.put(key, list);
            });
            List<String> keys = keyOrder(strides.get(name), new ArrayList<>(network.keySet()), strides);
            List<NbtMap> combinations = combinations(name, slowestFirst(keys, merged));
            states.addAll(combinations);
            generated.add(name + " (" + blockStates.size() + " -> " + combinations.size() + " states)");
        });
        // New blocks take the value order BDS lists the state type in (BdsDump#stateTypes). A dump before format 4 has
        // none (it was sorted), then the order other blocks with the same state use stands in (e.g. minecraft:corner
        // "none" first, like every vanilla stairs block)
        Map<String, List<Object>> valueOrder = new LinkedHashMap<>();
        palette.values().forEach(blockStates -> values(blockStates).forEach(valueOrder::putIfAbsent));
        dump.blockStates().forEach((name, scriptValues) -> {
            if (!palette.containsKey(name)) {
                Map<String, List<Object>> values = new LinkedHashMap<>();
                scriptValues.forEach((key, list) -> {
                    List<Object> order = dump.stateTypes().getOrDefault(key, valueOrder.getOrDefault(key, List.of()));
                    List<Object> sorted = new ArrayList<>();
                    order.stream().filter(v -> contains(list, v)).forEach(v -> sorted.add(list.stream()
                        .filter(w -> contains(List.of(v), w)).findFirst().orElseThrow()));
                    list.stream().filter(v -> !contains(sorted, v)).forEach(sorted::add);
                    values.put(key, sorted);
                });
                List<NbtMap> combinations = combinations(name, slowestFirst(
                    keyOrder(similarBlockOrder(values.keySet(), strides), new ArrayList<>(values.keySet()), strides), values));
                states.addAll(combinations);
                generated.add(name + " (new, " + combinations.size() + " states)");
            }
        });
        states.sort(Comparator.comparing((NbtMap state) -> BlockRegistry.nameHash(BlockStates.name(state)), Long::compareUnsigned));
        if (!generated.isEmpty() || !removed.isEmpty()) {
            ctx.report.check(Report.Status.INFO, "Reference palette", latest.name() + " adjusted with the Script API: "
                + (generated.isEmpty() ? "" : "generated " + String.join(", ", generated)) + (removed.isEmpty() ? "" : "; removed " + removed));
            if (!generated.isEmpty()) {
                ctx.report.todo("Block palette: the states of " + generated.size() + " blocks were generated from the Script API; their order "
                    + "within each block is inferred (Update#keyOrder) and only creative stacks verify it", "Generated blocks",
                    generated.stream().map(block -> "- " + block).toList());
            }
        }
        return new VanillaData(new DataVersion(latest.name() + "+", 0), states, data.items(), data.dataDrivenBlocks(), data.creative(),
            data.itemTags(), data.fallbacks(), data.blockDefaults(), data.experiments(), data.blockItems());
    }

    private static final List<String> DIRECTIONS = List.of("north", "east", "south", "west", "up", "down");

    /**
     * The order in which BDS enumerates a generated block's states, as keys from the fastest varying (stride 1) to the
     * slowest. Bedrock enumerates every combination with the first registered state varying fastest; registration
     * order cannot be observed, so it is inferred from the reference palette (replayed on 26.40 -> 26.50 -> 26.60 in
     * KeyOrderTest: right for 126 of the 127 blocks whose states changed or that are new with several states):
     * <ol>
     *     <li>the keys of {@code base} (the block's own order in the reference palette, or that of the most similar
     *     block for a new block) stay first, in their order: states added to an existing block vary slower than the old
     *     ones (stairs gained {@code minecraft:corner} as their slowest state in 26.50, fire and redstone wire their
     *     connections in 26.60; icicle, new in 26.60, starts like pointed dripstone). The exception seen:
     *     {@code minecraft:trip_wire} put the connections it gained in 26.50 first;</li>
     *     <li>the other keys follow, each time the one that the fewest remaining keys precede in other blocks of the
     *     reference palette (e.g. {@code minecraft:connection_north} before {@code _east} on 58 blocks of 26.50);</li>
     *     <li>without such evidence, keys that only differ by a direction suffix go north, east, south, west, up, down
     *     (every such family in the palette: walls, pale moss carpet, connections, redstone wire), and otherwise the
     *     order of {@code keys} (the Script API's, which matches the palette for only about 64% of blocks).</li>
     * </ol>
     *
     * @param base known order of some of the keys, fastest first; may be null or contain keys not in {@code keys}
     * @param keys every key of the block
     * @param reference fastest first key order of each block of the reference palette
     */
    static List<String> keyOrder(List<String> base, List<String> keys, Map<String, List<String>> reference) {
        List<String> order = new ArrayList<>();
        if (base != null) {
            base.stream().filter(keys::contains).forEach(order::add);
        }
        List<String> remaining = new ArrayList<>(keys);
        remaining.removeAll(order);
        Map<String, Integer> precedes = new java.util.HashMap<>();
        for (List<String> blockKeys : reference.values()) {
            for (int i = 0; i < blockKeys.size(); i++) {
                for (int j = i + 1; j < blockKeys.size(); j++) {
                    precedes.merge(blockKeys.get(i) + '\0' + blockKeys.get(j), 1, Integer::sum);
                }
            }
        }
        while (!remaining.isEmpty()) {
            String best = null;
            int bestBeaten = Integer.MAX_VALUE;
            for (String key : remaining) {
                int beaten = 0;
                for (String other : remaining) {
                    if (!other.equals(key) && precedes.getOrDefault(other + '\0' + key, 0) > precedes.getOrDefault(key + '\0' + other, 0)) {
                        beaten++;
                    }
                }
                if (beaten < bestBeaten || (beaten == bestBeaten && directionBefore(key, best))) {
                    best = key;
                    bestBeaten = beaten;
                }
            }
            order.add(best);
            remaining.remove(best);
        }
        return order;
    }

    /** Whether {@code key} and {@code other} only differ by a direction suffix, {@code key}'s coming first. */
    private static boolean directionBefore(String key, String other) {
        int a = key.lastIndexOf('_');
        int b = other.lastIndexOf('_');
        if (a < 0 || b < 0 || !key.substring(0, a).equals(other.substring(0, b))) {
            return false;
        }
        int x = DIRECTIONS.indexOf(key.substring(a + 1));
        int y = DIRECTIONS.indexOf(other.substring(b + 1));
        return x >= 0 && y >= 0 && x < y;
    }

    /** The key order of the reference block with the most keys that are all keys of a new block, or null. */
    static List<String> similarBlockOrder(Set<String> keys, Map<String, List<String>> reference) {
        List<String> best = null;
        for (List<String> order : reference.values()) {
            if (!order.isEmpty() && keys.containsAll(order) && (best == null || order.size() > best.size())) {
                best = order;
            }
        }
        return best;
    }

    /** The keys of a block's states from the fastest varying to the slowest (the first index at which each changes). */
    static List<String> strideOrder(List<NbtMap> states) {
        Map<String, Integer> firstChange = new LinkedHashMap<>();
        NbtMap first = BlockStates.states(states.get(0));
        for (String key : first.keySet()) {
            for (int i = 1; i < states.size(); i++) {
                if (!java.util.Objects.equals(BlockStates.states(states.get(i)).get(key), first.get(key))) {
                    firstChange.put(key, i);
                    break;
                }
            }
        }
        List<String> keys = new ArrayList<>(firstChange.keySet());
        keys.sort(Comparator.comparingInt(firstChange::get));
        return keys;
    }

    /** {@link #combinations} varies the last key fastest. */
    private static Map<String, List<Object>> slowestFirst(List<String> fastestFirst, Map<String, List<Object>> values) {
        Map<String, List<Object>> ordered = new LinkedHashMap<>();
        for (int i = fastestFirst.size() - 1; i >= 0; i--) {
            ordered.put(fastestFirst.get(i), values.get(fastestFirst.get(i)));
        }
        return ordered;
    }

    /**
     * The Script API states of a block minus its legacy alias states: the states the baseline dump reported for the
     * block that the palette of the same release does not have. Without a baseline, only the palette's states count.
     */
    private static Map<String, List<Object>> networkStates(String name, Map<String, List<Object>> script, BdsDump baseline,
                                                           Set<String> paletteKeys) {
        Map<String, List<Object>> before = baseline == null ? null : baseline.blockStates().get(name);
        Set<String> aliases = new TreeSet<>((before != null ? before : script).keySet());
        aliases.removeAll(paletteKeys);
        Map<String, List<Object>> network = new LinkedHashMap<>();
        script.forEach((key, values) -> {
            if (!aliases.contains(key)) {
                network.put(key, values);
            }
        });
        return network;
    }

    private static Map<String, List<Object>> values(List<NbtMap> states) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (NbtMap state : states) {
            BlockStates.states(state).forEach((key, value) -> {
                List<Object> list = values.computeIfAbsent(key, k -> new ArrayList<>());
                if (!contains(list, value)) {
                    list.add(value);
                }
            });
        }
        return values;
    }

    private static boolean sameValues(Map<String, List<Object>> a, Map<String, List<Object>> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (String key : a.keySet()) {
            List<Object> x = a.get(key);
            List<Object> y = b.get(key);
            if (x.size() != y.size() || !x.stream().allMatch(v -> contains(y, v))) {
                return false;
            }
        }
        return true;
    }

    /** Script API booleans arrive as bytes and numbers as ints: compare numbers by value. */
    private static boolean contains(List<Object> values, Object value) {
        for (Object candidate : values) {
            if (candidate instanceof Number x && value instanceof Number y ? x.intValue() == y.intValue() : candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    static List<NbtMap> combinations(String name, Map<String, List<Object>> values) {
        List<NbtMap> combinations = new ArrayList<>();
        combinations.add(NbtMap.EMPTY);
        for (Map.Entry<String, List<Object>> state : values.entrySet()) {
            List<NbtMap> next = new ArrayList<>();
            for (NbtMap partial : combinations) {
                for (Object value : state.getValue()) {
                    org.cloudburstmc.nbt.NbtMapBuilder builder = partial.toBuilder();
                    builder.put(state.getKey(), value);
                    next.add(builder.build());
                }
            }
            combinations = next;
        }
        // BDS's palette has every state's keys sorted (whatever the enumeration order); creative stacks resolved
        // through this palette carry these instances, so match it byte for byte
        return combinations.stream().map(states -> BlockStates.of(name, NbtMap.fromMap(new java.util.TreeMap<>(states)))).toList();
    }

    private Path latestDump() throws IOException {
        if (!Files.isDirectory(ctx.bdsData)) {
            return null;
        }
        Set<String> skip = ctx.previewBuilds();
        try (Stream<Path> dirs = Files.list(ctx.bdsData)) {
            return dirs.filter(d -> !skip.contains(d.getFileName().toString())).map(d -> d.resolve("dump.json.gz")).filter(Files::exists)
                .max(Comparator.comparing(f -> f.getParent().getFileName().toString(), Versions.ORDER)).orElse(null);
        }
    }
}
