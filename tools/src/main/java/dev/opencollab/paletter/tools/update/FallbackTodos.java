package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.internal.data.VanillaData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** New content that falls back to {@code minecraft:unknown} in older bundled versions. */
final class FallbackTodos {
    /**
     * New blocks and items an older bundled version lacks fall back to {@code minecraft:unknown} there (Paletter's
     * default; it bundles no fallbacks). Lists them per version as INFO: what a consumer may want to define a fallback for.
     */
    static void list(UpdateContext ctx, List<String> blocks, List<String> items, String source) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Importer.IndexEntry entry : Importer.readIndex(ctx.resources)) {
            if (entry.preview()) {
                continue;
            }
            VanillaData data = BundledFiles.read(ctx.resources.resolve(entry.name() + ".nbt"));
            Set<String> knownBlocks = new TreeSet<>();
            data.blockStates().forEach(state -> knownBlocks.add(BlockStates.name(state)));
            Set<String> knownItems = new TreeSet<>();
            data.items().forEach(item -> knownItems.add(item.identifier()));
            List<String> needBlocks = identifiers(blocks).stream().filter(id -> !knownBlocks.contains(id)).toList();
            List<String> needItems = identifiers(items).stream().filter(id -> !knownItems.contains(id)).toList();
            if (!needBlocks.isEmpty() || !needItems.isEmpty()) {
                lines.add("- " + entry.name() + ": "
                    + (needBlocks.isEmpty() ? "" : needBlocks.size() + " blocks " + code(needBlocks))
                    + (needBlocks.isEmpty() || needItems.isEmpty() ? "" : "; ")
                    + (needItems.isEmpty() ? "" : needItems.size() + " items " + code(needItems)));
            }
        }
        if (!lines.isEmpty()) {
            ctx.report.check(Report.Status.INFO, "Fallbacks", "new content falls back to unknown in older versions unless a consumer defines a fallback ("
                + source + "), see the list");
            ctx.report.evidence("Content that falls back to unknown (" + source + ")", lines);
        }
    }

    private static List<String> identifiers(List<String> lines) {
        return lines == null ? List.of() : lines.stream().map(line -> line.split(" ")[0]).toList();
    }

    private static String code(List<String> identifiers) {
        return String.join(", ", identifiers.stream().map(id -> "`" + id + "`").toList());
    }
}
