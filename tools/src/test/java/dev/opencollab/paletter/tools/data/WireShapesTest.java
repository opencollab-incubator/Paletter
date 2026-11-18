package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WireShapes#uncoveredByBuild}: the wire paths of every build's spec capture that no rule covers. */
class WireShapesTest {
    private static final String COMPONENTS = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "item:minecraft:covered", "kind": "item", "category": "component", "name": "minecraft:covered", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [{"path": "x", "types": [{"type": "integer"}], "required": false, "enumValues": [], "examples": [], "doc": ""}]}
        ]}""";
    private static final JsonObject MAPPINGS = JsonParser.parseString("""
        {"components": {"item:minecraft:covered": {"status": "sent", "wire": ["minecraft:covered.x"],
          "fields": {"x": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "int", "tags": [], "wire": "minecraft:covered.x"}]}}}}}""")
        .getAsJsonObject();

    /** A build's spec capture with one item of the covered component with the given members, and maybe an undocumented component. */
    private static void spec(Path bdsData, String build, NbtMap covered, boolean mystery) throws IOException {
        Path directory = Files.createDirectories(bdsData.resolve(build).resolve("spec"));
        NbtMapBuilder components = NbtMap.builder().putCompound("minecraft:covered", covered);
        if (mystery) {
            components.putCompound("minecraft:mystery", NbtMap.builder().putInt("z", 1).build());
        }
        NbtMap item = NbtMap.builder().putString("name", "paletter:a").putBoolean("componentBased", true)
            .putCompound("data", NbtMap.builder().putCompound("components", components.build()).build()).build();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(directory.resolve("custom_item_registry.nbt")));
             NBTOutputStream nbt = NbtUtils.createWriter(out)) {
            nbt.writeTag(NbtMap.builder().putList("items", NbtType.COMPOUND, List.of(item)).build());
        }
    }

    @Test
    void everyBuildIsCheckedOldestFirstAndOnlyBuildsWithGapsAreListed(@TempDir Path root) throws IOException {
        Path bdsData = Files.createDirectories(root.resolve("bds"));
        // 1.26.9.1 is older than 1.26.36.1 although it sorts after it as text
        spec(bdsData, "1.26.36.1", NbtMap.builder().putInt("x", 1).putInt("extra", 2).build(), false);
        spec(bdsData, "1.26.9.1", NbtMap.builder().putInt("x", 1).build(), true);
        spec(bdsData, "1.26.45.1", NbtMap.builder().putInt("x", 1).build(), false);
        // a build without a spec capture has nothing to check
        Files.createDirectories(bdsData.resolve("1.26.50.1/spec"));
        Path components = root.resolve("components.json");
        Files.writeString(components, COMPONENTS);

        Map<String, Map<String, List<String>>> uncovered = WireShapes.uncoveredByBuild(bdsData, MAPPINGS, Model.read(components));
        assertEquals(List.of("1.26.9.1", "1.26.36.1"), List.copyOf(uncovered.keySet()), "1.26.45.1 has no gap, 1.26.50.1 no capture");
        assertEquals(Map.of("the creator docs do not describe the component, so the model has no fields for it",
            List.of("item:minecraft:mystery", "item:minecraft:mystery z")), uncovered.get("1.26.9.1"));
        // a path of a documented component that no rule covers is for a human to look at
        assertEquals(Map.of(WireShapes.UNCLASSIFIED, List.of("item:minecraft:covered extra")), uncovered.get("1.26.36.1"));
    }

    /** {@code check-mappings --strict} fails on the paths of an undocumented component too, not only on unclassified ones. */
    @Test
    void strictFlagsTheWirePathsOfUndocumentedComponents(@TempDir Path root) throws IOException {
        Path bdsData = Files.createDirectories(root.resolve("bds"));
        spec(bdsData, "1.26.9.1", NbtMap.builder().putInt("x", 1).build(), true);
        spec(bdsData, "1.26.36.1", NbtMap.builder().putInt("x", 1).putInt("extra", 2).build(), false);
        Path components = root.resolve("components.json");
        Files.writeString(components, COMPONENTS);
        Map<String, Map<String, List<String>>> uncovered = WireShapes.uncoveredByBuild(bdsData, MAPPINGS, Model.read(components));

        assertEquals(List.of(
            "wire path item:minecraft:mystery of 1.26.9.1 belongs to a component the creator docs do not describe: document it or map it by hand",
            "wire path item:minecraft:mystery z of 1.26.9.1 belongs to a component the creator docs do not describe: document it or map it by hand"),
            WireShapes.unexplained("1.26.9.1", uncovered.get("1.26.9.1")));
        assertEquals(List.of("wire path item:minecraft:covered extra of 1.26.36.1 is not in mappings.json and no reason of WireShapes.classify fits"),
            WireShapes.unexplained("1.26.36.1", uncovered.get("1.26.36.1")));
        // a reason with a standing explanation is no failure
        assertEquals(List.of(), WireShapes.unexplained("b", Map.of("block definition data around the components", List.of("block:block_definition x"))));
    }

    /**
     * The rehearsal of BDS 1.26.63.1 and 1.26.70.24: the schema's block components {@code minecraft:leashable} and
     * {@code minecraft:sound} (block format 1.26.60) were captured before any inference mapped them.
     */
    @Test
    void aDocumentedComponentWithoutMappingsIsNotInferredYet(@TempDir Path root) throws IOException {
        Path components = root.resolve("components.json");
        Files.writeString(components, COMPONENTS.replace("item:minecraft:covered", "block:minecraft:leashable")
            .replace("\"kind\": \"item\"", "\"kind\": \"block\"").replace("\"minecraft:covered\"", "\"minecraft:leashable\""));
        Map<String, List<String>> classified = WireShapes.classify(List.of("block:minecraft:leashable", "block:minecraft:leashable offset[]"),
            MAPPINGS, Model.read(components));
        assertEquals(Map.of(WireShapes.NOT_INFERRED_YET, List.of("block:minecraft:leashable", "block:minecraft:leashable offset[]")), classified);
        // strict fails on them: after the inference, a component without mappings was never mapped
        assertEquals(List.of("wire path block:minecraft:leashable of 1.2 is of a documented component the inference never mapped: run `infer` and commit mappings.json",
            "wire path block:minecraft:leashable offset[] of 1.2 is of a documented component the inference never mapped: run `infer` and commit mappings.json"),
            WireShapes.unexplained("1.2", classified));
    }

    @Test
    void withoutADataDirectoryThereIsNothingToCheck(@TempDir Path root) throws IOException {
        Path components = root.resolve("components.json");
        Files.writeString(components, COMPONENTS);
        assertTrue(WireShapes.uncoveredByBuild(root.resolve("missing"), MAPPINGS, Model.read(components)).isEmpty());
    }
}
