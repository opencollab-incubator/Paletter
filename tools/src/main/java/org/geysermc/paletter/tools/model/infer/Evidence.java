package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.infer.MappingInference.Capture;
import org.geysermc.paletter.tools.model.infer.MappingInference.Variant;
import org.geysermc.paletter.tools.model.infer.Wire.Leaf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code data/model/evidence.json} (hand written): what proves each wire constant and what explains each finding of the
 * inference. Two sections:
 * <ul>
 *   <li>{@code findings}: key = the finding's id (see {@link MappingInference}); status {@code explained} (capture and
 *       variants that show the explanation, and the explanation) or {@code unexplained} (reason, and the capture that would
 *       explain it)</li>
 *   <li>{@code constants}: key = {@code <component> <wire path>} for a constant of {@code mappings.json}, or
 *       {@code Transforms <id>} for a literal wire constant of {@code Transforms.java} (marked there by a
 *       {@code wire-constant: <id>} comment); status {@code captured} (capture and the variants BDS sent it in, optionally
 *       {@code leaf} and {@code value} to check) or {@code unexplained} (reason, and the capture that would prove it)</li>
 * </ul>
 * Captured entries are checked against the capture itself: the variants exist, BDS accepted them, and they carry the leaf
 * with the value. Entries nothing refers to any more are problems too, so the file cannot rot.
 */
public final class Evidence {
    static final String FILE = "evidence.json";
    static final String TRANSFORMS_SOURCE = "paletter/src/main/java/org/geysermc/paletter/internal/component/Transforms.java";
    static final String TRANSFORMS_PREFIX = "Transforms ";

    /** @param status captured, explained or unexplained */
    record Entry(String key, String status, String capture, List<String> variants, String leaf, String value, String reason,
                 String wouldExplain, String explanation) {
        boolean unexplained() {
            return status.equals("unexplained");
        }
    }

    private final Map<String, Entry> findings = new TreeMap<>();
    private final Map<String, Entry> constants = new TreeMap<>();
    private final List<String> problems = new ArrayList<>();

    /** An absent file is an empty one: every finding and constant is then a problem that says what to add. */
    static Evidence read(Path modelDir) throws IOException {
        Evidence evidence = new Evidence();
        Path file = modelDir.resolve(FILE);
        if (!Files.exists(file)) {
            return evidence;
        }
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        evidence.section(root, "findings", evidence.findings, Set.of("explained", "unexplained"));
        evidence.section(root, "constants", evidence.constants, Set.of("captured", "unexplained"));
        return evidence;
    }

    private void section(JsonObject root, String name, Map<String, Entry> into, Set<String> statuses) {
        if (!root.has(name)) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(name).entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String where = FILE + " " + name + " \"" + e.getKey() + "\": ";
            String status = string(o, "status");
            if (!statuses.contains(status)) {
                problems.add(where + "status must be one of " + statuses + ", is " + (status == null ? "missing" : "\"" + status + "\""));
                continue;
            }
            List<String> variants = new ArrayList<>();
            if (o.has("variants")) {
                o.getAsJsonArray("variants").forEach(v -> variants.add(v.getAsString()));
            }
            Entry entry = new Entry(e.getKey(), status, string(o, "capture"), variants, string(o, "leaf"), string(o, "value"),
                string(o, "reason"), string(o, "wouldExplain"), string(o, "explanation"));
            if (entry.unexplained()) {
                if (blank(entry.reason()) || blank(entry.wouldExplain())) {
                    problems.add(where + "an unexplained entry needs a \"reason\" and the capture that \"wouldExplain\" it");
                }
            } else if (blank(entry.capture()) || (variants.isEmpty() && (blank(entry.leaf()) || blank(entry.value())))) {
                problems.add(where + status + " needs a \"capture\" (path of the spec capture) and its \"variants\" (identifiers)");
            } else if (status.equals("explained") && blank(entry.explanation())) {
                problems.add(where + "an explained finding needs an \"explanation\"");
            }
            into.put(e.getKey(), entry);
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    Collection<Entry> unexplainedFindings() {
        return findings.values().stream().filter(Entry::unexplained).toList();
    }

    Collection<Entry> unexplainedConstants() {
        return constants.values().stream().filter(Entry::unexplained).toList();
    }

    Entry finding(String id) {
        return findings.get(id);
    }

    /**
     * Everything that is wrong with the evidence against what the inference found: findings and constants without an entry
     * (with the entry to paste, the variants filled in when a capture carries the leaf), entries nothing refers to, captured
     * entries the capture does not bear out, and literal wire constants of {@code Transforms.java} without marker or entry.
     *
     * @param findings  id -> description of every finding
     * @param constants {@code <component> <path>} -> value ({@code 3 (int)}) of every constant of the mappings
     * @param newest    the newest capture, to suggest variants from
     */
    List<String> problems(Path root, Map<String, String> findings, Map<String, String> constants, Capture newest,
                          Map<String, Capture> captures) throws IOException {
        List<String> out = new ArrayList<>(problems);
        for (Map.Entry<String, String> finding : findings.entrySet()) {
            if (!this.findings.containsKey(finding.getKey())) {
                out.add("FINDING without evidence: " + finding.getKey() + ": " + finding.getValue() + ". Add to " + FILE + " under \"findings\": "
                    + "\"" + finding.getKey() + "\": {\"status\": \"unexplained\", \"reason\": \"...\", \"wouldExplain\": \"the capture that would explain it\"}"
                    + " (or status \"explained\" with \"capture\", \"variants\" and \"explanation\")");
            }
        }
        this.findings.keySet().stream().filter(k -> !findings.containsKey(k)).forEach(k -> out.add(
            FILE + " findings \"" + k + "\": the inference no longer reports this finding; remove the entry"));

        for (Map.Entry<String, String> constant : constants.entrySet()) {
            if (!this.constants.containsKey(constant.getKey())) {
                String leaf = constant.getKey().substring(constant.getKey().indexOf(' ') + 1);
                out.add("CONSTANT without evidence: " + constant.getKey() + " = " + constant.getValue() + ". Add to " + FILE + " under \"constants\": \""
                    + constant.getKey() + "\": " + suggestion(newest, leaf, constant.getValue()));
            }
        }
        Scan scan = scan(root.resolve(TRANSFORMS_SOURCE));
        scan.unmarked().forEach(line -> out.add("UNMARKED wire constant in " + TRANSFORMS_SOURCE + ":" + line.number() + " `" + line.text().strip()
            + "`: add a `// wire-constant: <id>` comment to the line and an entry \"" + TRANSFORMS_PREFIX + "<id>\" to " + FILE + " under \"constants\""));
        for (String id : scan.ids().keySet()) {
            if (!this.constants.containsKey(TRANSFORMS_PREFIX + id)) {
                out.add("CONSTANT without evidence: " + TRANSFORMS_PREFIX + id + " (" + TRANSFORMS_SOURCE + " lines " + scan.ids().get(id) + "). Add to "
                    + FILE + " under \"constants\": \"" + TRANSFORMS_PREFIX + id + "\": {\"status\": \"captured\", \"capture\": \"data/bds/<build>/spec\", "
                    + "\"variants\": [\"...\"]} (or status \"unexplained\" with \"reason\" and \"wouldExplain\")");
            }
        }
        for (String key : this.constants.keySet()) {
            boolean transforms = key.startsWith(TRANSFORMS_PREFIX);
            if (transforms ? !scan.ids().containsKey(key.substring(TRANSFORMS_PREFIX.length())) : !constants.containsKey(key)) {
                out.add(FILE + " constants \"" + key + "\": " + (transforms ? "no `wire-constant:` marker in " + TRANSFORMS_SOURCE
                    : "mappings.json has no such constant") + "; remove the entry");
            }
        }
        for (Entry entry : concat()) {
            if (!entry.unexplained() && entry.variants().isEmpty() && !blank(entry.leaf()) && !blank(entry.value())) {
                out.add(FILE + " \"" + entry.key() + "\": no variants yet; ones of the capture that send " + entry.leaf() + " = " + entry.value()
                    + ": " + suggestion(captures.getOrDefault(entry.capture(), newest), entry.leaf(), entry.value()));
            } else if (!entry.unexplained() && !blank(entry.capture()) && !entry.variants().isEmpty()) {
                String leaf = entry.leaf();
                String value = entry.value();
                if (leaf == null && constants.containsKey(entry.key())) {
                    leaf = entry.key().substring(entry.key().indexOf(' ') + 1);
                    value = constants.get(entry.key());
                }
                bearOut(root, entry, leaf, value, captures, out);
            }
        }
        return out;
    }

    private List<Entry> concat() {
        List<Entry> all = new ArrayList<>(findings.values());
        all.addAll(constants.values());
        return all;
    }

    /** The capture has the variants, BDS accepted them, and (when named) they carry the leaf with the value. */
    private static void bearOut(Path root, Entry entry, String leaf, String value, Map<String, Capture> captures, List<String> out) {
        Capture capture = captures.get(entry.capture());
        String where = FILE + " \"" + entry.key() + "\": ";
        if (capture == null) {
            out.add(where + "capture \"" + entry.capture() + "\" is not a spec capture of this repository (known: " + captures.keySet() + ")");
            return;
        }
        for (String id : entry.variants()) {
            Variant variant = capture.variants().stream().filter(v -> v.identifier().equals(id)).findFirst().orElse(null);
            if (variant == null) {
                out.add(where + "variant " + id + " is not in " + entry.capture() + "/variants.json");
                continue;
            }
            Map<String, Leaf> wire = capture.wire().get(id);
            if (wire == null || capture.log().getOrDefault(id, List.of()).stream().anyMatch(l -> l.startsWith("ERROR"))) {
                out.add(where + "variant " + id + " was rejected by BDS in " + entry.capture() + ", it proves nothing");
            } else if (leaf != null && wire.entrySet().stream().noneMatch(l -> Wire.generic(l.getKey()).equals(leaf)
                && (value == null || l.getValue().toString().equals(value)))) {
                out.add(where + "variant " + id + " does not send " + leaf + (value == null ? "" : " = " + value) + " in " + entry.capture());
            }
        }
    }

    /** An entry to paste: captured with up to three accepted variants of the newest capture that send the leaf with the value. */
    private static String suggestion(Capture newest, String leaf, String value) {
        List<String> ids = new ArrayList<>();
        if (newest != null) {
            for (Variant v : newest.variants()) {
                Map<String, Leaf> wire = newest.wire().get(v.identifier());
                if (ids.size() < 3 && wire != null && newest.log().getOrDefault(v.identifier(), List.of()).stream().noneMatch(l -> l.startsWith("ERROR"))
                    && wire.entrySet().stream().anyMatch(l -> Wire.generic(l.getKey()).equals(leaf) && l.getValue().toString().equals(value))) {
                    ids.add(v.identifier());
                }
            }
        }
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        return "{\"status\": \"captured\", \"capture\": \"data/bds/" + (newest == null ? "<build>" : newest.bdsVersion()) + "/spec\", \"variants\": "
            + array + "} (or status \"unexplained\" with \"reason\" and \"wouldExplain\")";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The literal wire constants of Transforms.java

    /** A line of the source: 1 based number and text. */
    record Line(int number, String text) {
    }

    /** @param ids marker id -> the lines it marks; @param unmarked flagged lines without a marker */
    record Scan(Map<String, List<Integer>> ids, List<Line> unmarked) {
    }

    private static final Pattern MARKER = Pattern.compile("//\\s*wire-constant:\\s*([a-z0-9_.]+)");
    /**
     * The literal forms that put a constant on the wire, recognised on a line of code: a narrowing cast of a number
     * ({@code (byte) 0}), a {@code static final} declaration, a ternary that yields a literal, a {@code put} of a literal,
     * a float literal, a {@code switch} arm that yields a string. A constant written in another form is not found: keep to these.
     */
    private static final List<Pattern> LITERALS = List.of(
        Pattern.compile("\\((?:byte|short|int|long)\\)\\s*-?\\d"),
        Pattern.compile("\\bstatic final\\b[^;]*="),
        Pattern.compile("\\?\\s*(?:-?\\d[\\w.]*|\"[^\"]*\")\\s*:"),
        Pattern.compile("\\.put\\w*\\(\\s*\"[^\"]*\"\\s*,\\s*(?:-?\\d+f?|\"[^\"]*\")\\s*\\)"),
        Pattern.compile("\\b\\d+(?:\\.\\d+)?f\\b"),
        Pattern.compile("->\\s*\"[^\"]*\""));

    /** Every flagged line needs a {@code // wire-constant: <id>} comment on it, or alone on the line above it. */
    static Scan scan(Path source) throws IOException {
        // No source (a synthetic model): no constants; evidence entries for Transforms then show up as stale
        return scan(Files.exists(source) ? Files.readAllLines(source, StandardCharsets.UTF_8) : List.of());
    }

    static Scan scan(List<String> lines) {
        Map<String, List<Integer>> ids = new TreeMap<>();
        List<Line> unmarked = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            Matcher marker = MARKER.matcher(text);
            String id = marker.find() ? marker.group(1) : null;
            String stripped = text.strip();
            if (id != null && stripped.startsWith("//")) {
                continue; // a marker alone on a line belongs to the line below
            }
            if (stripped.startsWith("*") || stripped.startsWith("//") || stripped.startsWith("/*")) {
                continue;
            }
            if (id == null && i > 0) {
                Matcher above = MARKER.matcher(lines.get(i - 1));
                if (lines.get(i - 1).strip().startsWith("//") && above.find()) {
                    id = above.group(1);
                }
            }
            String code = text.replaceFirst("//.*$", "");
            if (id != null) {
                ids.computeIfAbsent(id, k -> new ArrayList<>()).add(i + 1);
            } else if (LITERALS.stream().anyMatch(p -> p.matcher(code).find())) {
                unmarked.add(new Line(i + 1, text));
            }
        }
        return new Scan(ids, unmarked);
    }
}
