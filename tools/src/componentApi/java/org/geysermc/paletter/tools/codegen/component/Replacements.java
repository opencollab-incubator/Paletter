package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which component replaces which: for every component the docs mark deprecated or legacy, or that BDS rejects, the
 * successor components with the evidence for it. Derived from the data; {@code fixtures.json} {@code replacedBy} only
 * adds what no machine readable source says, with the evidence written next to it.
 * <p>
 * Sources, in this order (the first that names a successor wins):
 * <ol>
 *     <li><b>use</b>: a sentence of the component's docs text, docs notes or BDS's own message in the capture that says
 *     "Use minecraft:x ..." (every component named in the rest of that sentence);</li>
 *     <li><b>rename</b>: a docs note "renamed to `minecraft:x`" on the component, or "renamed from `x`" on the successor;</li>
 *     <li><b>field</b>: an active component of the same kind has a top level field of the component's name (the old
 *     component became a field of it).</li>
 * </ol>
 */
final class Replacements {
    /** @param by the successors' ids, @param evidence where it comes from, @param hand whether {@code fixtures.json} states it */
    record Replacement(List<String> by, String evidence, boolean hand) {
    }

    private static final Pattern USE = Pattern.compile("(?i)\\buse\\s+`?(?=[a-z_]+:[a-z_]+)");
    private static final Pattern ID = Pattern.compile("[a-z_]+:[a-z_]+");
    private static final Pattern RENAMED_TO = Pattern.compile("(?i)renamed to `([a-z_]+:[a-z_]+|[a-z_]+)`");
    private static final Pattern RENAMED_FROM = Pattern.compile("(?i)renamed from `([a-z_]+:[a-z_]+|[a-z_]+)`");

    private Replacements() {
    }

    /**
     * @param captureReference the capture the BDS messages come from, e.g. {@code data/bds/1.26.63.1/spec}, or null
     * @return the replacement by component id, in id order
     */
    static Map<String, Replacement> derive(JsonObject components, JsonObject mappings, JsonObject fixtures, String captureReference) {
        Map<String, JsonObject> byId = new TreeMap<>();
        components.getAsJsonArray("components").forEach(e -> byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject()));
        JsonObject mapped = mappings.has("components") ? mappings.getAsJsonObject("components") : new JsonObject();
        Map<String, Replacement> out = new LinkedHashMap<>();
        for (JsonObject doc : byId.values()) {
            String id = doc.get("id").getAsString();
            if (!doc.get("category").getAsString().equals("component")) {
                continue;
            }
            JsonObject mapping = mapped.has(id) ? mapped.getAsJsonObject(id) : null;
            String status = doc.get("status").getAsString();
            boolean rejected = mapping != null && mapping.has("status") && mapping.get("status").getAsString().equals("rejected");
            if (!status.equals("deprecated") && !status.equals("legacy") && !rejected) {
                continue;
            }
            Replacement found = use(id, doc, mapping, byId, captureReference);
            if (found == null) {
                found = rename(id, doc, byId);
            }
            if (found == null) {
                found = field(id, doc, byId);
            }
            if (found != null) {
                out.put(id, found);
            }
        }
        hand(fixtures, byId, out);
        return out;
    }

    private static void hand(JsonObject fixtures, Map<String, JsonObject> byId, Map<String, Replacement> out) {
        if (!fixtures.has("replacedBy")) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : fixtures.getAsJsonObject("replacedBy").entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            String id = e.getKey();
            JsonObject entry = e.getValue().getAsJsonObject();
            if (!byId.containsKey(id)) {
                throw new ComponentGenerator.GenerationException("fixtures.json replacedBy: unknown component " + id);
            }
            if (!entry.has("evidence") || entry.get("evidence").getAsString().isBlank()) {
                throw new ComponentGenerator.GenerationException("fixtures.json replacedBy " + id + " needs an evidence");
            }
            List<String> by = new ArrayList<>();
            JsonArray array = entry.getAsJsonArray("by");
            for (JsonElement b : array) {
                if (!byId.containsKey(b.getAsString())) {
                    throw new ComponentGenerator.GenerationException("fixtures.json replacedBy " + id + ": unknown successor " + b.getAsString());
                }
                by.add(b.getAsString());
            }
            Replacement derived = out.get(id);
            if (derived != null && derived.by().equals(by)) {
                throw new ComponentGenerator.GenerationException("fixtures.json replacedBy " + id + " is derived from the data already ("
                    + derived.evidence() + "); remove it");
            }
            out.put(id, new Replacement(by, entry.get("evidence").getAsString(), true));
        }
    }

    private static Replacement use(String id, JsonObject doc, JsonObject mapping, Map<String, JsonObject> byId, String captureReference) {
        List<String[]> texts = new ArrayList<>();
        texts.add(new String[] {text(doc, "doc"), "docs text"});
        if (doc.has("notes")) {
            doc.getAsJsonArray("notes").forEach(n -> texts.add(new String[] {n.getAsString(), "docs note"}));
        }
        if (mapping != null && mapping.has("note")) {
            texts.add(new String[] {mapping.get("note").getAsString(), "BDS message" + (captureReference == null ? "" : " (" + captureReference + ")")});
        }
        for (String[] text : texts) {
            Matcher m = USE.matcher(text[0]);
            while (m.find()) {
                List<String> by = successors(id, sentence(text[0], m.end()), byId);
                if (!by.isEmpty()) {
                    return new Replacement(by, text[1] + ": \"" + sentence(text[0], m.start()) + "\"", false);
                }
            }
        }
        return null;
    }

    private static Replacement rename(String id, JsonObject doc, Map<String, JsonObject> byId) {
        String kind = doc.get("kind").getAsString();
        if (doc.has("notes")) {
            for (JsonElement n : doc.getAsJsonArray("notes")) {
                Matcher m = RENAMED_TO.matcher(n.getAsString());
                if (m.find() && byId.containsKey(componentId(kind, m.group(1))) && !componentId(kind, m.group(1)).equals(id)) {
                    return new Replacement(List.of(componentId(kind, m.group(1))), "docs note: \"" + n.getAsString() + "\"", false);
                }
            }
        }
        for (JsonObject other : byId.values()) {
            if (!other.get("kind").getAsString().equals(kind) || !other.has("notes")) {
                continue;
            }
            for (JsonElement n : other.getAsJsonArray("notes")) {
                Matcher m = RENAMED_FROM.matcher(n.getAsString());
                if (m.find() && componentId(kind, m.group(1)).equals(id)) {
                    return new Replacement(List.of(other.get("id").getAsString()), "docs note of " + other.get("name").getAsString() + ": \""
                        + n.getAsString() + "\"", false);
                }
            }
        }
        return null;
    }

    private static Replacement field(String id, JsonObject doc, Map<String, JsonObject> byId) {
        String kind = doc.get("kind").getAsString();
        String name = doc.get("name").getAsString().replaceFirst("^[a-z_]+:", "");
        List<String> by = new ArrayList<>();
        for (JsonObject other : byId.values()) {
            if (!other.get("kind").getAsString().equals(kind) || !other.get("category").getAsString().equals("component")
                || !other.get("status").getAsString().equals("active") || other.get("id").getAsString().equals(id)) {
                continue;
            }
            for (JsonElement f : other.getAsJsonArray("fields")) {
                if (f.getAsJsonObject().get("path").getAsString().equals(name)) {
                    by.add(other.get("id").getAsString());
                    break;
                }
            }
        }
        if (by.size() != 1) {
            return null;
        }
        return new Replacement(by, "the docs of " + byId.get(by.getFirst()).get("name").getAsString() + " list a field " + name, false);
    }

    /** The components of the id's kind that the ids named in a sentence refer to (active ones, not the component itself). */
    private static List<String> successors(String id, String sentenceTail, Map<String, JsonObject> byId) {
        String kind = id.substring(0, id.indexOf(':'));
        Set<String> by = new LinkedHashSet<>();
        Matcher m = ID.matcher(sentenceTail);
        while (m.find()) {
            String other = kind + ":" + m.group();
            if (!other.equals(id) && byId.containsKey(other) && byId.get(other).get("category").getAsString().equals("component")
                && byId.get(other).get("status").getAsString().equals("active")) {
                by.add(other);
            }
        }
        return new ArrayList<>(by);
    }

    private static String componentId(String kind, String name) {
        return kind + ":" + (name.contains(":") ? name : "minecraft:" + name);
    }

    /** The sentence of the text that starts at an offset (up to a period followed by a space or the end). */
    private static String sentence(String text, int from) {
        Matcher end = Pattern.compile("\\.(\\s|$)").matcher(text);
        int to = end.find(from) ? end.end(0) : text.length();
        return text.substring(from, to).trim().replaceAll("\"+$", "");
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
