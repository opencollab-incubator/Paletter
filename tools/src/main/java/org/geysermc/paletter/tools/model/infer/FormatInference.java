package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.infer.MappingInference.Obs;
import org.geysermc.paletter.tools.model.rule.Leaf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the format sweep ({@code data/bds/<build>/spec-format}, see {@code FormatSweep}) says about a component BDS rejects at
 * the current format. Per component and format: the variants BDS accepted there, with what each sent compared with the
 * <em>control of the same format</em> (the companions alone: the file's envelope changes with the format, items below 1.16.100
 * are old-style items). Written to {@code mappings.json} as {@code formats: {<format>: {wire, upgradedTo, legacyEnvelope}}}:
 * <ul>
 *     <li>{@code wire}: the keys the variants changed against the control (empty: BDS accepted it and sent nothing);</li>
 *     <li>{@code upgradedTo}: components whose own key those are (BDS upgrades the old component into them, e.g.
 *     {@code block_light_absorption} into {@code light_dampening});</li>
 *     <li>{@code legacyEnvelope}: the item came as an old-style item ({@code componentBased 0}).</li>
 * </ul>
 * A component is in the result when a control at one of its formats exists (a sweep without controls says nothing); an empty
 * {@code formats} means BDS rejected it at every format.
 */
final class FormatInference {
    private FormatInference() {
    }

    /**
     * @param wireKeys the keys on the wire of every component of the mapping, by component id
     * @return per component id: format -> entry (only the formats BDS accepted), in format order
     */
    static Map<String, TreeMap<String, JsonObject>> read(Path dir, Model model, Fixtures fixtures, Map<String, Set<String>> wireKeys) throws IOException {
        MappingInference.Capture capture = MappingInference.load(dir);
        Map<String, String> formats = new HashMap<>();
        JsonParser.parseString(Files.readString(dir.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray().forEach(e -> {
            if (e.getAsJsonObject().has("format")) {
                formats.put(e.getAsJsonObject().get("identifier").getAsString(), e.getAsJsonObject().get("format").getAsString());
            }
        });
        Set<String> legacy = new HashSet<>();
        for (NbtMap item : Nbt.read(dir.resolve("custom_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
            if (!item.getBoolean("componentBased")) {
                legacy.add(item.getString("name"));
            }
        }
        Map<String, TreeMap<String, JsonObject>> result = new TreeMap<>();
        Map<String, Map<String, Obs>> controls = new HashMap<>();
        for (MappingInference.Variant v : capture.variants()) {
            if (v.role().equals("control") && formats.containsKey(v.identifier())) {
                controls.computeIfAbsent(v.component(), k -> new HashMap<>()).put(formats.get(v.identifier()), obs(capture, v, legacy));
                result.computeIfAbsent(v.component(), k -> new TreeMap<>(AddonGenerator::compare));
            }
        }
        Map<String, Map<String, Set<String>>> keys = new TreeMap<>();
        Map<String, Set<String>> legacyAt = new HashMap<>();
        for (MappingInference.Variant v : capture.variants()) {
            Obs control = controls.getOrDefault(v.component(), Map.of()).get(formats.get(v.identifier()));
            Component component = model.components().stream().filter(c -> c.id().equals(v.component())).findFirst().orElse(null);
            Obs own = obs(capture, v, legacy);
            if (!v.role().equals("format") || control == null || !control.accepted() || !own.accepted() || component == null) {
                continue;
            }
            String filler = component.kind().equals("item") && fixtures.requires(component.id()).isEmpty()
                && !fixtures.filler.has(component.name()) ? fixtures.filler.keySet().iterator().next() : null;
            Set<String> footprint = new TreeSet<>();
            Wire.diff(control.wire(), own.wire()).forEach((path, leaves) -> {
                if (filler == null || leaves[1] != null || !path.startsWith(filler)) {
                    footprint.add(path);
                }
            });
            String format = formats.get(v.identifier());
            keys.computeIfAbsent(v.component(), k -> new TreeMap<>()).computeIfAbsent(format, k -> new TreeSet<>()).addAll(MappingInference.wireKeys(footprint));
            if (legacy.contains(v.identifier())) {
                legacyAt.computeIfAbsent(v.component(), k -> new TreeSet<>()).add(format);
            }
        }
        keys.forEach((id, byFormat) -> byFormat.forEach((format, used) -> {
            JsonObject entry = new JsonObject();
            JsonArray wire = new JsonArray();
            used.forEach(wire::add);
            entry.add("wire", wire);
            JsonArray upgraded = new JsonArray();
            successors(id, used, model, wireKeys).forEach(upgraded::add);
            if (!upgraded.isEmpty()) {
                entry.add("upgradedTo", upgraded);
            }
            if (legacyAt.getOrDefault(id, Set.of()).contains(format)) {
                entry.addProperty("legacyEnvelope", true);
            }
            result.get(id).put(format, entry);
        }));
        return result;
    }

    /** An old-style item that sends no components is in the registry but not in the capture's wire: it is accepted, with nothing on it. */
    private static Obs obs(MappingInference.Capture capture, MappingInference.Variant v, Set<String> legacy) {
        Map<String, Leaf> wire = capture.wire().get(v.identifier());
        if (wire == null && legacy.contains(v.identifier())) {
            wire = Map.of();
        }
        return new Obs(v, wire, capture.log().getOrDefault(v.identifier(), List.of()));
    }

    /**
     * The other components of the kind whose own key is among the keys and that send all of them: what the old component became
     * ({@code item_properties.use_duration} and {@code minecraft:use_modifiers} are both on the wire of {@code use_modifiers}).
     */
    private static List<String> successors(String id, Set<String> used, Model model, Map<String, Set<String>> wireKeys) {
        List<String> out = new java.util.ArrayList<>();
        for (Component other : model.components()) {
            Set<String> wire = wireKeys.get(other.id());
            if (!other.id().equals(id) && other.kind().equals(id.substring(0, id.indexOf(':'))) && wire != null && used.contains(other.name())
                && wire.containsAll(used)) {
                out.add(other.id());
            }
        }
        return out;
    }
}
