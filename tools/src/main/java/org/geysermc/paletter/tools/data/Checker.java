package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports what the bundled versions do not cover for each other: every block and item of the newest version that an
 * older version lacks. Paletter bundles no fallbacks, so these fall back to {@code minecraft:unknown} unless a consumer
 * defines a fallback ({@code PaletteLayer.Builder#blockFallback}, {@code #itemFallback}). Nothing here is an error; the
 * list is what a consumer has to decide about.
 */
public final class Checker {
    private final Path output;
    /**
     * Per older version, the content of the newest version it lacks. Filled by {@link #problems}.
     */
    final Map<String, List<String>> defaulted = new LinkedHashMap<>();
    /**
     * Per older version, content it only has behind an experiment toggle that the newest version has without one. The
     * older palette would send it to clients that never enabled the toggle, unless a consumer defines a fallback with
     * {@code force}. Filled by {@link #problems}.
     */
    final Map<String, List<String>> gated = new LinkedHashMap<>();

    public Checker(Path output) {
        this.output = output;
    }

    public boolean run() throws IOException {
        Map<String, List<String>> problems = problems(false);
        problems.values().forEach(list -> list.forEach(problem -> System.out.println("  " + problem)));
        defaulted.forEach((version, list) -> System.out.println("  " + version + ": " + list.size()
            + " falls back to unknown unless a consumer defines a fallback: " + list));
        gated.forEach((version, list) -> System.out.println("  " + version + ": " + list.size()
            + " only exist behind an experiment toggle there; they are sent to every client unless a consumer defines a force fallback: " + list));
        boolean ok = problems.values().stream().allMatch(List::isEmpty);
        System.out.println(ok ? "All versions are consistent." : "Fix the problems above.");
        return ok;
    }

    /**
     * @param includePreviews also treat preview versions of {@code versions.txt} as bundled (the newest one then
     *                        defines the content older versions are compared with); by default they are ignored
     * @return per older version, the problems of its data (none today: missing content is reported in
     * {@link #defaulted}, not as a problem)
     */
    public Map<String, List<String>> problems(boolean includePreviews) throws IOException {
        List<VanillaData> versions = new ArrayList<>();
        for (Importer.IndexEntry entry : Importer.readIndex(output)) {
            if (entry.preview() && !includePreviews) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(output.resolve(entry.name() + ".nbt"))) {
                versions.add(PaletteDataCodec.read(stream));
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        defaulted.clear();
        gated.clear();
        VanillaData latest = versions.getLast();
        for (VanillaData data : versions.subList(0, versions.size() - 1)) {
            DataVersion version = data.version();
            List<String> missing = missing(data, latest);
            if (!missing.isEmpty()) {
                defaulted.put(version.name(), missing);
            }
            List<String> experimental = gatedContent(data, latest);
            if (!experimental.isEmpty()) {
                gated.put(version.name(), experimental);
            }
            System.out.println(version + ": " + missing.size() + " blocks and items of " + latest.version()
                + " are missing and fall back to unknown unless a consumer defines a fallback; " + experimental.size()
                + " only exist behind an experiment there");
            result.put(version.name(), List.of());
        }
        return result;
    }

    /**
     * @return the blocks and items of {@code latest} that {@code data} lacks, as {@code block <id>} / {@code item <id>}
     */
    public static List<String> missing(VanillaData data, VanillaData latest) {
        List<String> missing = new ArrayList<>();
        Set<String> blocks = blockNames(data);
        for (String block : new TreeSet<>(blockNames(latest))) {
            if (!blocks.contains(block)) {
                missing.add("block " + block);
            }
        }
        Set<String> items = itemNames(data);
        for (String item : new TreeSet<>(itemNames(latest))) {
            if (!items.contains(item)) {
                missing.add("item " + item);
            }
        }
        return missing;
    }

    /**
     * With experiment marks (versions captured with experiments): content an older version only has behind a toggle,
     * but the newest version has without one.
     *
     * @return as {@code block <id> (<toggles>)} / {@code item <id> (<toggles>)}
     */
    public static List<String> gatedContent(VanillaData data, VanillaData latest) {
        List<String> gated = new ArrayList<>();
        if (data.experiments().isEmpty()) {
            return gated;
        }
        Set<String> blocks = blockNames(data);
        for (String block : blockNames(latest)) {
            List<String> toggles = data.experimentsOfBlock(block);
            if (blocks.contains(block) && !toggles.isEmpty() && latest.experimentsOfBlock(block).isEmpty()) {
                gated.add("block " + block + " (" + String.join(", ", toggles) + ")");
            }
        }
        Set<String> items = itemNames(data);
        for (String item : itemNames(latest)) {
            List<String> toggles = data.experimentsOfItem(item);
            if (items.contains(item) && !toggles.isEmpty() && latest.experimentsOfItem(item).isEmpty()) {
                gated.add("item " + item + " (" + String.join(", ", toggles) + ")");
            }
        }
        return gated;
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
