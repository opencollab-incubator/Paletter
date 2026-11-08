package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.client.CapturedData;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The one diff engine of the tooling: a list of named sections of difference lines, and the comparisons that fill it.
 * Empty sections mean no difference; {@code info} lines never count as one.
 * <ul>
 *   <li>{@link #vanilla}: two versions of vanilla data (bundled vs freshly captured, previous vs new): blocks and their
 *       states, items, item components, data driven blocks, creative layout, item tags, block defaults</li>
 *   <li>{@link #scriptApi}: two Script API dumps of BDS</li>
 *   <li>{@link #capture}: a network capture against a bundled version (the {@code compare} command)</li>
 *   <li>{@link #bdsDump}: a Script API dump against a bundled version (the {@code bds-compare} command)</li>
 *   <li>{@link #source}: a directory in the CloudburstMC/Data layout against a bundled version, every aspect the bundled
 *       format keeps compared exactly (the {@code verify} command)</li>
 *   <li>{@link #shapes}: the wire shapes of components of two captures ({@link WireShapes})</li>
 * </ul>
 * Output: {@link #print} (console) and {@link #markdown} (report).
 */
public final class Diff {
    public static final int DEFAULT_MAX_LINES = 25;
    public static final String WIRE_CHANGES = "wire format changes";
    public static final String NEW_COMPONENTS = "new components";
    private static final int PRINT_LIMIT = 40;

    /** A section of the report: its differences, and information that does not count as one. */
    static final class Section {
        final String title;
        final List<String> differences = new ArrayList<>();
        final List<String> info = new ArrayList<>();
        String bundled = "";
        String source = "";

        Section(String title) {
            this.title = title;
        }
    }

    final Map<String, Section> sections = new LinkedHashMap<>();
    /** What the markdown report says it compared (set by {@link #source}). */
    private String sourceLabel = "";
    private String bundledLabel = "";
    private String name = "";

    public boolean identical() {
        return sections.values().stream().allMatch(section -> section.differences.isEmpty());
    }

    public int count() {
        return sections.values().stream().mapToInt(section -> section.differences.size()).sum();
    }

    /** The difference lines of a section, empty if there is none. */
    public List<String> lines(String title) {
        Section section = sections.get(title);
        return section == null ? List.of() : section.differences;
    }

    /** All sections with their difference lines. */
    public Map<String, List<String>> lines() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        sections.forEach((title, section) -> out.put(title, section.differences));
        return out;
    }

    Section section(String title) {
        return sections.computeIfAbsent(title, Section::new);
    }

    private void put(String title, Collection<String> lines) {
        section(title).differences.addAll(lines);
    }

    /** Like {@link #put}, but nothing for no lines. */
    private void putIfAny(String title, Collection<String> lines) {
        if (!lines.isEmpty()) {
            put(title, lines);
        }
    }

    /** Like {@link #put}, repeated lines once. */
    private void putDistinct(String title, Collection<String> lines) {
        put(title, new LinkedHashSet<>(lines));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Output

    /** {@code title: n} and the first lines of every section, to the console. */
    public void print(PrintStream out) {
        sections.values().forEach(section -> {
            out.println("  " + section.title + ": " + section.differences.size());
            section.differences.stream().limit(PRINT_LIMIT).forEach(line -> out.println("      " + line));
            if (section.differences.size() > PRINT_LIMIT) {
                out.println("      ... " + (section.differences.size() - PRINT_LIMIT) + " more");
            }
        });
    }

    /** The report of {@link #source}: a table of the sections and the differences of each. */
    public String markdown(int maxLines) {
        StringBuilder out = new StringBuilder();
        out.append("# Paletter data verification: ").append(name).append("\n\n");
        out.append("- **Source**: `").append(sourceLabel).append("`\n");
        out.append("- **Bundled**: `").append(bundledLabel).append("`\n");
        out.append("- **Result**: ").append(identical() ? "IDENTICAL" : "DIFFERENT (" + count() + " differences)").append("\n\n");
        out.append("| Section | Bundled | Source | Differences |\n|---|---|---|---|\n");
        sections.values().forEach(section -> out.append("| ").append(section.title).append(" | ").append(section.bundled).append(" | ")
            .append(section.source).append(" | ").append(section.differences.size()).append(" |\n"));
        for (Section section : sections.values()) {
            if (section.differences.isEmpty() && section.info.isEmpty()) {
                continue;
            }
            out.append("\n## ").append(section.title).append("\n\n");
            section.info.forEach(line -> out.append("- info: ").append(line).append('\n'));
            section.differences.stream().limit(maxLines).forEach(line -> out.append("- `").append(line.replace("`", "'")).append("`\n"));
            if (section.differences.size() > maxLines) {
                out.append("- ... ").append(section.differences.size() - maxLines).append(" more\n");
            }
        }
        return out.toString();
    }

    public void write(Path file, int maxLines) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, markdown(maxLines), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparisons

    /** Two versions of vanilla data; {@code network} compares what the network sends, {@code bds} what only the Script API knows. */
    public static Diff vanilla(VanillaData before, VanillaData after, boolean network, boolean bds) {
        Diff diff = new Diff();
        if (network) {
            diff.blocks(before, after);
            diff.items(before, after);
            diff.dataDriven(before, after);
            diff.creative(before, after);
        }
        if (bds) {
            diff.tags(before.itemTags(), after.itemTags());
            diff.defaults(before, after);
            diff.blockItems(before, after);
        }
        return diff;
    }

    /** A network capture against a bundled version: items, item components, block properties, creative layout. */
    public static Diff capture(CapturedData capture, VanillaData reference) {
        Diff diff = new Diff();
        diff.captureItems(capture, reference);
        diff.captureBlocks(capture, reference);
        diff.captureCreative(capture, reference);
        return diff;
    }

    /** A Script API dump against a bundled version: block states and defaults, items and stack sizes, item tags. */
    public static Diff bdsDump(BdsDump dump, VanillaData data, Map<String, Map<String, List<String>>> tagSources) {
        Diff diff = new Diff();
        if (!dump.errors().isEmpty()) {
            diff.putDistinct("addon errors", dump.errors());
        }
        diff.bdsBlocks(dump, data);
        diff.bdsItems(dump, data);
        tagSources.forEach((tagName, tags) -> diff.bdsTags(tagName, dump.itemTags(), tags));
        return diff;
    }

    /**
     * A directory in the CloudburstMC/Data layout (or Geyser's versioned file names) against a bundled Paletter version, in
     * memory. Every aspect the bundled format keeps is compared exactly: block states and their order (blocks in FNV-1 64
     * name order, states within each block), hashed network ids, items, item component NBT (including tag types), data
     * driven blocks and the creative layout.
     * <p>
     * Not compared, because the bundled format does not keep them: the order of {@code runtime_item_states} (items are
     * keyed by runtime id; the importer sorts them by identifier), the per state {@code version} tag (reported as
     * information), item tags, block defaults and fallbacks, and how the creative inventory is split into wire groups
     * across categories ({@code groupId}, {@code block_runtime_id}).
     *
     * @param name the version name, e.g. {@code 26.60} (also picks Geyser's {@code *.26_60.*} files)
     * @param bundledLabel what {@code bundled} is, for the report
     */
    public static Diff source(Path source, String name, VanillaData bundled, String bundledLabel) throws IOException {
        Diff diff = new Diff();
        diff.sourceLabel = source.toString();
        diff.bundledLabel = bundledLabel;
        diff.name = name;
        Importer.Source read = Importer.readSource(source, name);
        diff.sourceBlocks(read, bundled);
        diff.sourceHashes(read, bundled);
        diff.sourceItems(read, bundled);
        diff.sourceComponents(read, bundled);
        diff.sourceDataDriven(read, bundled);
        diff.sourceCreative(read, bundled);
        return diff;
    }

    /**
     * Component wire shapes of two sets of captures, per component (see {@link WireShapes}): an added, removed or retyped
     * field, or a component that is no longer sent, is a wire format change; a component not sent before is new.
     *
     * @param before component to (path to type)
     */
    public static Diff shapes(Map<String, Map<String, String>> before, Map<String, Map<String, String>> after) {
        Diff diff = new Diff();
        List<String> wireChanges = new ArrayList<>();
        List<String> newComponents = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String component : names) {
            Map<String, String> x = before.get(component);
            Map<String, String> y = after.get(component);
            if (x == null) {
                newComponents.add(component);
            } else if (y == null) {
                wireChanges.add(component + ": no longer sent");
            } else {
                Set<String> paths = new TreeSet<>(x.keySet());
                paths.addAll(y.keySet());
                for (String path : paths) {
                    String a = x.get(path);
                    String b = y.get(path);
                    String where = component + (path.isEmpty() ? "" : " " + path);
                    if (a == null) {
                        wireChanges.add(where + ": new field (" + b + ")");
                    } else if (b == null) {
                        wireChanges.add(where + ": field removed (was " + a + ")");
                    } else if (!a.equals(b)) {
                        wireChanges.add(where + ": type " + a + " -> " + b);
                    }
                }
            }
        }
        diff.put(WIRE_CHANGES, wireChanges);
        diff.put(NEW_COMPONENTS, newComponents);
        return diff;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Vanilla data (vanilla)

    private void blocks(VanillaData before, VanillaData after) {
        Map<String, List<NbtMap>> a = statesByBlock(before.blockStates());
        Map<String, List<NbtMap>> b = statesByBlock(after.blockStates());
        put("new blocks", difference(b.keySet(), a.keySet()).stream()
            .map(name -> name + " (" + b.get(name).size() + " states)").toList());
        put("removed blocks", difference(a.keySet(), b.keySet()).stream().toList());
        List<String> changed = new ArrayList<>();
        for (String name : new TreeSet<>(a.keySet())) {
            List<NbtMap> x = a.get(name);
            List<NbtMap> y = b.get(name);
            if (y == null || x.equals(y)) {
                continue;
            }
            Set<NbtMap> added = new LinkedHashSet<>(y);
            x.forEach(added::remove);
            Set<NbtMap> removed = new LinkedHashSet<>(x);
            y.forEach(removed::remove);
            if (added.isEmpty() && removed.isEmpty()) {
                changed.add(name + ": same states, different order");
            } else {
                changed.add(name + ": " + x.size() + " -> " + y.size() + " states; state keys " + keys(x) + " -> " + keys(y)
                    + (added.isEmpty() ? "" : "; e.g. new " + BlockStates.states(added.iterator().next())));
            }
        }
        put("blocks with changed states", changed);
        if (changed.isEmpty() && a.keySet().equals(b.keySet()) && !before.blockStates().equals(after.blockStates())) {
            put("block palette order", List.of("same states, different palette order (sequential runtime ids change)"));
        }
    }

    private void items(VanillaData before, VanillaData after) {
        Map<String, VanillaItem> a = itemMap(before);
        Map<String, VanillaItem> b = itemMap(after);
        put("new items", difference(b.keySet(), a.keySet()).stream()
            .map(name -> name + " (id " + b.get(name).runtimeId() + (b.get(name).componentBased() ? ", component based" : "") + ")").toList());
        put("removed items", List.copyOf(difference(a.keySet(), b.keySet())));
        List<String> ids = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> components = new ArrayList<>();
        a.forEach((name, x) -> {
            VanillaItem y = b.get(name);
            if (y == null) {
                return;
            }
            if (x.runtimeId() != y.runtimeId()) {
                ids.add(name + ": " + x.runtimeId() + " -> " + y.runtimeId());
            }
            if (x.version() != y.version() || x.componentBased() != y.componentBased()) {
                flags.add(name + ": version " + x.version() + " -> " + y.version() + ", componentBased " + x.componentBased() + " -> " + y.componentBased());
            }
            List<String> lines = new ArrayList<>();
            nbt("", x.componentData() == null ? NbtMap.EMPTY : x.componentData(), y.componentData() == null ? NbtMap.EMPTY : y.componentData(), lines);
            if (!lines.isEmpty()) {
                components.add(name + ": " + String.join("; ", lines));
            }
        });
        put("item runtime id changes", ids);
        put("item version/componentBased changes", flags);
        put("item component data changes", components);
    }

    private void dataDriven(VanillaData before, VanillaData after) {
        Map<String, NbtMap> a = new TreeMap<>();
        before.dataDrivenBlocks().forEach(block -> a.put(block.getName(), block.getProperties()));
        Map<String, NbtMap> b = new TreeMap<>();
        for (BlockPropertyData block : after.dataDrivenBlocks()) {
            b.put(block.getName(), block.getProperties());
        }
        put("new data driven blocks", List.copyOf(difference(b.keySet(), a.keySet())));
        put("removed data driven blocks", List.copyOf(difference(a.keySet(), b.keySet())));
        List<String> changed = new ArrayList<>();
        a.forEach((name, x) -> {
            NbtMap y = b.get(name);
            if (y != null) {
                List<String> lines = new ArrayList<>();
                nbt("", x, y, lines);
                if (!lines.isEmpty()) {
                    changed.add(name + ": " + String.join("; ", lines));
                }
            }
        });
        put("data driven block property changes", changed);
    }

    private void creative(VanillaData before, VanillaData after) {
        List<String> lines = new ArrayList<>();
        for (CreativeCategory category : CreativeCategory.values()) {
            List<String> x = flatten(before.creative().nodes(category));
            List<String> y = flatten(after.creative().nodes(category));
            if (x.equals(y)) {
                continue;
            }
            Set<String> added = difference(new LinkedHashSet<>(y), new LinkedHashSet<>(x));
            Set<String> removed = difference(new LinkedHashSet<>(x), new LinkedHashSet<>(y));
            lines.add(category + ": " + x.size() + " -> " + y.size() + " entries");
            added.forEach(entry -> lines.add(category + " + " + entry));
            removed.forEach(entry -> lines.add(category + " - " + entry));
            if (added.isEmpty() && removed.isEmpty()) {
                for (int i = 0; i < x.size(); i++) {
                    if (!x.get(i).equals(y.get(i))) {
                        lines.add(category + " order differs from #" + i + ": " + x.get(i) + " -> " + y.get(i));
                        break;
                    }
                }
            }
        }
        put("creative layout", lines);
    }

    private void tags(Map<String, List<String>> before, Map<String, List<String>> after) {
        put("new item tags", List.copyOf(difference(after.keySet(), before.keySet())));
        put("removed item tags", List.copyOf(difference(before.keySet(), after.keySet())));
        List<String> changed = new ArrayList<>();
        for (String tag : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(tag)) {
                continue;
            }
            Set<String> added = difference(new TreeSet<>(after.get(tag)), new TreeSet<>(before.get(tag)));
            Set<String> removed = difference(new TreeSet<>(before.get(tag)), new TreeSet<>(after.get(tag)));
            if (!added.isEmpty() || !removed.isEmpty()) {
                changed.add(tag + ": +" + added + " -" + removed);
            }
        }
        put("item tag membership", changed);
    }

    private void blockItems(VanillaData before, VanillaData after) {
        List<String> changed = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockItems().keySet());
        names.addAll(after.blockItems().keySet());
        for (String name : names) {
            String x = before.blockItems().get(name);
            String y = after.blockItems().get(name);
            if (!Objects.equals(x, y)) {
                changed.add(name + ": " + (x == null ? "no item" : x) + " -> " + (y == null ? "no item" : y));
            }
        }
        put("block to item relation", changed);
    }

    private void defaults(VanillaData before, VanillaData after) {
        List<String> changed = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockDefaults().keySet());
        names.addAll(after.blockDefaults().keySet());
        for (String name : names) {
            NbtMap x = before.blockDefaults().get(name);
            NbtMap y = after.blockDefaults().get(name);
            if (!Objects.equals(x, y)) {
                changed.add(name + ": " + (x == null ? "first state" : compact(BlockStates.states(x))) + " -> "
                    + (y == null ? "first state" : compact(BlockStates.states(y))));
            }
        }
        put("block defaults (non first state)", changed);
    }
    // ---------------------------------------------------------------------------------------------------------------
    // Network capture against bundled data (capture)

    private void captureItems(CapturedData capture, VanillaData reference) {
        Map<String, VanillaItem> bundled = new LinkedHashMap<>();
        reference.items().forEach(item -> bundled.put(item.identifier(), item));
        putIfAny("items only in the capture", difference(capture.items().keySet(), bundled.keySet()));
        putIfAny("items only in the bundled data", difference(bundled.keySet(), capture.items().keySet()));
        List<String> ids = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> components = new ArrayList<>();
        capture.items().forEach((name, wire) -> {
            VanillaItem item = bundled.get(name);
            if (item == null) {
                return;
            }
            if (item.runtimeId() != wire.getShort("id")) {
                ids.add(name + ": " + item.runtimeId() + " -> " + wire.getShort("id"));
            }
            if (item.version() != wire.getInt("version") || item.componentBased() != wire.getBoolean("componentBased")) {
                flags.add(name + ": version " + item.version() + " -> " + wire.getInt("version")
                    + ", componentBased " + item.componentBased() + " -> " + wire.getBoolean("componentBased"));
            }
            NbtMap bundledData = item.componentData() == null ? NbtMap.EMPTY : item.componentData();
            List<String> diff = new ArrayList<>();
            nbt("", bundledData, wire.getCompound("data"), diff);
            if (!diff.isEmpty()) {
                components.add(name + ": " + String.join("; ", diff));
            }
        });
        putIfAny("item runtime id changes", ids);
        putIfAny("item version/componentBased changes", flags);
        putIfAny("item component data changes", components);
    }

    private void captureBlocks(CapturedData capture, VanillaData reference) {
        Map<String, NbtMap> bundled = new LinkedHashMap<>();
        for (BlockPropertyData block : reference.dataDrivenBlocks()) {
            bundled.put(block.getName(), block.getProperties());
        }
        putIfAny("block properties only in the capture", difference(capture.blockProperties().keySet(), bundled.keySet()));
        putIfAny("block properties only in the bundled data", difference(bundled.keySet(), capture.blockProperties().keySet()));
        List<String> changes = new ArrayList<>();
        capture.blockProperties().forEach((name, wire) -> {
            NbtMap properties = bundled.get(name);
            if (properties != null) {
                List<String> diff = new ArrayList<>();
                nbt("", properties, wire, diff);
                if (!diff.isEmpty()) {
                    changes.add(name + ": " + String.join("; ", diff));
                }
            }
        });
        putIfAny("block property changes", changes);
    }

    private void captureCreative(CapturedData capture, VanillaData reference) {
        for (CreativeCategory category : CreativeCategory.values()) {
            List<String> wire = flatten(capture.creative().nodes(category));
            List<String> bundled = flatten(reference.creative().nodes(category));
            if (wire.equals(bundled)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            lines.add("size " + bundled.size() + " -> " + wire.size());
            Set<String> added = difference(new LinkedHashSet<>(wire), new LinkedHashSet<>(bundled));
            Set<String> removed = difference(new LinkedHashSet<>(bundled), new LinkedHashSet<>(wire));
            added.forEach(entry -> lines.add("+ " + entry));
            removed.forEach(entry -> lines.add("- " + entry));
            if (added.isEmpty() && removed.isEmpty()) {
                for (int i = 0; i < Math.min(wire.size(), bundled.size()); i++) {
                    if (!wire.get(i).equals(bundled.get(i))) {
                        lines.add("order differs from #" + i + ": " + bundled.get(i) + " -> " + wire.get(i));
                        break;
                    }
                }
            }
            putIfAny("creative " + category, lines);
        }
    }
    // ---------------------------------------------------------------------------------------------------------------
    // Script API dump against bundled data (bdsDump)

    private void bdsBlocks(BdsDump dump, VanillaData data) {
        // State values per block as the palette has them
        Map<String, Map<String, Set<Object>>> palette = new TreeMap<>();
        Map<String, Integer> stateCounts = new TreeMap<>();
        for (NbtMap state : data.blockStates()) {
            stateCounts.merge(BlockStates.name(state), 1, Integer::sum);
            Map<String, Set<Object>> values = palette.computeIfAbsent(BlockStates.name(state), n -> new TreeMap<>());
            BlockStates.states(state).forEach((key, value) -> values.computeIfAbsent(key, k -> new TreeSet<>(Diff::compareValues)).add(value));
        }

        Set<String> onlyBds = new TreeSet<>(dump.blockDefaults().keySet());
        onlyBds.removeAll(palette.keySet());
        Set<String> onlyPalette = new TreeSet<>(palette.keySet());
        onlyPalette.removeAll(dump.blockDefaults().keySet());
        putDistinct("blocks the Script API knows, but not the block palette", onlyBds);
        putDistinct("blocks in the block palette the Script API does not know", onlyPalette);

        // The Script API adds legacy alias states (wood_type, color, direction, ...); they are not network states
        Map<String, Integer> aliases = new TreeMap<>();
        List<String> stateProblems = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Object>>> entry : dump.blockStates().entrySet()) {
            Map<String, Set<Object>> expected = palette.get(entry.getKey());
            if (expected == null) {
                continue;
            }
            Set<String> missing = new TreeSet<>(expected.keySet());
            missing.removeAll(entry.getValue().keySet());
            if (!missing.isEmpty()) {
                stateProblems.add(entry.getKey() + ": palette states " + missing + " are unknown to the Script API");
            }
            entry.getValue().forEach((key, values) -> {
                Set<Object> palettesValues = expected.get(key);
                if (palettesValues == null) {
                    aliases.merge(key, 1, Integer::sum);
                    return;
                }
                Set<Object> set = new TreeSet<>(Diff::compareValues);
                set.addAll(values);
                if (!set.equals(palettesValues)) {
                    stateProblems.add(entry.getKey() + " " + key + ": " + set + " in BDS, " + palettesValues + " in the palette");
                }
            });
        }
        // Is the palette the full cartesian product of the values the Script API reports? Then it could be generated
        List<String> notProducts = new ArrayList<>();
        dump.blockStates().forEach((name, values) -> {
            Map<String, Set<Object>> expected = palette.get(name);
            if (expected == null) {
                return;
            }
            long product = 1;
            for (Map.Entry<String, List<Object>> state : values.entrySet()) {
                if (expected.containsKey(state.getKey())) {
                    product *= state.getValue().size();
                }
            }
            if (product != stateCounts.get(name)) {
                notProducts.add(name + ": " + stateCounts.get(name) + " palette states, " + product + " combinations of the Script API values");
            }
        });
        putDistinct("blocks whose palette states are not all combinations of the Script API values", notProducts);
        putDistinct("legacy alias states the Script API adds (state: blocks)", aliases.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
        putDistinct("block state differences (palette states and their values)", stateProblems);

        List<String> defaultProblems = new ArrayList<>();
        Map<String, NbtMap> defaults = dump.projectDefaults(data.blockStates(), defaultProblems);
        Map<String, NbtMap> first = new TreeMap<>();
        data.blockStates().forEach(state -> first.putIfAbsent(BlockStates.name(state), state));
        List<String> nonFirst = new ArrayList<>();
        defaults.forEach((name, state) -> {
            if (!state.equals(first.get(name))) {
                nonFirst.add(name + ": " + BlockStates.states(state).toString().replaceAll("\\s+", " "));
            }
        });
        putDistinct("block defaults that are not palette states (after dropping alias states)", defaultProblems);
        putDistinct("blocks whose default is not their first palette state", nonFirst);
        System.out.println("  " + dump.blockTags().size() + " block tags; " + dump.blockItems().size() + " blocks have an item");
    }

    private void bdsItems(BdsDump dump, VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        if (dump.items().isEmpty()) {
            return;
        }
        Set<String> onlyBds = new TreeSet<>(dump.items().keySet());
        onlyBds.removeAll(items.keySet());
        Set<String> onlyNetwork = new TreeSet<>(items.keySet());
        onlyNetwork.removeAll(dump.items().keySet());
        putDistinct("items the Script API knows, but not the item list", onlyBds);
        putDistinct("items in the item list the Script API does not know (or could not make a stack of)", onlyNetwork);

        List<String> stackProblems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, BdsDump.Item> entry : dump.items().entrySet()) {
            VanillaItem item = items.get(entry.getKey());
            if (item == null || item.componentData() == null) {
                continue;
            }
            NbtMap properties = item.componentData().getCompound("components", NbtMap.EMPTY).getCompound("item_properties", null);
            if (properties == null || !properties.containsKey("max_stack_size")) {
                continue;
            }
            checked++;
            int network = properties.getInt("max_stack_size");
            if (network != entry.getValue().maxStackSize()) {
                stackProblems.add(entry.getKey() + ": " + entry.getValue().maxStackSize() + " in BDS, " + network + " in the item components");
            }
        }
        putDistinct("max stack size differences (" + checked + " component based items checked)", stackProblems);
    }

    private void bdsTags(String name, Map<String, List<String>> bds, Map<String, List<String>> other) {
        Set<String> onlyBds = new TreeSet<>(bds.keySet());
        onlyBds.removeAll(other.keySet());
        Set<String> onlyOther = new TreeSet<>(other.keySet());
        onlyOther.removeAll(bds.keySet());
        List<String> differences = new ArrayList<>();
        for (String tag : new TreeSet<>(bds.keySet())) {
            if (!other.containsKey(tag)) {
                continue;
            }
            Set<String> added = new TreeSet<>(bds.get(tag));
            added.removeAll(other.get(tag));
            Set<String> removed = new TreeSet<>(other.get(tag));
            removed.removeAll(bds.get(tag));
            if (!added.isEmpty() || !removed.isEmpty()) {
                differences.add(tag + ": +" + added + " -" + removed);
            }
        }
        System.out.println("Item tags, BDS vs " + name + ": " + bds.size() + " vs " + other.size() + " tags");
        putDistinct("item tags only in BDS", onlyBds);
        putDistinct("item tags only in " + name, onlyOther);
        putDistinct("item tag membership (+ only in BDS, - only in " + name + ")", differences);
    }

    private static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Integer.compare(x.intValue(), y.intValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
    // ---------------------------------------------------------------------------------------------------------------
    // Script API dump against Script API dump (scriptApi)

    public static Diff scriptApi(BdsDump before, BdsDump after) {
        Diff diff = new Diff();
        Set<String> blocksBefore = before.blockStates().isEmpty() ? before.blockDefaults().keySet() : before.blockStates().keySet();
        Set<String> blocksAfter = after.blockStates().isEmpty() ? after.blockDefaults().keySet() : after.blockStates().keySet();
        diff.put("new blocks", difference(new TreeSet<>(blocksAfter), new TreeSet<>(blocksBefore)).stream()
            .map(name -> name + " " + compact(after.blockStates().getOrDefault(name, Map.of()))).toList());
        diff.put("removed blocks", List.copyOf(difference(new TreeSet<>(blocksBefore), new TreeSet<>(blocksAfter))));

        List<String> states = new ArrayList<>();
        before.blockStates().forEach((name, x) -> {
            Map<String, List<Object>> y = after.blockStates().get(name);
            if (y == null || x.equals(y)) {
                return;
            }
            Set<String> keys = new TreeSet<>(x.keySet());
            keys.addAll(y.keySet());
            List<String> parts = new ArrayList<>();
            for (String key : keys) {
                if (!x.containsKey(key)) {
                    parts.add("+" + key + "=" + y.get(key));
                } else if (!y.containsKey(key)) {
                    parts.add("-" + key);
                } else if (!x.get(key).equals(y.get(key))) {
                    parts.add(key + ": " + x.get(key) + " -> " + y.get(key));
                }
            }
            states.add(name + ": " + String.join("; ", parts));
        });
        diff.put("block state changes", states);

        List<String> defaults = new ArrayList<>();
        before.blockDefaults().forEach((name, x) -> {
            var y = after.blockDefaults().get(name);
            if (y != null && !x.equals(y)) {
                defaults.add(name + ": " + compact(BlockStates.states(x)) + " -> " + compact(BlockStates.states(y)));
            }
        });
        diff.put("block default changes", defaults);

        diff.put("new items", difference(after.items().keySet(), before.items().keySet()).stream()
            .map(name -> name + " " + describe(after.items().get(name))).toList());
        diff.put("removed items", List.copyOf(difference(before.items().keySet(), after.items().keySet())));
        // Script API component changes usually hit hundreds of items at once: one line per component
        List<String> items = new ArrayList<>();
        Map<String, List<String>> componentChanges = new java.util.TreeMap<>();
        before.items().forEach((name, x) -> {
            BdsDump.Item y = after.items().get(name);
            if (y == null || x.equals(y)) {
                return;
            }
            if (x.maxStackSize() != y.maxStackSize() || x.maxDurability() != y.maxDurability()
                || !Objects.equals(x.localizationKey(), y.localizationKey())) {
                items.add(name + ": " + describe(x) + " -> " + describe(y));
            }
            difference(new TreeSet<>(y.components()), new TreeSet<>(x.components()))
                .forEach(c -> componentChanges.computeIfAbsent("+" + c, k -> new ArrayList<>()).add(name));
            difference(new TreeSet<>(x.components()), new TreeSet<>(y.components()))
                .forEach(c -> componentChanges.computeIfAbsent("-" + c, k -> new ArrayList<>()).add(name));
        });
        diff.put("item changes (stack size, durability, localization key)", items);
        diff.put("Script API item components (+ added to, - removed from)", componentChanges.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue().size() + " items" + (e.getValue().size() <= 10 ? " " + e.getValue()
                : ", e.g. " + e.getValue().subList(0, 5))).toList());

        scriptTags(diff, "item tags", before.itemTags(), after.itemTags());
        scriptTags(diff, "block tags", before.blockTags(), after.blockTags());

        List<String> blockItems = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockItems().keySet());
        names.addAll(after.blockItems().keySet());
        for (String name : names) {
            if (!Objects.equals(before.blockItems().get(name), after.blockItems().get(name))) {
                blockItems.add(name + ": " + before.blockItems().get(name) + " -> " + after.blockItems().get(name));
            }
        }
        diff.put("block to item changes", blockItems);
        diff.put("addon errors", after.errors());
        return diff;
    }

    private static String describe(BdsDump.Item item) {
        return "(stack " + item.maxStackSize() + (item.maxDurability() > 0 ? ", durability " + item.maxDurability() : "")
            + ", key " + item.localizationKey() + ", components " + item.components() + ")";
    }

    private static void scriptTags(Diff diff, String what, Map<String, List<String>> before, Map<String, List<String>> after) {
        diff.put("new " + what, difference(after.keySet(), before.keySet()).stream()
            .map(tag -> tag + ": " + after.get(tag)).toList());
        diff.put("removed " + what, List.copyOf(difference(before.keySet(), after.keySet())));
        List<String> changed = new ArrayList<>();
        for (String tag : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(tag)) {
                continue;
            }
            Set<String> added = difference(new TreeSet<>(after.get(tag)), new TreeSet<>(before.get(tag)));
            Set<String> removed = difference(new TreeSet<>(before.get(tag)), new TreeSet<>(after.get(tag)));
            if (!added.isEmpty() || !removed.isEmpty()) {
                changed.add(tag + ": +" + added + " -" + removed);
            }
        }
        diff.put(what + " membership", changed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Source directory against bundled data (source)

    // Blocks

    private void sourceBlocks(Importer.Source read, VanillaData bundled) {
        List<NbtMap> a = bundled.blockStates();
        List<NbtMap> b = read.blocks();
        Map<String, List<NbtMap>> x = statesByBlock(a);
        Map<String, List<NbtMap>> y = statesByBlock(b);

        Section states = section("Block states");
        states.bundled = a.size() + " states, " + x.size() + " blocks";
        states.source = b.size() + " states, " + y.size() + " blocks";
        duplicates(b).forEach(state -> states.differences.add("duplicate state in the source: " + describe(state)));
        difference(y.keySet(), x.keySet()).forEach(block -> states.differences.add("block only in the source: " + block
            + " (" + y.get(block).size() + " states)"));
        difference(x.keySet(), y.keySet()).forEach(block -> states.differences.add("block only in the bundled data: " + block
            + " (" + x.get(block).size() + " states)"));
        List<String> sameSetDifferentOrder = new ArrayList<>();
        for (String block : new TreeSet<>(x.keySet())) {
            List<NbtMap> before = x.get(block);
            List<NbtMap> after = y.get(block);
            if (after == null || before.equals(after)) {
                continue;
            }
            Set<NbtMap> added = new LinkedHashSet<>(after);
            before.forEach(added::remove);
            Set<NbtMap> removed = new LinkedHashSet<>(before);
            after.forEach(removed::remove);
            added.forEach(state -> states.differences.add("state only in the source: " + describe(state)));
            removed.forEach(state -> states.differences.add("state only in the bundled data: " + describe(state)));
            if (added.isEmpty() && removed.isEmpty() && before.size() == after.size()) {
                for (int i = 0; i < before.size(); i++) {
                    if (!before.get(i).equals(after.get(i))) {
                        sameSetDifferentOrder.add(block + ": state #" + i + " of " + before.size() + " is "
                            + compact(BlockStates.states(after.get(i))) + " in the source, "
                            + compact(BlockStates.states(before.get(i))) + " bundled");
                        break;
                    }
                }
            }
        }

        // Order of blocks: the palette is the blocks in FNV-1 64 name order (unsigned), each block's states together
        Section order = section("Block order (FNV-1 64 name sort)");
        List<String> blocksA = new ArrayList<>(x.keySet());
        List<String> blocksB = new ArrayList<>(y.keySet());
        order.bundled = sortedness(a);
        order.source = sortedness(b);
        split(a).forEach(line -> order.differences.add("bundled: " + line));
        split(b).forEach(line -> order.differences.add("source: " + line));
        List<String> common = new ArrayList<>(blocksA);
        common.retainAll(y.keySet());
        List<String> commonB = new ArrayList<>(blocksB);
        commonB.retainAll(x.keySet());
        if (!common.equals(commonB)) {
            for (int i = 0; i < common.size(); i++) {
                if (!common.get(i).equals(commonB.get(i))) {
                    order.differences.add("blocks in a different order from block #" + i + ": " + commonB.get(i) + " in the source, "
                        + common.get(i) + " bundled");
                    break;
                }
            }
        }
        if (!isSorted(b)) {
            order.info.add("the source palette is not sorted by the FNV-1 64 hash of the block name: " + firstUnsorted(b));
        }

        Section within = section("State order within blocks");
        within.bundled = x.size() + " blocks";
        within.source = y.size() + " blocks";
        within.differences.addAll(sameSetDifferentOrder);

        // Whatever the sections above explain, the runtime ids (palette positions) must be equal
        if (!a.equals(b) && states.differences.isEmpty() && order.differences.isEmpty() && within.differences.isEmpty()) {
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                if (!a.get(i).equals(b.get(i))) {
                    order.differences.add("palette position #" + i + ": " + describe(b.get(i)) + " in the source, " + describe(a.get(i)) + " bundled");
                    break;
                }
            }
        }

        // The version tag is not part of a state's identity and not bundled
        Map<String, Integer> versions = new TreeMap<>();
        read.rawBlocks().forEach(state -> versions.merge(String.valueOf(state.getOrDefault("version", "none")), 1, Integer::sum));
        states.info.add("`version` tags in the source (not bundled, not compared): " + versions);
    }

    private void sourceHashes(Importer.Source read, VanillaData bundled) {
        Section section = section("Hashed block network ids");
        Map<Integer, NbtMap> bundledHashes = new HashMap<>();
        List<String> bundledCollisions = new ArrayList<>();
        for (NbtMap state : bundled.blockStates()) {
            NbtMap previous = bundledHashes.put(BlockNetworkIds.hash(state), state);
            if (previous != null && !previous.equals(state)) {
                bundledCollisions.add(describe(previous) + " and " + describe(state));
            }
        }
        bundledCollisions.forEach(line -> section.differences.add("hash collision in the bundled data: " + line));
        section.bundled = bundledHashes.size() + " distinct hashes";

        Map<Integer, NbtMap> sourceHashes = new HashMap<>();
        int carried = 0;
        int nameHashes = 0;
        for (NbtMap raw : read.rawBlocks()) {
            NbtMap state = BlockStates.normalize(raw);
            int hash = BlockNetworkIds.hash(state);
            NbtMap previous = sourceHashes.put(hash, state);
            if (previous != null && !previous.equals(state)) {
                section.differences.add("hash collision in the source: " + describe(previous) + " and " + describe(state));
            }
            if (raw.get("network_id") instanceof Number networkId) {
                carried++;
                if (networkId.intValue() != hash) {
                    section.differences.add(describe(state) + ": network_id " + networkId.intValue() + " in the source, computed " + hash);
                }
            }
            if (raw.get("name_hash") instanceof Number nameHash) {
                nameHashes++;
                long expected = BlockPalette.nameHash(BlockStates.name(state));
                if (nameHash.longValue() != expected) {
                    section.differences.add(describe(state) + ": name_hash " + Long.toUnsignedString(nameHash.longValue())
                        + " in the source, computed FNV-1 64 " + Long.toUnsignedString(expected));
                }
            }
        }
        section.source = sourceHashes.size() + " distinct hashes; " + carried + " states carry `network_id`, " + nameHashes + " `name_hash`";
        Set<Integer> onlySource = new TreeSet<>(sourceHashes.keySet());
        onlySource.removeAll(bundledHashes.keySet());
        Set<Integer> onlyBundled = new TreeSet<>(bundledHashes.keySet());
        onlyBundled.removeAll(sourceHashes.keySet());
        if (!onlySource.isEmpty() || !onlyBundled.isEmpty()) {
            // Follows from the state differences; listed so hashed id consumers see the size of the change
            section.info.add(onlySource.size() + " hashed ids only in the source, " + onlyBundled.size() + " only in the bundled data "
                + "(see Block states)");
        }
        if (carried == 0) {
            section.info.add("the source carries no `network_id`: hashes computed only");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Items

    private void sourceItems(Importer.Source read, VanillaData bundled) {
        Section section = section("Items");
        Map<String, VanillaItem> a = itemMap(bundled.items());
        Map<String, VanillaItem> b = itemMap(read.items());
        section.bundled = bundled.items().size() + " items";
        section.source = read.items().size() + " items";
        duplicates(read.items().stream().map(VanillaItem::identifier).toList())
            .forEach(id -> section.differences.add("duplicate item in the source: " + id));
        Map<Integer, String> ids = new HashMap<>();
        for (VanillaItem item : read.items()) {
            String previous = ids.put(item.runtimeId(), item.identifier());
            if (previous != null && !previous.equals(item.identifier())) {
                section.differences.add("runtime id " + item.runtimeId() + " used twice in the source: " + previous + ", " + item.identifier());
            }
        }
        difference(b.keySet(), a.keySet()).forEach(id -> section.differences.add("only in the source: " + id
            + " (id " + b.get(id).runtimeId() + ")"));
        difference(a.keySet(), b.keySet()).forEach(id -> section.differences.add("only in the bundled data: " + id
            + " (id " + a.get(id).runtimeId() + ")"));
        a.forEach((id, x) -> {
            VanillaItem y = b.get(id);
            if (y == null) {
                return;
            }
            if (x.runtimeId() != y.runtimeId()) {
                section.differences.add(id + ": runtime id " + y.runtimeId() + " in the source, " + x.runtimeId() + " bundled");
            }
            if (x.version() != y.version()) {
                section.differences.add(id + ": version " + y.version() + " in the source, " + x.version() + " bundled");
            }
            if (x.componentBased() != y.componentBased()) {
                section.differences.add(id + ": componentBased " + y.componentBased() + " in the source, " + x.componentBased() + " bundled");
            }
        });
        // The importer sorts items by identifier; the bundled list must be in that order too
        List<String> order = bundled.items().stream().map(VanillaItem::identifier).toList();
        List<String> sorted = new ArrayList<>(order);
        sorted.sort(null);
        if (!order.equals(sorted)) {
            section.differences.add("the bundled items are not sorted by identifier, as the importer writes them");
        }
    }

    private void sourceComponents(Importer.Source read, VanillaData bundled) {
        Section section = section("Item components");
        Map<String, VanillaItem> a = itemMap(bundled.items());
        Map<String, VanillaItem> b = itemMap(read.items());
        section.bundled = a.values().stream().filter(item -> item.componentData() != null && !item.componentData().isEmpty()).count()
            + " items with component data";
        section.source = read.components().size() + " entries in item_components";
        a.forEach((id, x) -> {
            VanillaItem y = b.get(id);
            if (y == null) {
                return;
            }
            List<String> lines = new ArrayList<>();
            nbt("", data(x), data(y), lines);
            lines.forEach(line -> section.differences.add(id + ": " + line + " (bundled -> source)"));
        });
        difference(read.components().keySet(), b.keySet()).forEach(id ->
            section.info.add("item_components has an entry for " + id + ", which is not an item of runtime_item_states (not bundled)"));
    }

    private static NbtMap data(VanillaItem item) {
        return item.componentData() == null ? NbtMap.EMPTY : item.componentData();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Data driven blocks

    private void sourceDataDriven(Importer.Source read, VanillaData bundled) {
        Section section = section("Data driven blocks");
        Map<String, NbtMap> a = new LinkedHashMap<>();
        bundled.dataDrivenBlocks().forEach(block -> a.put(block.getName(), block.getProperties()));
        section.bundled = a.size() + " blocks";
        if (read.dataDriven() == null) {
            section.source = "no data_driven_blocks file";
            section.info.add("skipped: the source has no data_driven_blocks.nbt");
            return;
        }
        Map<String, NbtMap> b = new LinkedHashMap<>();
        for (BlockPropertyData block : read.dataDriven()) {
            b.put(block.getName(), block.getProperties());
        }
        section.source = b.size() + " blocks";
        difference(b.keySet(), a.keySet()).forEach(block -> section.differences.add("only in the source: " + block));
        difference(a.keySet(), b.keySet()).forEach(block -> section.differences.add("only in the bundled data: " + block));
        a.forEach((block, x) -> {
            NbtMap y = b.get(block);
            if (y != null) {
                List<String> lines = new ArrayList<>();
                nbt("", x, y, lines);
                lines.forEach(line -> section.differences.add(block + ": " + line + " (bundled -> source)"));
            }
        });
        List<String> orderA = new ArrayList<>(a.keySet());
        orderA.retainAll(b.keySet());
        List<String> orderB = new ArrayList<>(b.keySet());
        orderB.retainAll(a.keySet());
        if (!orderA.equals(orderB)) {
            section.differences.add("different order: " + orderB + " in the source, " + orderA + " bundled");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Creative

    /** A creative entry, equal by value only: the text shows state keys in file order, which carries no meaning. */
    private record Entry(String text, Object value) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Entry entry && value.equals(entry.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    private void sourceCreative(Importer.Source read, VanillaData bundled) {
        Section section = section("Creative layout");
        section.bundled = bundled.creative().stacks().count() + " stacks";
        section.source = read.creative().stacks().count() + " stacks";
        for (CreativeCategory category : CreativeCategory.values()) {
            List<Entry> x = entries(bundled.creative().nodes(category));
            List<Entry> y = entries(read.creative().nodes(category));
            if (x.equals(y)) {
                continue;
            }
            section.differences.add(category + ": " + x.size() + " entries bundled, " + y.size() + " in the source");
            // Longest common subsequence: what is in both but not in it moved, the rest was added or removed
            int[][] lcs = new int[x.size() + 1][y.size() + 1];
            for (int i = x.size() - 1; i >= 0; i--) {
                for (int j = y.size() - 1; j >= 0; j--) {
                    lcs[i][j] = x.get(i).equals(y.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            List<Integer> onlyX = new ArrayList<>();
            List<Integer> onlyY = new ArrayList<>();
            int i = 0;
            int j = 0;
            while (i < x.size() && j < y.size()) {
                if (x.get(i).equals(y.get(j))) {
                    i++;
                    j++;
                } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                    onlyX.add(i++);
                } else {
                    onlyY.add(j++);
                }
            }
            while (i < x.size()) {
                onlyX.add(i++);
            }
            while (j < y.size()) {
                onlyY.add(j++);
            }
            List<Integer> unmatchedY = new ArrayList<>(onlyY);
            for (int from : onlyX) {
                Entry entry = x.get(from);
                Integer to = unmatchedY.stream().filter(k -> y.get(k).equals(entry)).findFirst().orElse(null);
                if (to != null) {
                    unmatchedY.remove(to);
                    section.differences.add(category + " moved: " + entry.text() + " from #" + from + " (bundled) to #" + to + " (source)"
                        + neighbours(y, to));
                } else {
                    section.differences.add(category + " only bundled: #" + from + " " + entry.text());
                }
            }
            for (int to : unmatchedY) {
                section.differences.add(category + " only in the source: #" + to + " " + y.get(to).text() + neighbours(y, to));
            }
        }
    }

    private static String neighbours(List<Entry> entries, int index) {
        return "; after " + (index == 0 ? "nothing" : entries.get(index - 1).text());
    }

    private static List<Entry> entries(List<CreativeNode> nodes) {
        List<Entry> out = new ArrayList<>();
        List<String> texts = flatten(nodes);
        int k = 0;
        for (CreativeNode node : nodes) {
            if (node instanceof CreativeGroup group) {
                out.add(new Entry(texts.get(k++), List.of("group", group.name(), Objects.requireNonNullElse((Object) group.icon(), "-"))));
                for (CreativeStack stack : group.stacks()) {
                    out.add(new Entry(texts.get(k++), List.of(group.name(), stack)));
                }
            } else {
                out.add(new Entry(texts.get(k++), node));
            }
        }
        return out;
    }

    private static Map<String, VanillaItem> itemMap(List<VanillaItem> items) {
        Map<String, VanillaItem> map = new TreeMap<>();
        items.forEach(item -> map.putIfAbsent(item.identifier(), item));
        return map;
    }

    private static <T> List<T> duplicates(List<T> values) {
        Set<T> seen = new java.util.HashSet<>();
        Set<T> duplicates = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!seen.add(value)) {
                duplicates.add(value);
            }
        });
        return List.copyOf(duplicates);
    }

    /** Blocks whose states are not contiguous in the palette. */
    private static List<String> split(List<NbtMap> states) {
        Set<String> done = new java.util.HashSet<>();
        Set<String> split = new TreeSet<>();
        String current = null;
        for (NbtMap state : states) {
            String block = BlockStates.name(state);
            if (!block.equals(current)) {
                if (!done.add(block)) {
                    split.add(block);
                }
                current = block;
            }
        }
        return split.stream().map(block -> "the states of " + block + " are not contiguous").toList();
    }

    private static boolean isSorted(List<NbtMap> states) {
        return firstUnsorted(states) == null;
    }

    private static String firstUnsorted(List<NbtMap> states) {
        for (int i = 1; i < states.size(); i++) {
            String previous = BlockStates.name(states.get(i - 1));
            String current = BlockStates.name(states.get(i));
            if (Long.compareUnsigned(BlockPalette.nameHash(previous), BlockPalette.nameHash(current)) > 0) {
                return "#" + i + " " + current + " after " + previous;
            }
        }
        return null;
    }

    private static String sortedness(List<NbtMap> states) {
        return isSorted(states) ? "sorted" : "NOT sorted";
    }

    private static String describe(NbtMap state) {
        return BlockStates.name(state) + " " + compact(BlockStates.states(state));
}
    // ---------------------------------------------------------------------------------------------------------------
    // Shared helpers

    private static Map<String, List<NbtMap>> statesByBlock(List<NbtMap> states) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        states.forEach(state -> blocks.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        return blocks;
    }

    private static Set<String> keys(List<NbtMap> states) {
        Set<String> keys = new TreeSet<>();
        states.forEach(state -> keys.addAll(BlockStates.states(state).keySet()));
        return keys;
    }

    private static Map<String, VanillaItem> itemMap(VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        return items;
    }

    static List<String> flatten(List<CreativeNode> nodes) {
        List<String> out = new ArrayList<>();
        for (CreativeNode node : nodes) {
            if (node instanceof CreativeGroup group) {
                out.add("[group " + group.name() + " icon " + (group.icon() == null ? "-" : describe(group.icon())) + "]");
                group.stacks().forEach(stack -> out.add(group.name() + " / " + describe(stack)));
            } else {
                out.add(describe((CreativeStack) node));
            }
        }
        return out;
    }

    private static String describe(CreativeStack stack) {
        return stack.identifier() + (stack.damage() != 0 ? "@" + stack.damage() : "") + (stack.count() != 1 ? "x" + stack.count() : "")
            + (stack.blockState() != null ? " " + compact(stack.blockState().getCompound("states")) : "")
            + (stack.tag() != null ? " " + compact(stack.tag()) : "");
    }

    /** Path level NBT diff; also reports type changes of equal looking values. */
    static void nbt(String path, Object a, Object b, List<String> out) {
        if (a instanceof NbtMap mapA && b instanceof NbtMap mapB) {
            Set<String> keys = new LinkedHashSet<>(mapA.keySet());
            keys.addAll(mapB.keySet());
            for (String key : keys) {
                String child = path.isEmpty() ? key : path + "." + key;
                if (!mapA.containsKey(key)) {
                    out.add("+" + child + "=" + shorten(mapB.get(key)));
                } else if (!mapB.containsKey(key)) {
                    out.add("-" + child);
                } else {
                    nbt(child, mapA.get(key), mapB.get(key), out);
                }
            }
        } else if (a instanceof NbtList<?> listA && b instanceof NbtList<?> listB && listA.size() == listB.size()) {
            for (int i = 0; i < listA.size(); i++) {
                nbt(path + "[" + i + "]", listA.get(i), listB.get(i), out);
            }
        } else if (!Objects.deepEquals(a, b) || (a != null && b != null && a.getClass() != b.getClass())) {
            out.add(path + ": " + shorten(a) + " -> " + shorten(b));
        }
    }

    static String compact(Object value) {
        return String.valueOf(value).replaceAll("\\s+", " ");
    }

    private static String shorten(Object value) {
        String text = compact(value);
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    public static <T extends Comparable<T>> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
