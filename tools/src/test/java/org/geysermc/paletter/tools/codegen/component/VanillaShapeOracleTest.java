package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.component.BlockCompiler;
import org.geysermc.paletter.internal.component.ItemCompiler;
import org.geysermc.paletter.internal.component.WireContext;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.tools.data.WireShapes;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second oracle per bundled version, next to the conformance suite: what that version's own vanilla data carries
 * (the data driven items and blocks, captured from BDS) against what Paletter compiles for the same components. For every
 * component both have, a wire path both have must have the same NBT type. This needs no BDS run and compares against the
 * real data of that version, not against the spec variants.
 * <p>
 * The compiled side is every variant of the version's spec capture that BDS accepted and the API can express (compiled the
 * way the conformance suite does). Disagreements that are no bug are listed per protocol in
 * {@code data/model/vanilla-shapes-known-<protocol>.txt}; the test fails on any change either way.
 */
class VanillaShapeOracleTest {
    @TestFactory
    Stream<DynamicTest> compiledShapesHaveTheTypesOfTheVersionsVanillaData() throws IOException {
        return ComponentConformanceTest.captures().stream().map(capture -> DynamicTest.dynamicTest(
            capture.getParent().getFileName() + " (protocol " + ComponentConformanceTest.protocolOf(capture) + ")", () -> check(capture)));
    }

    private static void check(Path capture) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject();
        int protocol = meta.get("protocol").getAsInt();
        Paletter paletter = Paletter.bundled();
        DataVersion version = paletter.version(protocol);
        VanillaData data = paletter.vanillaData(version);

        WireShapes vanilla = WireShapes.ofData(data);
        WireShapes compiled = new WireShapes();
        AddonGenerator generator = new AddonGenerator(Model.read(ComponentConformanceTest.MODEL.resolve("components.json")),
            Fixtures.read(ComponentConformanceTest.MODEL), meta.get("formatVersion").getAsString());
        Set<String> errors = ComponentConformanceTest.errorVariants(capture.resolve("content-log.txt"));
        Set<String> sent = new TreeSet<>();
        for (NbtMap item : ComponentConformanceTest.read(capture.resolve("custom_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
            sent.add(item.getString("name"));
        }
        for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            sent.add(block.getString("name"));
        }
        int encoded = 0;
        for (JsonElement e : JsonParser.parseString(Files.readString(capture.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject entry = e.getAsJsonObject();
            String identifier = entry.get("identifier").getAsString();
            if (errors.contains(identifier) || !sent.contains(identifier)) {
                continue;
            }
            String component = entry.get("component").getAsString();
            JsonObject definition = generator.definitionOf(component, identifier, entry.get("json"));
            WireContext ctx = WireContext.of(data, new PaletteReport(), "oracle");
            try {
                if (entry.get("kind").getAsString().equals("block")) {
                    CustomBlock block = ApiBinder.block(definition);
                    compiled.addBlock(BlockCompiler.propertyData(block, 10000, ctx));
                } else {
                    CustomItem item = ApiBinder.item(definition);
                    compiled.addItem(ItemCompiler.componentData(item.identifier(), 0, item.components(), item.menuCategory(), item.creative(), ctx));
                }
                encoded++;
            } catch (ApiBinder.Unbindable ignored) {
                // not expressible through the API: the conformance suite lists it
            }
        }
        assertTrue(encoded > 1000, "only " + encoded + " variants compiled");

        Map<String, String> disagreements = new TreeMap<>();
        int compared = 0;
        for (Map.Entry<String, Map<String, String>> component : vanilla.components().entrySet()) {
            Map<String, String> ours = compiled.components().get(component.getKey());
            if (ours == null) {
                continue;
            }
            for (Map.Entry<String, String> path : component.getValue().entrySet()) {
                String type = ours.get(path.getKey());
                if (type == null) {
                    continue;
                }
                compared++;
                if (!type.equals(path.getValue()) && !type.equals("any") && !path.getValue().equals("any")) {
                    disagreements.put(component.getKey() + " " + (path.getKey().isEmpty() ? "<root>" : path.getKey()),
                        "vanilla " + path.getValue() + ", Paletter " + type);
                }
            }
        }
        assertTrue(compared > 100, "only " + compared + " paths compared: the oracle checks nothing");

        Path known = ComponentConformanceTest.MODEL.resolve("vanilla-shapes-known-" + protocol + ".txt");
        Map<String, String> expected = new TreeMap<>();
        if (Files.exists(known)) {
            for (String line : Files.readAllLines(known, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    int bar = line.indexOf(" | ");
                    expected.put(line.substring(0, bar), line.substring(bar + 3));
                }
            }
        }
        if (Boolean.getBoolean("paletter.updateGolden")) {
            List<String> lines = new ArrayList<>(List.of("# Wire paths of protocol " + protocol + " whose NBT type in that version's vanilla data differs from what Paletter compiles.",
                "# Written by VanillaShapeOracleTest (-PupdateGolden=true); a path not listed must agree. Format: <component> <path> | <difference>"));
            disagreements.forEach((k, v) -> lines.add(k + " | " + v));
            Files.writeString(known, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertEquals(expected, disagreements, "vanilla data of " + version + " against the compiled shapes");
    }
}
