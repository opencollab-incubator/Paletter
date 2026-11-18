package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.infer.SpecCaptures.Capture;
import dev.opencollab.paletter.tools.model.infer.SpecCaptures.Variant;
import dev.opencollab.paletter.tools.model.rule.Leaf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;

/** What BDS sends around the components of every custom item and block ({@code envelope} of {@code mappings.json}). */
final class Envelope {
    private Envelope() {
    }

    /**
     * What BDS sends around the components of every custom item and block, per kind as protocol windows (oldest first; a
     * capture whose envelope equals the previous window's joins it): {@code {item: [{protocols, leaves}], block: [...]}},
     * leaves as {@code path: "value (type)"}. Item paths are relative to the {@code components} compound, block paths to
     * the block property data.
     * <p>
     * The envelope of a capture is what every accepted control variant of the kind sends alike, once the leaves its
     * companions (fixtures {@code requires}, the item filler) and its description explain are taken out: the rules of the
     * companion and of the description write those (a {@code minecraft:wearable} companion sets
     * {@code item_properties.max_stack_size} to 1). What is left is written whatever the definition says:
     * {@code item_properties} defaults, {@code item_tags}, {@code molangVersion}, {@code vanilla_block_data.material}.
     *
     * @param results per capture: component id -> its result in that capture
     */
    static JsonObject windows(List<Capture> captures, List<Map<String, JsonObject>> results, Fixtures fixtures, IntUnaryOperator dataProtocol) {
        JsonObject out = new JsonObject();
        for (String kind : List.of("item", "block")) {
            JsonArray windows = new JsonArray();
            JsonObject current = null;
            for (int i = 0; i < captures.size(); i++) {
                JsonObject leaves = new JsonObject();
                of(captures.get(i), kind, results.get(i), fixtures).forEach((path, leaf) -> leaves.addProperty(path, leaf.toString()));
                if (current == null || !current.get("leaves").equals(leaves)) {
                    current = new JsonObject();
                    current.add("protocols", new JsonArray());
                    current.add("leaves", leaves);
                    windows.add(current);
                }
                current.getAsJsonArray("protocols").add(dataProtocol.applyAsInt(captures.get(i).protocol()));
            }
            out.add(kind, windows);
        }
        return out;
    }

    /**
     * The envelope of one capture and kind (see {@link #windows}): a leaf is in it when every control that does
     * not explain it sends it with the same value, and at least one such control exists.
     */
    private static Map<String, Leaf> of(Capture capture, String kind, Map<String, JsonObject> results, Fixtures fixtures) {
        Set<String> description = targets(results.get(kind + ":description"));
        List<Map<String, Leaf>> sent = new ArrayList<>();
        List<Set<String>> explainedBy = new ArrayList<>();
        for (Variant v : capture.variants()) {
            Map<String, Leaf> leaves = capture.definitions().get(kind + " " + v.identifier());
            if (!v.kind().equals(kind) || !v.role().equals("control") || leaves == null
                || capture.log().getOrDefault(v.identifier(), List.of()).stream().anyMatch(line -> line.startsWith("ERROR"))) {
                continue;
            }
            Set<String> explained = new TreeSet<>(description);
            for (String companion : companions(v, fixtures)) {
                explained.addAll(targets(results.get(kind + ":" + companion)));
            }
            sent.add(leaves);
            explainedBy.add(explained);
        }
        return shared(sent, explainedBy);
    }

    /**
     * The leaves every control that does not explain them sends alike.
     *
     * @param sent        per control its leaves (block components under {@code components.})
     * @param explainedBy per control the wire paths its companions and description explain
     */
    static Map<String, Leaf> shared(List<Map<String, Leaf>> sent, List<Set<String>> explainedBy) {
        Map<String, Leaf> envelope = new TreeMap<>();
        Set<String> paths = new TreeSet<>();
        sent.forEach(leaves -> paths.addAll(leaves.keySet()));
        for (String path : paths) {
            // Block components sit under "components" in the data; the rules name them without it
            String generic = Wire.generic(path.startsWith("components.") ? path.substring("components.".length()) : path);
            Leaf value = null;
            boolean same = true;
            for (int i = 0; i < sent.size() && same; i++) {
                if (FieldAnalysis.explained(generic, explainedBy.get(i))) {
                    continue;
                }
                Leaf leaf = sent.get(i).get(path);
                same = leaf != null && (value == null || value.equals(leaf));
                value = leaf;
            }
            if (same && value != null) {
                envelope.put(path, value);
            }
        }
        return envelope;
    }

    /** The companion components of a control, as the add-on generator writes them: fixtures {@code requires}, else the item filler. */
    private static Set<String> companions(Variant control, Fixtures fixtures) {
        Set<String> companions = new TreeSet<>(fixtures.requires(control.component()).keySet());
        if (control.kind().equals("item") && companions.isEmpty() && control.json() == null) {
            companions.addAll(fixtures.filler.keySet());
        }
        return companions;
    }

    /** The wire leaves the rules of a result write: rule targets, absent values and constants of its forms and fields. */
    private static Set<String> targets(JsonObject result) {
        Set<String> targets = new TreeSet<>();
        if (result == null) {
            return targets;
        }
        if (result.has("constants")) {
            targets.addAll(result.getAsJsonObject("constants").keySet());
        }
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = e.getValue().getAsJsonObject();
                if (field.has("rules")) {
                    field.getAsJsonArray("rules").forEach(r -> targets.add(r.getAsJsonObject().get("wire").getAsString()));
                }
                if (field.has("whenAbsent")) {
                    targets.addAll(field.getAsJsonObject("whenAbsent").keySet());
                }
            }
        }
        return targets;
    }
}
