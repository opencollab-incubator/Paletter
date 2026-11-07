package org.geysermc.paletter.generator;

import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.item.ItemFallback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifies that one mapping against the newest version works for every bundled version:
 * every block and item that exists in the newest version, but not in an older one, needs a fallback in the older
 * version, and every fallback must point at something that exists there.
 */
final class Checker {
    private final Path output;
    /**
     * Per older version, the content of the newest version it lacks without a curated fallback: it falls back to
     * {@code minecraft:unknown} (Paletter's default), which is fine, but a fallback in
     * {@code generator/fallbacks/<version>.json} would be better. Filled by {@link #problems}.
     */
    final Map<String, List<String>> defaulted = new java.util.LinkedHashMap<>();

    Checker(Path output) {
        this.output = output;
    }

    boolean run() throws IOException {
        Map<String, List<String>> problems = problems(false);
        problems.values().forEach(list -> list.forEach(problem -> System.out.println("  " + problem)));
        defaulted.forEach((version, list) -> System.out.println("  " + version + ": " + list.size()
            + " fall back to minecraft:unknown (no curated fallback): " + list));
        boolean ok = problems.values().stream().allMatch(List::isEmpty);
        System.out.println(ok ? "All versions are covered." : "Fix the problems above in generator/fallbacks/<version>.json and re-import.");
        return ok;
    }

    /**
     * @param includePreviews also treat preview versions of {@code versions.txt} as bundled (the newest one then
     *                        defines what needs fallbacks); by default they are ignored
     * @return per older version, what is missing a fallback or has a broken one (empty lists if covered)
     */
    Map<String, List<String>> problems(boolean includePreviews) throws IOException {
        List<VanillaData> versions = new ArrayList<>();
        for (Importer.IndexEntry entry : Importer.readIndex(output)) {
            if (entry.preview() && !includePreviews) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(output.resolve(entry.name() + ".nbt"))) {
                versions.add(PaletteDataCodec.read(stream));
            }
        }
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        defaulted.clear();
        VanillaData latest = versions.getLast();
        Set<String> latestBlocks = blockNames(latest);
        Set<String> latestItems = itemNames(latest);
        for (VanillaData data : versions.subList(0, versions.size() - 1)) {
            DataVersion version = data.version();
            Set<String> blocks = blockNames(data);
            Set<String> items = itemNames(data);
            List<String> problems = new ArrayList<>();

            Set<String> missingBlocks = new TreeSet<>(latestBlocks);
            missingBlocks.removeAll(blocks);
            missingBlocks.removeAll(data.fallbacks().unmapped());
            for (String block : missingBlocks) {
                BlockFallback fallback = data.fallbacks().blocks().get(block);
                if (fallback == null) {
                    defaulted.computeIfAbsent(version.name(), v -> new ArrayList<>()).add("block " + block);
                }
            }
            Set<String> missingItems = new TreeSet<>(latestItems);
            missingItems.removeAll(items);
            missingItems.removeAll(data.fallbacks().unmapped());
            for (String item : missingItems) {
                ItemFallback fallback = data.fallbacks().items().get(item);
                if (fallback == null) {
                    defaulted.computeIfAbsent(version.name(), v -> new ArrayList<>()).add("item " + item);
                }
            }
            for (Map.Entry<String, BlockFallback> entry : data.fallbacks().blocks().entrySet()) {
                if (!latestBlocks.contains(entry.getKey())) {
                    problems.add("unused block fallback " + entry.getKey() + ": no such block in " + latest.version());
                } else if (blocks.contains(entry.getKey()) && !entry.getValue().force()) {
                    problems.add("unused block fallback " + entry.getKey() + ": it exists in " + version + " (set force if it is experimental there)");
                } else if (!blocks.contains(entry.getValue().target())) {
                    problems.add("block " + entry.getKey() + " falls back to " + entry.getValue().target() + ", which does not exist in " + version);
                }
            }
            for (Map.Entry<String, ItemFallback> entry : data.fallbacks().items().entrySet()) {
                if (!latestItems.contains(entry.getKey())) {
                    problems.add("unused item fallback " + entry.getKey() + ": no such item in " + latest.version());
                } else if (items.contains(entry.getKey()) && !entry.getValue().force()) {
                    problems.add("unused item fallback " + entry.getKey() + ": it exists in " + version + " (set force if it is experimental there)");
                } else if (!items.contains(entry.getValue().target())) {
                    problems.add("item " + entry.getKey() + " falls back to " + entry.getValue().target() + ", which does not exist in " + version);
                }
            }

            System.out.println(version + ": " + missingBlocks.size() + " blocks and " + missingItems.size()
                + " items of " + latest.version() + " are missing, "
                + defaulted.getOrDefault(version.name(), List.of()).size() + " of them fall back to minecraft:unknown; "
                + problems.size() + " problems");
            result.put(version.name(), problems);
        }
        return result;
    }

    private static Set<String> blockNames(VanillaData data) {
        Set<String> names = new LinkedHashSet<>();
        data.blockStates().forEach(state -> names.add(BlockStates.name(state)));
        return names;
    }

    private static Set<String> itemNames(VanillaData data) {
        Set<String> names = new LinkedHashSet<>();
        for (VanillaItem item : data.items()) {
            names.add(item.identifier());
        }
        return names;
    }
}
