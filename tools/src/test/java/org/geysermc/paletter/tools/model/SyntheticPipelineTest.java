package org.geysermc.paletter.tools.model;

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
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.infer.MappingInference;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole tooling on a small synthetic model: the generated variants against a golden file, then an inference over
 * a fake server (a known JSON -> wire function) that must find exactly that function, deterministically.
 */
class SyntheticPipelineTest {
    /** One item component: seconds (number -> ticks), mode (enum -> int), quiet (boolean, server only). */
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "item:minecraft:test", "kind": "item", "category": "component", "name": "minecraft:test", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [],
           "forms": [{"type": "object"}, {"type": "boolean"}],
           "fields": [
             {"path": "seconds", "types": [{"type": "number"}], "required": true, "enumValues": [], "examples": [], "doc": "",
              "range": {"min": 0, "max": 100, "text": ""}},
             {"path": "mode", "types": [{"type": "string"}], "defaultValue": "a", "required": false,
              "enumValues": ["a", "b", "c"], "examples": [], "doc": ""},
             {"path": "quiet", "types": [{"type": "boolean"}], "defaultValue": false, "required": false, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {"minecraft:max_stack_size": 64}, "errata": [], "requires": {}, "samples": {},
         "mapKeys": {"default": "k"}, "context": {}, "skip": {}}""";

    private static Path modelDir(Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("data/model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        return model;
    }

    private static AddonGenerator generator(Path model) throws IOException {
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
    }

    @Test
    void generatedVariantsMatchGolden(@TempDir Path root) throws IOException {
        Path model = modelDir(root);
        String actual = Model.GSON.toJson(generator(model).manifest()) + "\n";
        String golden;
        try (InputStream in = getClass().getResourceAsStream("/golden/synthetic-variants.json")) {
            golden = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        if (Boolean.getBoolean("spec.updateGolden") || golden.isEmpty()) {
            Path file = Path.of("src/test/resources/golden/synthetic-variants.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual);
        }
        assertEquals(golden.isEmpty() ? actual : golden, actual, "variants differ from src/test/resources/golden (-Dspec.updateGolden=true)");
        // deterministic
        assertEquals(actual, Model.GSON.toJson(generator(model).manifest()) + "\n");
    }

    @Test
    void formatVersionFollowsTheBuild() {
        assertEquals("1.26.45", AddonGenerator.formatVersionFor("1.26.60", "1.26.45.1"));
        assertEquals("1.26.52", AddonGenerator.formatVersionFor("1.26.60", "1.26.52.3"));
        assertEquals("1.26.60", AddonGenerator.formatVersionFor("1.26.60", "1.26.63.1"), "a newer build keeps the model's format");
        assertEquals("1.26.60", AddonGenerator.formatVersionFor("1.26.60", "1.26.60.0"));
        assertEquals("1.25.99", AddonGenerator.formatVersionFor("1.26.60", "1.25.99.7"));
        assertEquals("1.26.60", AddonGenerator.formatVersionFor("1.26.60", "1.27.0.1"));
    }

    @Test
    void addonAsksForTheBuildsFormatVersion(@TempDir Path root) throws IOException {
        Path model = modelDir(root);
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.45");
        Path out = root.resolve("addon");
        generator.write(out);
        for (String manifest : List.of("behavior_pack/manifest.json", "resource_pack/manifest.json")) {
            JsonObject header = JsonParser.parseString(Files.readString(out.resolve(manifest))).getAsJsonObject().getAsJsonObject("header");
            assertEquals("[1,26,45]", header.get("min_engine_version").toString(), manifest);
        }
        String item = Files.readString(out.resolve("behavior_pack/" + generator.variants().getFirst().file()));
        assertEquals("1.26.45", JsonParser.parseString(item).getAsJsonObject().get("format_version").getAsString());
        // the default is the model's format version
        Path other = root.resolve("default");
        generator(model).write(other);
        assertEquals("1.26.60", JsonParser.parseString(Files.readString(other.resolve("behavior_pack/" + generator.variants().getFirst().file())))
            .getAsJsonObject().get("format_version").getAsString());
    }

    @Test
    void inferenceFindsTheServerFunction(@TempDir Path root) throws IOException {
        Path model = modelDir(root);
        AddonGenerator generator = generator(model);
        Path capture = Files.createDirectories(root.resolve("data/bds/9.9.9.9/spec"));
        fakeServer(generator, capture);

        new MappingInference(root).run(false);
        String first = Files.readString(model.resolve("mappings.json"));
        assertTrue(new MappingInference(root).run(true), "a second run must reproduce the outputs");

        JsonObject test = JsonParser.parseString(first).getAsJsonObject().getAsJsonObject("components").getAsJsonObject("item:minecraft:test");
        assertEquals("sent", test.get("status").getAsString());
        JsonObject fields = test.getAsJsonObject("fields");

        JsonObject seconds = fields.getAsJsonObject("seconds");
        assertEquals("inferred", seconds.get("status").getAsString());
        JsonObject ticks = rule(seconds, "minecraft:test.ticks");
        assertEquals("linear", ticks.get("kind").getAsString());
        assertEquals(20.0, ticks.get("scale").getAsDouble());
        assertEquals("int", ticks.get("nbt").getAsString());
        assertTrue(seconds.getAsJsonArray("rejectedValues").size() > 0, "negative seconds are rejected by the fake server");

        JsonObject mode = fields.getAsJsonObject("mode");
        assertEquals("inferred", mode.get("status").getAsString());
        JsonObject table = rule(mode, "minecraft:test.mode");
        assertEquals("table", table.get("kind").getAsString());
        assertEquals("3", table.getAsJsonObject("table").get("c").getAsString());
        assertEquals("1 (int)", mode.getAsJsonObject("whenAbsent").get("minecraft:test.mode").getAsString(), "injected default");

        assertEquals("dropped", fields.getAsJsonObject("quiet").get("status").getAsString());
    }

    private static JsonObject rule(JsonObject field, String wire) {
        for (JsonElement rule : field.getAsJsonArray("rules")) {
            if (rule.getAsJsonObject().get("wire").getAsString().equals(wire)) {
                return rule.getAsJsonObject();
            }
        }
        throw new AssertionError("no rule for " + wire + " in " + field);
    }

    /**
     * What a server would send: minecraft:test {ticks: int(seconds * 20), mode: a=1 b=2 c=3 (default a)}; quiet is
     * server only; the boolean shorthand is {ticks: 0, mode: 1}; negative seconds are rejected (error logged, no component).
     */
    private static void fakeServer(AddonGenerator generator, Path capture) throws IOException {
        List<NbtMap> items = new ArrayList<>();
        List<String> log = new ArrayList<>();
        for (AddonGenerator.Variant v : generator.variants()) {
            JsonObject components = v.definition().getAsJsonObject("minecraft:item").getAsJsonObject("components");
            NbtMapBuilder wire = NbtMap.builder();
            NbtMapBuilder properties = NbtMap.builder().putInt("max_stack_size", 64);
            wire.putCompound("item_properties", properties.build());
            if (components.has("minecraft:max_stack_size")) {
                wire.putCompound("minecraft:max_stack_size", NbtMap.builder().putByte("value", (byte) 64).build());
            }
            JsonElement test = components.get("minecraft:test");
            if (test != null) {
                double seconds = test.isJsonObject() && test.getAsJsonObject().has("seconds") ? test.getAsJsonObject().get("seconds").getAsDouble() : 0;
                String mode = test.isJsonObject() && test.getAsJsonObject().has("mode") ? test.getAsJsonObject().get("mode").getAsString() : "a";
                if (seconds < 0 || (test.isJsonObject() && !test.getAsJsonObject().has("seconds"))) {
                    log.add("ERROR [Item]  Error Parsing Item '" + v.identifier() + "':");
                    log.add("ERROR [Item]  '" + v.identifier() + "': Failed to parse field ' -> components -> minecraft:test -> seconds: "
                        + (seconds < 0 ? "the input is outside of the accepted range [0, 100]" : "missing required field") + "'");
                } else {
                    wire.putCompound("minecraft:test", NbtMap.builder().putInt("ticks", (int) Math.round(seconds * 20))
                        .putInt("mode", mode.charAt(0) - 'a' + 1).build());
                }
            }
            items.add(NbtMap.builder().putString("name", v.identifier()).putBoolean("componentBased", true)
                .putCompound("data", NbtMap.builder().putCompound("components", wire.build()).build()).build());
        }
        writeGzip(capture.resolve("custom_item_registry.nbt"), NbtMap.builder().putList("items", NbtType.COMPOUND, items).build());
        writeGzip(capture.resolve("custom_block_properties.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND)).build());
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
