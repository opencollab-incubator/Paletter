package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.tools.bds.CapturePlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.component.BlockCompiler;
import dev.opencollab.paletter.internal.component.ItemCompiler;
import dev.opencollab.paletter.internal.component.WireContext;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance suite, once per spec capture ({@code data/bds/<version>/spec}, every protocol that was captured): every
 * variant of the capture that BDS accepted (sent, no content log error) is rebuilt from its add-on JSON through the
 * generated API ({@link ApiBinder}), compiled for the capture's protocol, and compared with the NBT BDS sent for it: the
 * whole item component data, or the whole block property data except the load order dependent
 * {@code vanilla_block_data.block_id}. The variants are the capture's own ({@code variants.json}), so a capture of an older
 * build is checked against what that build was given, not against what today's model would generate.
 * <p>
 * Outcomes per variant: {@code pass}, {@code fail} (different NBT), {@code unbindable} (the generated API cannot express
 * the JSON), {@code not-generated} (it uses a component that is not generated), {@code missing} (BDS neither sent it nor
 * logged an error for it), {@code unsupported} (it differs only in {@code enabled_states} flags the generated trait state
 * table marks unsupported in the protocol from BDS's own data, each reported as left out). Only {@code pass} and
 * {@code unsupported} are accepted: BDS always wins, so nothing else is waived and there is no list of known exceptions. The only variants not compared are those the content log has an ERROR line for (counted in the report),
 * and every component of a capture needs at least one compared variant, or an account from the data of the format sweep
 * ({@link OldFormats}: replaced, old-style item, server-only, rejected at every format). The table goes to
 * {@code build/reports/component-conformance-<protocol>.md}.
 */
class ComponentConformanceTest {
    static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    static final Path MODEL = ROOT.resolve("data/model");

    record Outcome(String identifier, String component, String status, String detail) {
    }

    /** The spec captures, oldest protocol first. */
    static List<Path> captures() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT.resolve("data/bds"))) {
            return dirs.map(d -> d.resolve("spec")).filter(d -> Files.exists(d.resolve("capture.json")))
                .sorted(Comparator.comparingInt(ComponentConformanceTest::protocolOf)).toList();
        }
    }

    static int protocolOf(Path capture) {
        return meta(capture).get("protocol").getAsInt();
    }

    static boolean isPreview(Path capture) {
        JsonObject meta = meta(capture);
        return meta.has("preview") && meta.get("preview").getAsBoolean();
    }

    private static JsonObject meta(Path capture) {
        try {
            return JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The bundled version a capture is of: the newest bundled version, previews included, that is not above its protocol. A
     * release capture must land on a release (not on a preview newer than it, nor on an older release because its own is
     * not bundled), a preview capture on the preview of its own protocol.
     */
    static DataVersion versionOf(int protocol, boolean preview) {
        DataVersion version = withPreviews().version(protocol);
        boolean previewVersion = !Paletter.bundled().versions().contains(version);
        assertEquals(preview, previewVersion, "the " + (preview ? "preview" : "release") + " capture of protocol " + protocol
            + " resolves to the " + (previewVersion ? "preview " : "release ") + version);
        assertTrue(!preview || version.protocolVersion() == protocol, "the preview " + protocol + " is not bundled, got " + version);
        return version;
    }

    @TestFactory
    Stream<DynamicTest> everyAcceptedVariantReproducesBdsWire() throws IOException {
        // Captures of different BDS platforms are never compared: fails with the captures per platform if data/bds mixes them
        CapturePlatform.single(ROOT.resolve("data/bds"));
        List<Path> captures = captures();
        assertFalse(captures.isEmpty(), "no spec capture in data/bds");
        return captures.stream().map(capture -> DynamicTest.dynamicTest(
            capture.getParent().getFileName() + " (protocol " + protocolOf(capture) + ")",
            () -> conformance(capture, capture.equals(captures.getLast()))));
    }

    private void conformance(Path capture, boolean newest) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject();
        int protocol = meta.get("protocol").getAsInt();

        JsonArray manifest = JsonParser.parseString(Files.readString(capture.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject[] recorded = manifest.asList().stream().map(JsonElement::getAsJsonObject).toArray(JsonObject[]::new);
        // The definitions of the variants as that capture's add-on had them (its format version and the bases it shrunk).
        // For the newest capture they are also checked against what the generator makes of today's model. Probed keys
        // apply to the builds of the probed build's line, as in spec-capture.
        Fixtures fixtures = Fixtures.read(MODEL).forBuild(meta.get("bdsVersion").getAsString());
        AddonGenerator generator = new AddonGenerator(Model.read(MODEL.resolve("components.json")), fixtures,
            meta.get("formatVersion").getAsString(), AddonGenerator.Shrunk.of(manifest));
        if (newest) {
            List<JsonObject> captured = Arrays.asList(recorded);
            List<AddonGenerator.Variant> generated = generator.variants();
            assertEquals(captured.size(), generated.size(), "the add-on generator no longer produces the captured variants");
            for (int i = 0; i < captured.size(); i++) {
                AddonGenerator.Variant v = generated.get(i);
                assertEquals(captured.get(i).get("json"), v.component() == null || v.component() == JsonNull.INSTANCE ? null : v.component(),
                    v.identifier() + " differs from the captured variant");
            }
        }

        Map<String, NbtMap> items = new HashMap<>();
        for (NbtMap item : read(capture.resolve("custom_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
            if (item.getBoolean("componentBased")) {
                items.put(item.getString("name"), item.getCompound("data").getCompound("components"));
            }
        }
        Map<String, NbtMap> blocks = new HashMap<>();
        for (NbtMap block : read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            blocks.put(block.getString("name"), withoutBlockId(block.getCompound("properties")));
        }
        Set<String> errors = errorVariants(capture.resolve("content-log.txt"));

        boolean preview = isPreview(capture);
        DataVersion version = versionOf(protocol, preview);
        VanillaData data = Access.palette().vanillaData(preview ? withPreviews() : Paletter.bundled(), version);

        List<Outcome> outcomes = new ArrayList<>();
        Set<String> components = new TreeSet<>();
        Set<String> compared = new TreeSet<>();
        int rejected = 0;
        int oldForm = 0;
        for (JsonObject entry : recorded) {
            String identifier = entry.get("identifier").getAsString();
            boolean block = entry.get("kind").getAsString().equals("block");
            String component = entry.get("component").getAsString();
            components.add(component);
            if (errors.contains(identifier)) {
                rejected++; // rejected by BDS: nothing to conform to
                continue;
            }
            if (component.endsWith(":minecraft:custom_components") && entry.has("json") && entry.get("json").isJsonArray()) {
                oldForm++; // accepted, in the old list-of-names form: the API deliberately offers only the new form
                continue;
            }
            NbtMap expected = block ? blocks.get(identifier) : items.get(identifier);
            if (expected == null) {
                outcomes.add(new Outcome(identifier, component, "missing", "BDS neither sent it nor logged an error for it"));
                continue;
            }
            outcomes.add(check(identifier, component, block, generator.definitionOf(component, identifier, entry.has("role") ? entry.get("role").getAsString() : "", entry.get("json")), expected, data, protocol));
            // The control variant is accepted whatever the component is, so it says nothing of it; the base one sets the component
            String role = entry.has("role") ? entry.get("role").getAsString() : "";
            if (!role.equals("control")) {
                compared.add(component);
            }
        }
        Set<String> uncompared = new TreeSet<>(components);
        uncompared.removeAll(compared);
        List<String> unexplained = new ArrayList<>();
        JsonObject mappings = JsonParser.parseString(Files.readString(MODEL.resolve("mappings.json"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("components");
        for (String component : uncompared) {
            if (fixtures.skip(component, null) != null) {
                // Not modelled (fixtures.json skip), so no generated encoding to verify: block:minecraft:tag, whose tag:<name>
                // components every captured build rejects ("not present in the Schema")
                continue;
            }
            String how = OldFormats.classify(mappings.has(component) ? mappings.getAsJsonObject(component) : null, OldFormats.notSent(whitebox(), component), compared);
            if (how == null || how.equals(OldFormats.PENDING_SWEEP)) {
                unexplained.add(component + (how == null ? " (its formats fit no class)" : " (no format sweep data)"));
            }
        }
        assertTrue(unexplained.isEmpty(), "protocol " + protocol + ": no variant of these components, other than the control one, was both accepted by BDS "
            + "and found in its data, and the format sweep does not account for them (replaced, old-style item, server-only, rejected at every format), "
            + "so their generated encoding is not verified: " + unexplained);
        checkRetirements(mappings, compared);

        // Report
        Map<String, int[]> perComponent = new TreeMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        for (Outcome o : outcomes) {
            int[] counts = perComponent.computeIfAbsent(o.component(), k -> new int[6]);
            counts[List.of("pass", "fail", "unbindable", "not-generated", "missing", "unsupported").indexOf(o.status())]++;
            totals.merge(o.status(), 1, Integer::sum);
        }
        StringBuilder report = new StringBuilder("# Component conformance against " + capture.getParent().getFileName() + " (protocol " + protocol + ")\n\n");
        report.append("Accepted variants: ").append(outcomes.size()).append(". ").append(totals).append(". Rejected by BDS (content log ERROR, not compared): ")
            .append(rejected).append(". Accepted old-form custom_components (not in the API, not compared): ").append(oldForm).append("\n\n");
        report.append("| component | pass | fail | unbindable | not generated | missing | unsupported trait state |\n|---|---|---|---|---|---|---|\n");
        perComponent.forEach((c, n) -> report.append("| `").append(c).append("` | ").append(n[0]).append(" | ").append(n[1]).append(" | ")
            .append(n[2]).append(" | ").append(n[3]).append(" | ").append(n[4]).append(" | ").append(n[5]).append(" |\n"));
        report.append("\n## Not passing\n\n");
        for (Outcome o : outcomes) {
            if (!o.status().equals("pass")) {
                report.append("- `").append(o.identifier()).append("` ").append(o.status()).append(": ").append(o.detail()).append('\n');
            }
        }
        Path out = ROOT.resolve("tools/build/reports/component-conformance-" + protocol + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report.substring(0, report.indexOf("\n## Not passing")));

        // No waivers: BDS decides, so every accepted variant must be reproduced exactly. Only an enabled_states value the trait
        // state table marks unsupported (from BDS's own data), which Paletter leaves out with a report, is counted in the
        // report table instead
        List<String> notPassing = outcomes.stream().filter(o -> !o.status().equals("pass") && !o.status().equals("unsupported"))
            .map(o -> o.identifier() + " " + o.status() + ": " + o.detail()).toList();
        assertTrue(notPassing.isEmpty(), "protocol " + protocol + ": " + notPassing.size() + " variants BDS accepted are not reproduced by the generated API"
            + " (see build/reports/component-conformance-" + protocol + ".md):\n  " + String.join("\n  ", notPassing));
    }

/**
 * The retirements against the data: every component of {@code api.json} {@code retired} must be one BDS never sends (server-only or
 * rejected at every format; {@code seed} only inside an old-style item), and no kept deprecated component may be one.
 */
private static void checkRetirements(JsonObject mappings, Set<String> compared) throws IOException {
    JsonObject api = JsonParser.parseString(Files.readString(MODEL.resolve("api.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    JsonObject components = JsonParser.parseString(Files.readString(MODEL.resolve("components.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    Set<String> retired = new TreeSet<>(api.getAsJsonObject("retired").keySet());
    retired.removeIf(id -> id.startsWith("_"));
    List<String> wrong = new ArrayList<>();
    for (String id : retired) {
        String how = OldFormats.classify(mappings.has(id) ? mappings.getAsJsonObject(id) : null, OldFormats.notSent(whitebox(), id), compared);
        boolean ok = OldFormats.SERVER_ONLY.equals(how) || OldFormats.REJECTED_EVERYWHERE.equals(how)
            || (id.equals("item:minecraft:seed") && OldFormats.OLD_STYLE_ITEM.equals(how)) || OldFormats.PENDING_SWEEP.equals(how);
        if (!ok) {
            wrong.add(id + " is " + how + ": it reaches the wire, keep its record");
        }
    }
    for (JsonElement e : components.getAsJsonArray("components")) {
        JsonObject c = e.getAsJsonObject();
        String id = c.get("id").getAsString();
        String status = c.get("status").getAsString();
        if (!c.get("category").getAsString().equals("component") || !(status.equals("deprecated") || status.equals("legacy")) || retired.contains(id)
            || id.endsWith(":minecraft:custom_components") || api.getAsJsonObject("handWritten").has(id)) {
            // a hand-written component (api.json handWritten) is kept for what it writes, not for what BDS sends
            continue;
        }
        String how = OldFormats.classify(mappings.has(id) ? mappings.getAsJsonObject(id) : null, OldFormats.notSent(whitebox(), id), compared);
        if (OldFormats.SERVER_ONLY.equals(how) || OldFormats.REJECTED_EVERYWHERE.equals(how)) {
            wrong.add(id + " is " + how + ": BDS never sends it, retire it in api.json");
        }
    }
    assertTrue(wrong.isEmpty(), "retirements against the data: " + wrong);
}

/** The white-box of the newest build that has one, or null. */
    private static JsonObject whitebox() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT.resolve("data/bds"))) {
            Path newest = dirs.filter(d -> Files.exists(d.resolve("whitebox.json"))).max(Comparator.comparing(d -> d.getFileName().toString(), dev.opencollab.paletter.tools.Versions.ORDER)).orElse(null);
            return newest == null ? null : JsonParser.parseString(Files.readString(newest.resolve("whitebox.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    private static Outcome check(String identifier, String componentId, boolean block, JsonObject definition, NbtMap expected, VanillaData data,
                                 int protocol) {
        PaletteReportBuilder report = new PaletteReportBuilder();
        WireContext ctx = WireContext.of(data, report, "conformance");
        NbtMap actual;
        try {
            if (block) {
                CustomBlock custom = ApiBinder.block(definition, protocol);
                actual = withoutBlockId(BlockCompiler.propertyData(custom, 10000, ctx));
            } else {
                CustomItem custom = ApiBinder.item(definition, protocol);
                actual = ItemCompiler.componentData(custom.identifier(), 0, custom.components(), custom.menuCategory(), ctx)
                    .getCompound("components");
            }
        } catch (ApiBinder.Unbindable e) {
            String status = e.getMessage().startsWith("no generated") ? "not-generated" : "unbindable";
            return new Outcome(identifier, componentId, status, e.getMessage());
        }
        if (actual.equals(expected)) {
            return new Outcome(identifier, componentId, "pass", "");
        }
        String diff = diff(expected, actual);
        if (block && onlyUnsupportedTraitStates(diff, expected, protocol, report)) {
            return new Outcome(identifier, componentId, "unsupported", diff + " report: " + report.entries());
        }
        return new Outcome(identifier, componentId, "fail", diff + (report.entries().isEmpty() ? "" : " report: " + report.entries()));
    }

    private static final java.util.regex.Pattern TRAIT_FLAG =
        java.util.regex.Pattern.compile("traits\\[(\\d+)]\\.enabled_states\\.([a-z_]+): BDS 1 \\(Byte\\), Paletter 0 \\(Byte\\)");

    /**
     * Whether the block differs from BDS only in {@code enabled_states} flags the generated trait state table marks unsupported in
     * the protocol, each reported as left out: BDS 1.26.36.1 accepts {@code minecraft:sixteen_way_rotation} but lists its state with
     * duplicate values, so Paletter does not send it in 26.30 (docs/wire-notes.md). Decided by the data, not a list of variants.
     */
    static boolean onlyUnsupportedTraitStates(String diff, NbtMap expected, int protocol, PaletteReportBuilder report) {
        List<NbtMap> traits = expected.getList("traits", NbtType.COMPOUND, List.of());
        for (String leaf : diff.split("; ")) {
            java.util.regex.Matcher m = TRAIT_FLAG.matcher(leaf);
            if (!m.matches() || Integer.parseInt(m.group(1)) >= traits.size()) {
                return false;
            }
            String trait = traits.get(Integer.parseInt(m.group(1))).getString("name");
            String value = "minecraft:" + m.group(2);
            if (dev.opencollab.paletter.internal.component.TraitStateTable.unsupported(trait, value, protocol) == null
                || report.warnings().stream().noneMatch(w -> w.message().contains("enabled_states = " + value))) {
                return false;
            }
        }
        return true;
    }

    private static NbtMap withoutBlockId(NbtMap properties) {
        if (!properties.containsKey("vanilla_block_data")) {
            return properties;
        }
        NbtMap data = properties.getCompound("vanilla_block_data");
        org.cloudburstmc.nbt.NbtMapBuilder builder = data.toBuilder();
        builder.remove("block_id");
        return properties.toBuilder().putCompound("vanilla_block_data", builder.build()).build();
    }

    static String diff(NbtMap expected, NbtMap actual) {
        Map<String, String> a = new TreeMap<>();
        Map<String, String> b = new TreeMap<>();
        flatten("", expected, a);
        flatten("", actual, b);
        List<String> out = new ArrayList<>();
        for (String key : union(a.keySet(), b.keySet())) {
            String x = a.get(key);
            String y = b.get(key);
            if (x == null || !x.equals(y)) {
                out.add(key + ": BDS " + (x == null ? "-" : x) + ", Paletter " + (y == null ? "-" : y));
            }
        }
        return String.join("; ", out.subList(0, Math.min(out.size(), 6))) + (out.size() > 6 ? "; ... (" + out.size() + ")" : "");
    }

    private static void flatten(String path, Object value, Map<String, String> out) {
        if (value instanceof NbtMap map) {
            if (map.isEmpty()) {
                out.put(path, "{}");
            }
            map.forEach((k, v) -> flatten(path.isEmpty() ? k : path + "." + k, v, out));
        } else if (value instanceof NbtList<?> list) {
            if (list.isEmpty()) {
                out.put(path, "[] (" + list.getType().getTagClass().getSimpleName() + ")");
            }
            for (int i = 0; i < list.size(); i++) {
                flatten(path + "[" + i + "]", list.get(i), out);
            }
        } else {
            out.put(path, value + " (" + value.getClass().getSimpleName() + ")");
        }
    }

    /** Variants with an ERROR line in the content log, attributed like the mapping inference does. */
    static Set<String> errorVariants(Path log) throws IOException {
        Set<String> out = new TreeSet<>();
        Pattern blockFile = Pattern.compile("blocks/([a-z0-9_]+)\\.json");
        Pattern itemFile = Pattern.compile("items/([a-z0-9_]+)\\.json");
        Pattern itemName = Pattern.compile("'(paletter:[a-z0-9_]+)'");
        Pattern bare = Pattern.compile("(paletter:[a-z0-9_]+)");
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (!line.startsWith("ERROR")) {
                continue;
            }
            Matcher m;
            if ((m = blockFile.matcher(line)).find() || (m = itemFile.matcher(line)).find()) {
                out.add("paletter:" + m.group(1));
            } else if ((m = itemName.matcher(line)).find() || (m = bare.matcher(line)).find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** The bundled data with its previews: the capture of a preview build is compiled for the preview's own version. */
    static Paletter withPreviews() {
        return Paletter.builder().previews(true).build();
    }

    static NbtMap read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gzip.readAllBytes();
            }
        }
        try (NBTInputStream input = NbtUtils.createReader(new ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        }
    }
}
