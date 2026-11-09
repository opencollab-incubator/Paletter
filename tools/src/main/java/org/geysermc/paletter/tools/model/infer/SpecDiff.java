package org.geysermc.paletter.tools.model.infer;

import org.geysermc.paletter.tools.model.infer.Wire.Leaf;

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

    /** @return the changes, in variant order */
    public static List<Change> compare(Path previous, Path current) throws IOException {
        MappingInference.Capture before = MappingInference.load(previous);
        MappingInference.Capture after = MappingInference.load(current);
        Map<String, MappingInference.Variant> earlier = new HashMap<>();
        before.variants().forEach(v -> earlier.put(v.identifier(), v));
        List<Change> changes = new ArrayList<>();
        for (MappingInference.Variant variant : after.variants()) {
            MappingInference.Variant old = earlier.get(variant.identifier());
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

    private static boolean accepted(MappingInference.Capture capture, MappingInference.Variant variant) {
        return capture.wire().containsKey(variant.identifier())
            && capture.log().getOrDefault(variant.identifier(), List.of()).stream().noneMatch(line -> line.startsWith("ERROR"));
    }
}
