package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.component.BlockCompiler;
import org.geysermc.paletter.internal.component.ItemCompiler;
import org.geysermc.paletter.internal.component.WireContext;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
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
 * the JSON, e.g. a field it leaves out), {@code not-generated} (it uses a component that is not generated, e.g. a server
 * only companion with no wire effect). The table goes to {@code build/reports/component-conformance-<protocol>.md}; the
 * expected outcome of every variant that does not pass is committed per protocol in
 * {@code data/model/conformance-known-<protocol>.txt}, with a reason, so the test fails on any change either way.
 */
class ComponentConformanceTest {
    static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    static final Path MODEL = ROOT.resolve("data/model");

    record Outcome(String identifier, String component, String status, String detail) {
    }

    static Path knownFile(int protocol) {
        return MODEL.resolve("conformance-known-" + protocol + ".txt");
    }

    /** The spec captures, oldest protocol first. */
    static List<Path> captures() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT.resolve("data/bds"))) {
            return dirs.map(d -> d.resolve("spec")).filter(d -> Files.exists(d.resolve("capture.json")))
                .sorted(Comparator.comparingInt(ComponentConformanceTest::protocolOf)).toList();
        }
    }

    static int protocolOf(Path capture) {
        try {
            return JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject().get("protocol").getAsInt();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyAcceptedVariantReproducesBdsWire() throws IOException {
        List<Path> captures = captures();
        assertFalse(captures.isEmpty(), "no spec capture in data/bds");
        return captures.stream().map(capture -> DynamicTest.dynamicTest(
            capture.getParent().getFileName() + " (protocol " + protocolOf(capture) + ")",
            () -> conformance(capture, capture.equals(captures.getLast()))));
    }

    private void conformance(Path capture, boolean newest) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject();
        int protocol = meta.get("protocol").getAsInt();
        Path knownFile = knownFile(protocol);

        JsonObject[] recorded = JsonParser.parseString(Files.readString(capture.resolve("variants.json"), StandardCharsets.UTF_8))
            .getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject).toArray(JsonObject[]::new);
        // The definitions of the variants as that capture's add-on had them (its format version). For the newest capture
        // they are also checked against what the generator makes of today's model.
        AddonGenerator generator = new AddonGenerator(Model.read(MODEL.resolve("components.json")), Fixtures.read(MODEL),
            meta.get("formatVersion").getAsString());
        if (newest) {
            assertEquals(recorded.length, generator.variants().size(), "the add-on generator no longer produces the captured variants");
            for (int i = 0; i < recorded.length; i++) {
                AddonGenerator.Variant v = generator.variants().get(i);
                assertEquals(recorded[i].get("json"), v.component() == null || v.component() == JsonNull.INSTANCE ? null : v.component(),
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

        Paletter paletter = Paletter.bundled();
        DataVersion version = paletter.version(protocol);
        VanillaData data = paletter.vanillaData(version);

        List<Outcome> outcomes = new ArrayList<>();
        for (JsonObject entry : recorded) {
            String identifier = entry.get("identifier").getAsString();
            boolean block = entry.get("kind").getAsString().equals("block");
            NbtMap expected = block ? blocks.get(identifier) : items.get(identifier);
            if (expected == null || errors.contains(identifier)) {
                continue; // rejected by BDS: nothing to conform to
            }
            JsonElement json = entry.get("json");
            String component = entry.get("component").getAsString();
            outcomes.add(check(identifier, component, block, generator.definitionOf(component, identifier, json), expected, data));
        }

        // Report
        Map<String, int[]> perComponent = new TreeMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        for (Outcome o : outcomes) {
            int[] counts = perComponent.computeIfAbsent(o.component(), k -> new int[4]);
            counts[List.of("pass", "fail", "unbindable", "not-generated").indexOf(o.status())]++;
            totals.merge(o.status(), 1, Integer::sum);
        }
        StringBuilder report = new StringBuilder("# Component conformance against " + capture.getParent().getFileName() + " (protocol " + protocol + ")\n\n");
        report.append("Accepted variants: ").append(outcomes.size()).append(". ").append(totals).append("\n\n");
        report.append("| component | pass | fail | unbindable | not generated |\n|---|---|---|---|---|\n");
        perComponent.forEach((c, n) -> report.append("| `").append(c).append("` | ").append(n[0]).append(" | ").append(n[1]).append(" | ")
            .append(n[2]).append(" | ").append(n[3]).append(" |\n"));
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

        // Against the committed expectations
        Map<String, String> known = new TreeMap<>();
        Map<String, String> reasons = new TreeMap<>();
        if (Files.exists(knownFile)) {
            for (String line : Files.readAllLines(knownFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] parts = line.split(" ", 3);
                    known.put(parts[0], parts[1]);
                    reasons.put(parts[0], parts.length > 2 ? parts[2] : "");
                }
            }
        }
        Map<String, String> actual = new TreeMap<>();
        outcomes.stream().filter(o -> !o.status().equals("pass")).forEach(o -> actual.put(o.identifier(), o.status()));
        List<String> changes = new ArrayList<>();
        for (String id : new TreeSet<>(union(known.keySet(), actual.keySet()))) {
            String was = known.getOrDefault(id, "pass");
            String now = actual.getOrDefault(id, "pass");
            if (!was.equals(now)) {
                changes.add(id + ": expected " + was + ", got " + now);
            }
        }
        if (Boolean.getBoolean("paletter.updateGolden")) {
            StringBuilder s = new StringBuilder("# Accepted BDS variants (protocol " + protocol + ") the generated API does not reproduce, with why. Written by\n"
                + "# ComponentConformanceTest (-PupdateGolden=true); a variant not listed must pass. Reasons are kept by hand.\n");
            for (Outcome o : outcomes) {
                if (!o.status().equals("pass")) {
                    s.append(o.identifier()).append(' ').append(o.status()).append(' ')
                        .append(reasons.getOrDefault(o.identifier(), o.detail().replace('\n', ' '))).append('\n');
                }
            }
            Files.writeString(knownFile, s.toString(), StandardCharsets.UTF_8);
            return;
        }
        assertTrue(changes.isEmpty(), "conformance of protocol " + protocol + " changed (see build/reports/component-conformance-" + protocol
            + ".md):\n  " + String.join("\n  ", changes));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    private static Outcome check(String identifier, String componentId, boolean block, JsonObject definition, NbtMap expected, VanillaData data) {
        PaletteReport report = new PaletteReport();
        WireContext ctx = WireContext.of(data, report, "conformance");
        NbtMap actual;
        try {
            if (block) {
                CustomBlock custom = ApiBinder.block(definition);
                actual = withoutBlockId(BlockCompiler.propertyData(custom, 10000, ctx));
            } else {
                CustomItem custom = ApiBinder.item(definition);
                actual = ItemCompiler.componentData(custom.identifier(), 0, custom.components(), custom.menuCategory(), custom.creative(), ctx)
                    .getCompound("components");
            }
        } catch (ApiBinder.Unbindable e) {
            String status = e.getMessage().startsWith("no generated") ? "not-generated" : "unbindable";
            return new Outcome(identifier, componentId, status, e.getMessage());
        }
        if (actual.equals(expected)) {
            return new Outcome(identifier, componentId, "pass", "");
        }
        return new Outcome(identifier, componentId, "fail", diff(expected, actual) + (report.entries().isEmpty() ? "" : " report: " + report.entries()));
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
