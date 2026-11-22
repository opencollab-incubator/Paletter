package dev.opencollab.paletter.tools.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.MappingWindows;
import dev.opencollab.paletter.tools.model.infer.MappingInference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fields whose evidence another field decides, through the whole loop on a fake server: the first capture varies each field
 * alone, BDS refuses or ignores the values the other field rules out, and the inference must say so (status {@code linked})
 * instead of calling the field server only; the content log becomes links in the feedback, and the next capture varies the
 * fields together, which explains them.
 */
class LinkedFieldsTest {
    /**
     * An item component: {@code chance {min, max}} (0 to 100, both 100 when not set, max must not be below min),
     * {@code replace} (only takes effect when {@code block} is the item itself), and {@code bones} (a map BDS refuses
     * entries of for the {@code geo} of the base).
     */
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "item:minecraft:test", "kind": "item", "category": "component", "name": "minecraft:test", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "chance", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "chance.min", "types": [{"type": "integer"}], "defaultValue": 100, "required": false, "enumValues": [], "examples": [], "doc": "",
              "range": {"min": 0, "max": 100, "text": ""}},
             {"path": "chance.max", "types": [{"type": "integer"}], "defaultValue": 100, "required": false, "enumValues": [], "examples": [], "doc": "",
              "range": {"min": 0, "max": 100, "text": ""}},
             {"path": "block", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "replace", "types": [{"type": "boolean"}], "defaultValue": false, "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "geo", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "bones", "types": [{"type": "map", "items": {"type": "boolean"}}], "defaultValue": {}, "required": false,
              "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {"minecraft:max_stack_size": 64}, "errata": [], "requires": {},
         "samples": {"item:minecraft:test:block": "minecraft:dirt", "item:minecraft:test:geo": "minecraft:full"},
         "alternatives": {"item:minecraft:test:geo": "custom:geo"}, "mapKeys": {"default": "k"}, "context": {}, "skip": {}}""";

    @Test
    void linkedFieldsAreFlaggedThenVariedTogether(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("data/model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Path capture = Files.createDirectories(root.resolve("data/bds/9.9.9.9/spec"));

        // First capture: one field at a time
        AddonGenerator first = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
        assertTrue(first.variants().stream().allMatch(v -> v.linked().isEmpty()), "no feedback yet, no links");
        fakeServer(first, capture, false);
        MappingInference inference = new MappingInference(root);
        inference.run(false);
        JsonObject fields = fields(model);
        assertEquals("linked", fields.getAsJsonObject("chance.max").get("status").getAsString(), "max below the default min is refused");
        assertEquals("[\"chance.min\"]", fields.getAsJsonObject("chance.max").get("linkedTo").toString());
        assertEquals("linked", fields.getAsJsonObject("replace").get("status").getAsString(), "replace is ignored for another block");
        assertEquals("[\"block\"]", fields.getAsJsonObject("replace").get("linkedTo").toString());
        // Only the presence of bones moves the wire (a flag), which alone would read as dropped: its entries were refused
        // for the geo of the base, so it is linked too
        assertEquals("linked", fields.getAsJsonObject("bones").get("status").getAsString(), fields.getAsJsonObject("bones").toString());
        assertEquals("[\"geo\"]", fields.getAsJsonObject("bones").get("linkedTo").toString());
        assertEquals(3, inference.linkedFields().size(), inference.linkedFields().toString());
        JsonObject feedback = JsonParser.parseString(Files.readString(model.resolve("bds-feedback.json"))).getAsJsonObject()
            .getAsJsonObject("item:minecraft:test");
        assertTrue(feedback.getAsJsonArray("chance").toString().contains("order min max"), feedback.toString());
        assertTrue(feedback.getAsJsonArray("replace").toString().contains("identifier block block"), feedback.toString());
        assertTrue(feedback.getAsJsonArray("bones").toString().contains("conflicts geo 'minecraft:full'"), feedback.toString());

        // Second capture: the links from the feedback
        AddonGenerator second = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
        AddonGenerator.Variant max = second.variants().stream().filter(v -> v.path().equals("chance.max") && !v.linked().isEmpty()
            && v.value().getAsInt() == 7).findFirst().orElseThrow();
        assertEquals(List.of("chance.min"), max.linked());
        assertEquals("{\"max\":7,\"min\":0}", max.component().getAsJsonObject().get("chance").toString(), "min at its lowest");
        assertTrue(second.variants().stream().anyMatch(v -> v.path().equals("chance.max") && v.linked().isEmpty() && v.value().getAsInt() == 7),
            "the variant as before stays, so BDS keeps saying what links them");
        AddonGenerator.Variant min = second.variants().stream().filter(v -> v.path().equals("chance.min") && !v.linked().isEmpty()
            && v.value().getAsInt() == 7).findFirst().orElseThrow();
        assertEquals("{\"min\":7,\"max\":100}", min.component().getAsJsonObject().get("chance").toString(), "max at its highest");
        AddonGenerator.Variant replace = second.variants().stream().filter(v -> v.path().equals("replace") && !v.linked().isEmpty())
            .findFirst().orElseThrow();
        assertEquals(replace.identifier(), replace.component().getAsJsonObject().get("block").getAsString(), "the block is the item itself");
        assertNotNull(replace.companion(), "and the add-on defines that block");
        assertEquals(replace.identifier(), replace.companion().getAsJsonObject("minecraft:block").getAsJsonObject("description")
            .get("identifier").getAsString());

        fakeServer(second, capture, false);
        inference = new MappingInference(root);
        inference.run(false);
        fields = fields(model);
        assertEquals("inferred", fields.getAsJsonObject("chance.max").get("status").getAsString(), fields.getAsJsonObject("chance.max").toString());
        assertEquals("identity", fields.getAsJsonObject("chance.max").getAsJsonArray("rules").get(0).getAsJsonObject().get("kind").getAsString());
        assertFalse(fields.getAsJsonObject("chance.max").getAsJsonArray("rules").toString().contains("chance.min"),
            "the min the link set is not explained by max");
        assertFalse(fields.getAsJsonObject("replace").get("status").getAsString().equals("linked"));
        // The linked variants of bones were accepted with the second geo. Each entry only ever had one value, which is no
        // rule: the field is partial, and the conformance suite fails until a rule or a hand transform writes the entries
        AddonGenerator.Variant bones = second.variants().stream().filter(v -> v.path().equals("bones") && !v.linked().isEmpty()
            && !v.value().getAsJsonObject().isEmpty()).findFirst().orElseThrow();
        assertEquals("custom:geo", bones.component().getAsJsonObject().get("geo").getAsString());
        assertEquals("partial", fields.getAsJsonObject("bones").get("status").getAsString(), fields.getAsJsonObject("bones").toString());
        JsonObject evidence = MappingWindows.newest(JsonParser.parseString(Files.readString(model.resolve(
            dev.opencollab.paletter.tools.model.infer.MappingEvidence.FILE))).getAsJsonObject().getAsJsonObject("components")
            .getAsJsonObject("item:minecraft:test")).getAsJsonObject("fields");
        assertTrue(evidence.getAsJsonObject("bones").getAsJsonArray("unexplained").toString().contains("minecraft:test.bones.k"));
        assertEquals(0, inference.linkedFields().size(), inference.linkedFields().toString());
    }

    /**
     * Both fields of the pair write one leaf ({@code chance_span}, max less min), so the leaf a linked variant of {@code max}
     * moves is the one {@code min} moves too, and the linked variant leaves it out by name. The field's own effect on the
     * leaf is masked, which is not the same as having none: {@code max} stays linked (the second capture does not make it
     * server only because a linked variant was accepted).
     */
    @Test
    void aFieldWhoseOnlyLeafItsPartnerAlsoWritesStaysLinkedAfterTheLinkedCapture(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("data/model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Path capture = Files.createDirectories(root.resolve("data/bds/9.9.9.9/spec"));
        fakeServer(new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model)), capture, true);
        new MappingInference(root).run(false);
        assertEquals("linked", fields(model).getAsJsonObject("chance.max").get("status").getAsString(), "no leaf follows max alone");

        AddonGenerator second = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
        assertTrue(second.variants().stream().anyMatch(v -> v.path().equals("chance.max") && v.linked().equals(List.of("chance.min"))));
        fakeServer(second, capture, true);
        MappingInference inference = new MappingInference(root);
        inference.run(false);
        JsonObject max = fields(model).getAsJsonObject("chance.max");
        assertEquals("linked", max.get("status").getAsString(), "the linked variants moved only the leaf min moves: " + max);
        assertEquals("[\"chance.min\"]", max.get("linkedTo").toString());
        assertTrue(inference.linkedFields().stream().anyMatch(field -> field.contains("chance.max")), inference.linkedFields().toString());
    }

    private static JsonObject fields(Path model) throws IOException {
        return MappingWindows.newest(JsonParser.parseString(Files.readString(model.resolve("mappings.json"))).getAsJsonObject()
            .getAsJsonObject("components").getAsJsonObject("item:minecraft:test")).getAsJsonObject("fields");
    }

    /**
     * minecraft:test {chance_min, chance_max: identity, 100 when not set; replace: 1 only when block is the item itself;
     * bones_set: 1 when the map is given; bones: its entries as "1.000000" and "0.000000"}; with {@code span} chance_span
     * (max less min) is the one leaf of both fields instead; max below min is refused, a
     * replace for another block logged as a warning, and entries of bones are refused for the geo minecraft:full.
     */
    private static void fakeServer(AddonGenerator generator, Path capture, boolean span) throws IOException {
        List<NbtMap> items = new ArrayList<>();
        List<NbtMap> blocks = new ArrayList<>();
        List<String> log = new ArrayList<>();
        int blockId = 10000;
        for (AddonGenerator.Variant v : generator.variants()) {
            if (v.companion() != null) {
                blocks.add(NbtMap.builder().putString("name", v.identifier()).putCompound("properties", NbtMap.builder()
                    .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", blockId++).build()).build()).build());
            }
            JsonObject components = v.definition().getAsJsonObject("minecraft:item").getAsJsonObject("components");
            NbtMapBuilder wire = NbtMap.builder().putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", 64).build());
            JsonElement test = components.get("minecraft:test");
            if (test != null && test.isJsonObject()) {
                JsonObject t = test.getAsJsonObject();
                JsonObject chance = t.has("chance") && t.get("chance").isJsonObject() ? t.getAsJsonObject("chance") : new JsonObject();
                double min = chance.has("min") ? chance.get("min").getAsDouble() : 100;
                double max = chance.has("max") ? chance.get("max").getAsDouble() : 100;
                String error = null;
                if (min != Math.rint(min) || max != Math.rint(max)) {
                    error = (min != Math.rint(min) ? "min" : "max") + ": invalid numeric value";
                } else if (min < 0 || min > 100 || max < 0 || max > 100) {
                    error = (min < 0 || min > 100 ? "min" : "max") + ": the input is outside of the accepted range [0, 100]";
                } else if (max < min) {
                    error = "chance: max value cannot be lower than min value";
                } else if (!t.has("block")) {
                    error = "block: missing required field";
                } else if (!t.has("geo")) {
                    error = "geo: missing required field";
                } else if (t.has("bones") && !t.getAsJsonObject("bones").isEmpty() && t.get("geo").getAsString().equals("minecraft:full")) {
                    error = "geo: Invalid bones: Cannot use bones with: minecraft:full";
                }
                if (error != null) {
                    log.add("ERROR [Item]  Error Parsing Item '" + v.identifier() + "':");
                    log.add("ERROR [Item]  '" + v.identifier() + "': Failed to parse field ' -> components -> minecraft:test -> " + error + "'");
                } else {
                    boolean replace = t.has("replace") && t.get("replace").getAsBoolean();
                    String block = t.get("block").getAsString();
                    if (replace && !block.equals(v.identifier())) {
                        log.add("WARN [Item] \"minecraft:test\": Item identifier " + v.identifier() + " does not match the block identifier "
                            + block + ". Cannot replace the block item.");
                    }
                    NbtMapBuilder sent = NbtMap.builder()
                        .putByte("replace", (byte) (replace && block.equals(v.identifier()) ? 1 : 0)).putString("geo", t.get("geo").getAsString());
                    if (span) {
                        sent.putInt("chance_span", (int) (max - min));
                    } else {
                        sent.putInt("chance_min", (int) min).putInt("chance_max", (int) max);
                    }
                    if (t.has("bones")) {
                        NbtMapBuilder bones = NbtMap.builder();
                        t.getAsJsonObject("bones").entrySet().forEach(e -> bones.putString(e.getKey(), e.getValue().getAsBoolean() ? "1.000000" : "0.000000"));
                        sent.putByte("bones_set", (byte) 1).putCompound("bones", bones.build());
                    }
                    wire.putCompound("minecraft:test", sent.build());
                }
            }
            items.add(NbtMap.builder().putString("name", v.identifier()).putBoolean("componentBased", true)
                .putCompound("data", NbtMap.builder().putCompound("components", wire.build()).build()).build());
        }
        writeGzip(capture.resolve("custom_item_registry.nbt"), NbtMap.builder().putList("items", NbtType.COMPOUND, items).build());
        writeGzip(capture.resolve("custom_block_properties.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND,
            blocks.isEmpty() ? new NbtList<>(NbtType.COMPOUND) : new NbtList<>(NbtType.COMPOUND, blocks)).build());
        Files.writeString(capture.resolve("content-log.txt"), String.join("\n", log.stream().sorted().toList()) + "\n");
        Files.writeString(capture.resolve("variants.json"), Model.GSON.toJson(generator.manifest()));
        JsonObject meta = new JsonObject();
        meta.addProperty("bdsVersion", "test");
        meta.addProperty("protocol", 1);
        meta.add("experiments", new JsonArray());
        Files.writeString(capture.resolve("capture.json"), meta.toString());
    }

    private static void writeGzip(Path file, NbtMap map) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file)); NBTOutputStream nbt = NbtUtils.createWriter(out)) {
            nbt.writeTag(map);
        }
    }
}
