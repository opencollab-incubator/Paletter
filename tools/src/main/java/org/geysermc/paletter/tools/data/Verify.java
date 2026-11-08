package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.tools.update.Report;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * {@code verify}: diffs a directory in the CloudburstMC/Data layout (or Geyser's versioned file names) against a
 * bundled Paletter version, in memory, without writing anything. Every aspect the bundled format keeps is compared
 * exactly: block states and their order (blocks in FNV-1 64 name order, states within each block), hashed network ids,
 * items, item component NBT (including tag types), data driven blocks and the creative layout.
 * <p>
 * Not compared, because the bundled format does not keep them: the order of {@code runtime_item_states} (items are
 * keyed by runtime id; the importer sorts them by identifier), the per state {@code version} tag (reported as
 * information), item tags, block defaults and fallbacks (not part of the layout), and how the creative inventory is
 * split into wire groups across categories ({@code groupId}, {@code block_runtime_id}).
 */
public final class Verify {
    public static final int DEFAULT_MAX_LINES = 25;

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
    private final String sourceLabel;
    private final String bundledLabel;
    private final String name;

    private Verify(String sourceLabel, String bundledLabel, String name) {
        this.sourceLabel = sourceLabel;
        this.bundledLabel = bundledLabel;
        this.name = name;
    }

    /**
     * @param source the source directory
     * @param name the version name, e.g. {@code 26.60} (also picks Geyser's {@code *.26_60.*} files)
     * @param bundled the bundled data to compare with
     * @param bundledLabel what {@code bundled} is, for the report
     */
    public static Verify of(Path source, String name, VanillaData bundled, String bundledLabel) throws IOException {
        Verify verify = new Verify(source.toString(), bundledLabel, name);
        Importer.Source read = Importer.readSource(source, name);
        verify.blocks(read, bundled);
        verify.hashes(read, bundled);
        verify.items(read, bundled);
        verify.components(read, bundled);
        verify.dataDriven(read, bundled);
        verify.creative(read, bundled);
        return verify;
    }

    public boolean identical() {
        return sections.values().stream().allMatch(section -> section.differences.isEmpty());
    }

    int count() {
        return sections.values().stream().mapToInt(section -> section.differences.size()).sum();
    }

    Section section(String title) {
        return sections.computeIfAbsent(title, Section::new);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Blocks

    private void blocks(Importer.Source read, VanillaData bundled) {
        List<NbtMap> a = bundled.blockStates();
        List<NbtMap> b = read.blocks();
        Map<String, List<NbtMap>> x = statesByBlock(a);
        Map<String, List<NbtMap>> y = statesByBlock(b);

        Section states = section("Block states");
        states.bundled = a.size() + " states, " + x.size() + " blocks";
        states.source = b.size() + " states, " + y.size() + " blocks";
        duplicates(b).forEach(state -> states.differences.add("duplicate state in the source: " + describe(state)));
        DataDiff.difference(y.keySet(), x.keySet()).forEach(block -> states.differences.add("block only in the source: " + block
            + " (" + y.get(block).size() + " states)"));
        DataDiff.difference(x.keySet(), y.keySet()).forEach(block -> states.differences.add("block only in the bundled data: " + block
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
                            + DataDiff.compact(BlockStates.states(after.get(i))) + " in the source, "
                            + DataDiff.compact(BlockStates.states(before.get(i))) + " bundled");
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

    private void hashes(Importer.Source read, VanillaData bundled) {
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

    private void items(Importer.Source read, VanillaData bundled) {
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
        DataDiff.difference(b.keySet(), a.keySet()).forEach(id -> section.differences.add("only in the source: " + id
            + " (id " + b.get(id).runtimeId() + ")"));
        DataDiff.difference(a.keySet(), b.keySet()).forEach(id -> section.differences.add("only in the bundled data: " + id
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

    private void components(Importer.Source read, VanillaData bundled) {
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
            DataDiff.nbt("", data(x), data(y), lines);
            lines.forEach(line -> section.differences.add(id + ": " + line + " (bundled -> source)"));
        });
        DataDiff.difference(read.components().keySet(), b.keySet()).forEach(id ->
            section.info.add("item_components has an entry for " + id + ", which is not an item of runtime_item_states (not bundled)"));
    }

    private static NbtMap data(VanillaItem item) {
        return item.componentData() == null ? NbtMap.EMPTY : item.componentData();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Data driven blocks

    private void dataDriven(Importer.Source read, VanillaData bundled) {
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
        DataDiff.difference(b.keySet(), a.keySet()).forEach(block -> section.differences.add("only in the source: " + block));
        DataDiff.difference(a.keySet(), b.keySet()).forEach(block -> section.differences.add("only in the bundled data: " + block));
        a.forEach((block, x) -> {
            NbtMap y = b.get(block);
            if (y != null) {
                List<String> lines = new ArrayList<>();
                DataDiff.nbt("", x, y, lines);
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

    private void creative(Importer.Source read, VanillaData bundled) {
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
        List<String> texts = DataDiff.flatten(nodes);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Report

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
    // Helpers

    private static Map<String, List<NbtMap>> statesByBlock(List<NbtMap> states) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        states.forEach(state -> blocks.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        return blocks;
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
        return BlockStates.name(state) + " " + DataDiff.compact(BlockStates.states(state));
    }
}
