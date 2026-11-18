package org.geysermc.paletter.tools.model.infer;

import org.geysermc.paletter.tools.model.rule.Leaf;
import org.geysermc.paletter.tools.update.BuildHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What changed between two spec captures in the values BDS sends, not only in the keys and types (which
 * {@code Diff.shapes} sees): for every variant both captures were given with the same JSON and both accepted, the wire
 * leaves that differ. An axis that is mirrored, a unit that changes, a new enum id or another bit packing all show up
 * here as a variant, a path, and the old and new value.
 */
public final class SpecDiff {
    private SpecDiff() {
    }

    /**
     * @param component the component the variant tests ({@code block:minecraft:collision_box})
     * @param variant the variant's identifier
     * @param label what the variant varies
     * @param path the wire path, relative to the variant's component data
     * @param before the leaf in the earlier capture, null if it was not sent
     * @param after the leaf in the later capture, null if it is no longer sent
     */
    public record Change(String component, String variant, String label, String path, String before, String after) {
        public boolean valueChange() {
            return before != null && after != null;
        }

        @Override
        public String toString() {
            return variant + " (" + label + ") " + path + ": " + (before == null ? "not sent" : before) + " -> " + (after == null ? "not sent" : after);
        }
    }

    /**
     * How the variants of two captures pair up. {@link #compare} and {@link #acceptance} only look at variants both were
     * given with the same JSON, so two captures of different add-ons would compare equal on whatever they share: a
     * comparison that decides anything first requires {@link #same()}.
     *
     * @param variants the variants both captures have with the same component and JSON
     * @param onlyBefore variants only the earlier capture has
     * @param onlyAfter variants only the later capture has
     * @param differentJson variants both have, with another component or JSON
     */
    public record Pairing(int variants, int onlyBefore, int onlyAfter, int differentJson) {
        public boolean same() {
            return onlyBefore == 0 && onlyAfter == 0 && differentJson == 0;
        }

        /** Why the captures cannot be compared, or null when they can. */
        public String mismatch() {
            return same() ? null : "captured from different add-ons (" + onlyBefore + " variants only in the earlier capture, "
                + onlyAfter + " only in the later one, " + differentJson + " with other JSON; " + variants + " alike): recapture "
                + "both with the same spec add-on";
        }
    }

    public static Pairing pairing(Path previous, Path current) throws IOException {
        return pairing(SpecCaptures.load(previous), SpecCaptures.load(current));
    }

    static Pairing pairing(SpecCaptures.Capture before, SpecCaptures.Capture after) {
        Map<String, SpecCaptures.Variant> earlier = new HashMap<>();
        before.variants().forEach(v -> earlier.put(v.identifier(), v));
        int same = 0;
        int onlyAfter = 0;
        int different = 0;
        for (SpecCaptures.Variant variant : after.variants()) {
            SpecCaptures.Variant old = earlier.remove(variant.identifier());
            if (old == null) {
                onlyAfter++;
            } else if (!old.component().equals(variant.component()) || !java.util.Objects.equals(old.json(), variant.json())) {
                different++;
            } else {
                same++;
            }
        }
        return new Pairing(same, earlier.size(), onlyAfter, different);
    }

    /** @return the changes, in variant order */
    public static List<Change> compare(Path previous, Path current) throws IOException {
        SpecCaptures.Capture before = SpecCaptures.load(previous);
        SpecCaptures.Capture after = SpecCaptures.load(current);
        Map<String, SpecCaptures.Variant> earlier = new HashMap<>();
        before.variants().forEach(v -> earlier.put(v.identifier(), v));
        List<Change> changes = new ArrayList<>();
        for (SpecCaptures.Variant variant : after.variants()) {
            SpecCaptures.Variant old = earlier.get(variant.identifier());
            if (old == null || !old.component().equals(variant.component()) || !java.util.Objects.equals(old.json(), variant.json())
                || !accepted(before, old) || !accepted(after, variant)) {
                continue;
            }
            for (Map.Entry<String, Leaf[]> d : Wire.diff(before.wire().get(variant.identifier()), after.wire().get(variant.identifier())).entrySet()) {
                changes.add(new Change(variant.component(), variant.identifier(), variant.label(), d.getKey(),
                    d.getValue()[0] == null ? null : d.getValue()[0].toString(), d.getValue()[1] == null ? null : d.getValue()[1].toString()));
            }
        }
        return changes;
    }

    /**
     * What BDS accepts and logs, variant by variant: for every variant both captures were given with the same JSON, one
     * line when one build sent it and the other did not, or when their content log lines for it differ. The packs' format
     * version follows the build, so BDS's warnings that quote it are compared without it.
     *
     * @return the differences, in variant order
     */
    public static List<String> acceptance(Path previous, Path current) throws IOException {
        SpecCaptures.Capture before = SpecCaptures.load(previous);
        SpecCaptures.Capture after = SpecCaptures.load(current);
        String formatBefore = BuildHistory.formatVersion(previous);
        String formatAfter = BuildHistory.formatVersion(current);
        Map<String, SpecCaptures.Variant> earlier = new HashMap<>();
        before.variants().forEach(v -> earlier.put(v.identifier(), v));
        List<String> changes = new ArrayList<>();
        for (SpecCaptures.Variant variant : after.variants()) {
            SpecCaptures.Variant old = earlier.get(variant.identifier());
            if (old == null || !old.component().equals(variant.component()) || !java.util.Objects.equals(old.json(), variant.json())) {
                continue;
            }
            boolean sentBefore = before.wire().containsKey(variant.identifier());
            boolean sentAfter = after.wire().containsKey(variant.identifier());
            List<String> logBefore = withoutFormatVersion(before.log().getOrDefault(variant.identifier(), List.of()), formatBefore);
            List<String> logAfter = withoutFormatVersion(after.log().getOrDefault(variant.identifier(), List.of()), formatAfter);
            if (sentBefore != sentAfter) {
                changes.add(variant.identifier() + " (" + variant.label() + "): " + (sentAfter ? "sent now, not before" : "sent before, not now")
                    + (logAfter.isEmpty() ? "" : "; logs " + logAfter));
            } else if (!new java.util.TreeSet<>(logBefore).equals(new java.util.TreeSet<>(logAfter))) {
                changes.add(variant.identifier() + " (" + variant.label() + "): content log " + logBefore + " -> " + logAfter);
            }
        }
        // Lines no variant is named in
        java.util.Set<String> unattributedBefore = new java.util.TreeSet<>(withoutFormatVersion(before.unattributed(), formatBefore));
        java.util.Set<String> unattributedAfter = new java.util.TreeSet<>(withoutFormatVersion(after.unattributed(), formatAfter));
        unattributedAfter.stream().filter(line -> !unattributedBefore.contains(line)).forEach(line -> changes.add("content log, new: " + line));
        unattributedBefore.stream().filter(line -> !unattributedAfter.contains(line)).forEach(line -> changes.add("content log, gone: " + line));
        return changes;
    }

    private static List<String> withoutFormatVersion(List<String> lines, String formatVersion) {
        return lines.stream().map(line -> BuildHistory.withoutFormatVersion(line, formatVersion)).toList();
    }

    private static boolean accepted(SpecCaptures.Capture capture, SpecCaptures.Variant variant) {
        return capture.wire().containsKey(variant.identifier())
            && capture.log().getOrDefault(variant.identifier(), List.of()).stream().noneMatch(line -> line.startsWith("ERROR"));
    }
}
