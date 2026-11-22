package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.client.RecipeCapture;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of the diff engine of the tooling: a list of named sections of difference lines. Empty sections mean no
 * difference; {@code info} lines never count as one. The comparisons that fill it live in one class per kind of data:
 * <ul>
 *   <li>{@link #vanilla} ({@link VanillaDiff}): two versions of vanilla data (bundled vs freshly captured, previous vs new):
 *       blocks and their states, items, item components, data driven blocks, creative layout, item tags, block defaults</li>
 *   <li>{@link #scriptApi} ({@link BdsDiff}): two Script API dumps of BDS</li>
 *   <li>{@link #capture} ({@link CaptureDiff}): a network capture against a bundled version (the {@code compare} command)</li>
 *   <li>{@link #bdsDump} ({@link BdsDiff}): a Script API dump against a bundled version (the {@code bds-compare} command)</li>
 *   <li>{@link #source} ({@link SourceDiff}): a directory in the CloudburstMC/Data layout against a bundled version, every
 *       aspect the bundled format keeps compared exactly (the {@code verify} command)</li>
 *   <li>{@link #recipes} ({@link RecipeDiff}): two recipe captures, normalised ({@link RecipeData#normalize}), by recipe id</li>
 *   <li>{@link #shapes}, {@link #values} ({@link WireShapeDiff}): the wire shapes and values of components of two captures
 *       ({@link WireShapes})</li>
 * </ul>
 * Output: {@link #print} (console) and {@link #markdown} (report).
 */
public final class Diff {
    public static final int DEFAULT_MAX_LINES = 25;
    public static final String WIRE_CHANGES = "wire format changes";
    public static final String NEW_COMPONENTS = "new components";
    public static final String VALUE_CHANGES = "component value changes";
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
    String sourceLabel = "";
    String bundledLabel = "";
    String name = "";

    Diff() {
    }

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
        return VanillaDiff.of(before, after, network, bds);
    }

    /**
     * Two recipe captures, whatever their list layout: per group (recipes, mixes, trims) the entries added, removed and
     * changed (with the changed fields), unlock changes, and a changed {@code cleanRecipes} flag. Lines start with the kind.
     */
    public static Diff recipes(RecipeCapture.Recipes previous, RecipeCapture.Recipes captured) {
        return RecipeDiff.of(previous, captured);
    }

    /** A network capture against a bundled version: items, item components, block properties, creative layout. */
    public static Diff capture(CapturedData capture, VanillaData reference) {
        return CaptureDiff.of(capture, reference);
    }

    /** A Script API dump against a bundled version: block states and defaults, items and stack sizes, item tags. */
    public static Diff bdsDump(BdsDump dump, VanillaData data, Map<String, Map<String, List<String>>> tagSources) {
        return BdsDiff.dump(dump, data, tagSources);
    }

    /** Two Script API dumps. */
    public static Diff scriptApi(BdsDump before, BdsDump after) {
        return BdsDiff.scriptApi(before, after);
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
        return SourceDiff.of(source, name, bundled, bundledLabel);
    }

    /**
     * An export of a compiled palette ({@link Export}) against a source directory (a CloudburstMC/Data checkout), with the
     * rules of {@link #source}: the export plays the bundled side. On top, the export's own {@code network_id}s: each must be
     * the hash of its state, and equal to the source's where the source carries one for the same state at the same position.
     *
     * @param source the CloudburstMC/Data checkout
     * @param export a directory written by {@link Export#write(dev.opencollab.paletter.Palette, Path)}
     */
    public static Diff against(Path source, String name, Path export) throws IOException {
        return SourceDiff.against(source, name, export);
    }

    /**
     * Component wire shapes of two sets of captures, per component (see {@link WireShapes}): an added, removed or retyped
     * field, or a component that is no longer sent, is a wire format change; a component not sent before is new.
     *
     * @param before component to (path to type)
     */
    public static Diff shapes(Map<String, Map<String, String>> before, Map<String, Map<String, String>> after) {
        return WireShapeDiff.shapes(before, after);
    }

    /**
     * The values of components and paths both sides send ({@link WireShapes#values}): a value that came or went with the same
     * shape is a value change, which {@link #shapes} cannot see (an enum id, a unit, a default).
     *
     * @param before component to (path to the distinct values)
     */
    public static Diff values(Map<String, Map<String, Set<String>>> before, Map<String, Map<String, Set<String>>> after) {
        return WireShapeDiff.values(before, after);
    }
}
