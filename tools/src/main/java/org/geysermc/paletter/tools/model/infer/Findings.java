package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.rule.PackedBits;
import org.geysermc.paletter.tools.model.rule.Rule;
import org.geysermc.paletter.tools.model.rule.Table;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The unknown bit gate: bits every value of an integral table sets that no field of the leaf explains ({@code residualBits}
 * of the rules, {@code findings} of the component), and whether the format sweeps explain them.
 */
final class Findings {
    /** What the description of a finding no format sweep explains says. */
    static final String UNEXPLAINED = "UNEXPLAINED";

    private Findings() {
    }

    /**
     * Adds the findings of a capture's result. Captures come oldest first and the newest decides: an older window's finding
     * must not hide a bit that only a newer build sets.
     */
    static void collectFindings(Map<String, String> findings, String componentId, JsonObject result, Map<String, Long> formatBits) {
        if (result.has("findings")) {
            result.getAsJsonArray("findings").forEach(f -> findings.put(f.getAsJsonObject().get("id").getAsString(),
                describe(f.getAsJsonObject(), formatBits.getOrDefault(componentId + " " + leafOfFinding(f.getAsJsonObject()), 0L))));
        }
    }

    /**
     * What a finding says, and whether the format sweep explains its bits: a bit BDS sets or clears by the add-on's
     * {@code format_version} is explained, as Paletter writes the current format.
     *
     * @param formatBits the bits of the finding's leaf that the format sweep saw change with the format version
     */
    private static String describe(JsonObject finding, long formatBits) {
        long bits = 0;
        for (JsonElement bit : finding.getAsJsonArray("bits")) {
            bits |= 1L << bit.getAsInt();
        }
        String what = "bits " + finding.get("bits") + " are set in every value (" + finding.get("values") + ") of " + finding.getAsJsonArray("fields").size()
            + " fields that pack into one leaf, and no field explains them";
        return what + ((bits & ~formatBits) == 0
            ? ". Explained: BDS sets them by the add-on's format_version (format sweeps, data/bds/*/spec-format/format-sweep.json), and Paletter writes the current format."
            : ". " + UNEXPLAINED + ": the format sweep does not change bits " + Long.toBinaryString(bits & ~formatBits) + " (binary); name them in the hand transform.");
    }

    /** The leaf name of a finding: the last word of its id ({@code residual-bits <component> <leaf>}). */
    private static String leafOfFinding(JsonObject finding) {
        String id = finding.get("id").getAsString();
        return id.substring(id.lastIndexOf(' ') + 1);
    }

    /**
     * {@code <component> <leaf name>} -> the bits that differ between format versions in the format sweeps of every build
     * ({@code data/bds/<build>/spec-format/format-sweep.json}, field {@code differingBits}, binary).
     */
    static Map<String, Long> formatDependentBits(Path root) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        Path dir = root.resolve(org.geysermc.paletter.tools.Layout.BDS);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> sweeps;
        try (Stream<Path> builds = Files.list(dir)) {
            sweeps = builds.map(b -> b.resolve("spec-format/format-sweep.json")).filter(Files::exists).toList();
        }
        for (Path sweep : sweeps) {
            JsonObject components = JsonParser.parseString(Files.readString(sweep)).getAsJsonObject().getAsJsonObject("components");
            components.entrySet().forEach(component -> component.getValue().getAsJsonObject().entrySet().forEach(key -> {
                JsonObject formats = key.getValue().getAsJsonObject().getAsJsonObject("formats");
                formats.entrySet().forEach(format -> {
                    JsonObject differs = format.getValue().getAsJsonObject().getAsJsonObject("differs");
                    if (differs == null) {
                        return;
                    }
                    differs.entrySet().forEach(leaf -> {
                        JsonObject change = leaf.getValue().getAsJsonObject();
                        if (change.has("differingBits")) {
                            String name = leaf.getKey().substring(leaf.getKey().lastIndexOf('.') + 1);
                            out.merge(component.getKey() + " " + name, Long.parseLong(change.get("differingBits").getAsString(), 2), (a, b) -> a | b);
                        }
                    });
                });
            }));
        }
        return out;
    }

    /**
     * Bits every value of a boolean or enum table sets that no field explains. A field explains the bits its own table varies;
     * several fields that pack into one leaf ({@code face_dimming} and {@code isotropic} into {@code packed_bools}, whichever face) explain the
     * union (leaves are told apart by name). What remains is set whatever the JSON says: a bit whose meaning the capture never varied. The rules get
     * {@code residualBits}, and the component one finding per leaf name in {@code findings}.
     */
    static JsonArray findings(String componentId, JsonObject result) {
        Map<String, List<JsonObject>> byWire = new TreeMap<>();
        Map<JsonObject, String> owner = new java.util.IdentityHashMap<>();
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = e.getValue().getAsJsonObject();
                if (!field.has("rules")) {
                    continue;
                }
                for (JsonElement r : field.getAsJsonArray("rules")) {
                    JsonObject rule = r.getAsJsonObject();
                    if (integralTable(rule) != null) {
                        byWire.computeIfAbsent(leafName(rule.get("wire").getAsString()), k -> new ArrayList<>()).add(rule);
                        owner.put(rule, e.getKey().isEmpty() ? "(value)" : e.getKey());
                    }
                }
            }
        }
        Map<String, JsonObject> findings = new TreeMap<>();
        for (Map.Entry<String, List<JsonObject>> wire : byWire.entrySet()) {
            long explained = 0;
            for (JsonObject rule : wire.getValue()) {
                long[] values = integralTable(rule);
                for (long v : values) {
                    explained |= v ^ values[0];
                }
            }
            for (JsonObject rule : wire.getValue()) {
                long[] values = integralTable(rule);
                long common = values[0];
                for (long v : values) {
                    common &= v;
                }
                long residual = common & ~explained;
                if (residual == 0) {
                    continue;
                }
                JsonArray bits = new JsonArray();
                for (int bit = 0; bit < 63; bit++) {
                    if ((residual >> bit & 1) != 0) {
                        bits.add(bit);
                    }
                }
                rule.add("residualBits", bits);
                JsonObject finding = findings.computeIfAbsent("residual-bits " + componentId + " " + wire.getKey(), id -> {
                    JsonObject f = new JsonObject();
                    f.addProperty("id", id);
                    f.add("bits", new JsonArray());
                    f.add("wires", new JsonArray());
                    f.add("fields", new JsonArray());
                    f.add("values", new JsonArray());
                    return f;
                });
                union(finding.getAsJsonArray("bits"), bits);
                union(finding.getAsJsonArray("wires"), new JsonPrimitive(rule.get("wire").getAsString()));
                union(finding.getAsJsonArray("fields"), new JsonPrimitive(owner.get(rule)));
                for (long v : values) {
                    union(finding.getAsJsonArray("values"), new JsonPrimitive(String.valueOf(v)));
                }
            }
        }
        JsonArray out = new JsonArray();
        findings.values().forEach(f -> {
            sorted(f.getAsJsonArray("values"), Comparator.comparingLong(e -> Long.parseLong(e.getAsString())));
            out.add(f);
        });
        return out;
    }

    /** The last segment of a wire path: the leaf the bits sit in, whichever map key (face) the path goes through. */
    private static String leafName(String wire) {
        return wire.substring(wire.lastIndexOf('.') + 1);
    }

    /** The values of a table rule onto an integral leaf, when every one is a non negative integer; else null. */
    private static long[] integralTable(JsonObject rule) {
        return switch (Rule.of(rule)) {
            case Table table -> table.integral();
            case PackedBits bits -> bits.integral();
            default -> null;
        };
    }

    private static void union(JsonArray into, JsonElement element) {
        List<JsonElement> add = element.isJsonArray() ? element.getAsJsonArray().asList() : List.of(element);
        add.stream().filter(e -> !into.contains(e)).forEach(into::add);
    }

    private static void sorted(JsonArray array, Comparator<JsonElement> order) {
        List<JsonElement> copy = new ArrayList<>(array.asList());
        copy.sort(order);
        while (!array.isEmpty()) {
            array.remove(array.size() - 1);
        }
        copy.forEach(array::add);
    }
}
