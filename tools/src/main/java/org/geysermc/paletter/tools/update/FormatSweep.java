package org.geysermc.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.rule.Leaf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The format sweep of {@code spec-capture --format-sweep}: the add-on's {@code format_version} is the one thing a normal
 * capture never varies, so a wire value that depends on it (a bit BDS sets only for formats from some version on) looks
 * like a constant. The sweep asks BDS the same component at the formats around every "Versioned" boundary it knows:
 * <ul>
 *     <li>the boundaries are the versions BDS names for the component or one of its keys in its own texts, those the
 *     wiki's format histories mark Versioned, and the {@code minFormatVersion} of the docs;</li>
 *     <li>per component, the base at every boundary, at the known version just below it, and at the capture's format
 *     (the reference); per key that has a boundary, the same with the key set to its sample;</li>
 *     <li>a component BDS rejects at the capture's format is asked at every known format, with a control of the same format
 *     ({@link #plan}).</li>
 * </ul>
 * The report ({@code format-sweep.json} next to the capture) lists, per component and key, what BDS refused and which
 * wire leaves differ from the reference, with the bits that differ for numbers: evidence for a hand transform (BDS decides).
 * {@code infer} reads the capture itself for the rejected components ({@code FormatInference}: each variant against the control of its
 * own format).
 */
final class FormatSweep {
    private FormatSweep() {
    }

    /**
     * Adds the sweep's variants to a generator made with {@link AddonGenerator#empty}. A component BDS rejects at the current
     * format ({@code unknown} or {@code deprecated} in the feedback) may still be accepted at an older one, so it is asked at every
     * known format, not only around its boundaries: its base and the fixtures' {@code sweepValues}, each beside a control (the
     * companions alone) at the same format, as the envelope of the file changes with the format.
     */
    static void plan(AddonGenerator generator, Fixtures fixtures, KeyDiscovery.Evidence evidence, String top) {
        Model model = generator.model();
        TreeSet<String> all = new TreeSet<>(AddonGenerator::compare);
        evidence.bdsVersions.values().forEach(all::addAll);
        evidence.wikiVersions.values().forEach(all::addAll);
        model.components().forEach(c -> {
            add(all, c.minFormatVersion());
            c.fields().forEach(f -> add(all, f.minFormatVersion()));
        });
        all.removeIf(v -> AddonGenerator.compare(v, top) > 0);
        all.add(top);
        for (Component c : model.components()) {
            TreeSet<String> component = new TreeSet<>(AddonGenerator::compare);
            add(component, c.minFormatVersion());
            component.addAll(evidence.bdsVersions.getOrDefault(c.name(), new TreeSet<>()));
            component.addAll(evidence.wikiVersions.getOrDefault(c.name(), new TreeSet<>()));
            Map<Field, Set<String>> fields = new TreeMap<>((a, b) -> a.path().compareTo(b.path()));
            for (Field f : c.fields()) {
                TreeSet<String> own = new TreeSet<>(AddonGenerator::compare);
                add(own, f.minFormatVersion());
                own.addAll(evidence.bdsVersions.getOrDefault(f.name(), new TreeSet<>()));
                own.addAll(evidence.wikiVersions.getOrDefault(f.name(), new TreeSet<>()));
                own.removeIf(v -> AddonGenerator.compare(v, top) > 0);
                if (!own.isEmpty() && f.types().stream().noneMatch(t -> t.type().equals("object") || t.type().equals("map"))) {
                    fields.put(f, own);
                }
            }
            component.removeIf(v -> AddonGenerator.compare(v, top) > 0);
            if (rejected(fixtures, c)) {
                for (String format : all) {
                    if (generator.atFormat(c.id(), format, "", null) != null) {
                        generator.controlAt(c.id(), format);
                        fixtures.sweepValues(c.id()).forEach(value -> generator.atFormat(c.id(), format, "", value));
                    }
                }
                continue;
            }
            if (component.isEmpty() && fields.isEmpty()) {
                continue;
            }
            for (String format : formats(all, component, fields, top)) {
                generator.atFormat(c.id(), format, "", null);
            }
            fields.forEach((field, versions) -> {
                for (String format : formats(all, versions, Map.of(), top)) {
                    generator.atFormat(c.id(), format, field.path(), null);
                }
            });
        }
    }

    /** Whether BDS rejected the component at the current format: it does not know it, or calls it deprecated. */
    static boolean rejected(Fixtures fixtures, Component c) {
        return fixtures.fact(c.id(), "", "unknown") || fixtures.fact(c.id(), "", "deprecated");
    }

    private static void add(Set<String> into, String version) {
        if (version != null) {
            into.add(version);
        }
    }

    /** The boundaries, the known version below each, and the reference. */
    private static Set<String> formats(TreeSet<String> all, Set<String> boundaries, Map<Field, Set<String>> fields, String top) {
        TreeSet<String> formats = new TreeSet<>(AddonGenerator::compare);
        formats.add(top);
        List<Set<String>> sources = new ArrayList<>(List.of(boundaries));
        sources.addAll(fields.values());
        for (Set<String> source : sources) {
            for (String boundary : source) {
                formats.add(boundary);
                String below = all.lower(boundary);
                if (below != null) {
                    formats.add(below);
                }
            }
        }
        return formats;
    }

    /**
     * Per component and key ({@code ""}: the base) and format: whether BDS took the variant and how its wire differs
     * from the reference, the variant at the generator's top format.
     *
     * @param leaves the wire of each variant BDS sent ({@code block <id>} / {@code item <id>})
     * @param log    the content log per variant identifier
     */
    static JsonObject report(AddonGenerator generator, String bdsVersion, String top, Map<String, Map<String, Leaf>> leaves,
                             Map<String, List<String>> log) {
        Map<String, Map<String, Map<String, AddonGenerator.Variant>>> grouped = new TreeMap<>();
        for (AddonGenerator.Variant v : generator.variants()) {
            if (v.role().equals("control")) {
                continue; // only compared with, by the mapping inference
            }
            grouped.computeIfAbsent(v.componentId(), k -> new TreeMap<>()).computeIfAbsent(group(v), k -> new TreeMap<>(AddonGenerator::compare))
                .put(v.format(), v);
        }
        JsonObject components = new JsonObject();
        grouped.forEach((component, byPath) -> {
            JsonObject paths = new JsonObject();
            byPath.forEach((path, byFormat) -> {
                AddonGenerator.Variant reference = byFormat.get(top);
                Map<String, Leaf> referenceWire = reference == null ? null : leaves.get(key(reference));
                JsonObject formats = new JsonObject();
                JsonArray same = new JsonArray();
                byFormat.forEach((format, v) -> {
                    if (v == reference) {
                        return;
                    }
                    Map<String, Leaf> wire = leaves.get(key(v));
                    List<String> errors = log.getOrDefault(v.identifier(), List.of()).stream().filter(l -> l.startsWith("ERROR")).toList();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("accepted", wire != null && errors.isEmpty());
                    if (!errors.isEmpty()) {
                        entry.addProperty("error", errors.getFirst().replaceFirst("^.*\\| ", ""));
                    }
                    JsonObject differs = referenceWire == null || wire == null ? new JsonObject() : differences(referenceWire, wire);
                    if (!differs.isEmpty()) {
                        entry.add("differs", differs);
                    }
                    if (differs.isEmpty() && errors.isEmpty() && wire != null && referenceWire != null) {
                        same.add(format);
                    } else {
                        formats.add(format, entry);
                    }
                });
                JsonObject result = new JsonObject();
                result.addProperty("reference", top + (referenceWire == null ? " (rejected or not sent: nothing to compare with)" : ""));
                result.add("formats", formats);
                if (!same.isEmpty()) {
                    result.add("sameAsReference", same);
                }
                paths.add(path, result);
            });
            components.add(component, paths);
        });
        JsonObject json = new JsonObject();
        json.addProperty(Model.GENERATED_KEY, "Generated by `./gradlew :tools:run --args=\"spec-capture --format-sweep\"`: the base of a component, and a "
            + "key with a format boundary, at the format versions around the boundaries, against the reference at " + top + ". Do not edit.");
        json.addProperty("bdsVersion", bdsVersion);
        json.add("components", components);
        return json;
    }

    /** {@code (base)}, {@code (base) <value>} for a whole value of the fixtures' {@code sweepValues}, or the field path. */
    private static String group(AddonGenerator.Variant v) {
        return v.path().isEmpty() ? "(base)" + (v.value() == null ? "" : " " + v.value()) : v.path();
    }

    private static JsonObject differences(Map<String, Leaf> reference, Map<String, Leaf> wire) {
        JsonObject differs = new JsonObject();
        TreeSet<String> paths = new TreeSet<>(reference.keySet());
        paths.addAll(wire.keySet());
        for (String path : paths) {
            Leaf a = reference.get(path);
            Leaf b = wire.get(path);
            if (a != null && a.equals(b)) {
                continue;
            }
            JsonObject leaf = new JsonObject();
            leaf.addProperty("reference", a == null ? "absent" : a.toString());
            leaf.addProperty("value", b == null ? "absent" : b.toString());
            if (a != null && b != null && a.value() instanceof Number x && b.value() instanceof Number y
                && !(x instanceof Float) && !(x instanceof Double)) {
                leaf.addProperty("differingBits", Long.toBinaryString(x.longValue() ^ y.longValue()));
            }
            differs.add(path, leaf);
        }
        return differs;
    }

    private static String key(AddonGenerator.Variant v) {
        return v.kind() + " " + v.identifier();
    }
}
