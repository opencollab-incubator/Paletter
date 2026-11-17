package org.geysermc.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.geysermc.paletter.tools.model.Model;
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

/** {@link WireShapes#uncoveredByBuild}: the wire paths of every build's add-on capture that no rule covers. */
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

    /** A build's add-on capture with one item of the covered component with the given members, and maybe an undocumented component. */
    private static void addon(Path bdsData, String build, NbtMap covered, boolean mystery) throws IOException {
        Path directory = Files.createDirectories(bdsData.resolve(build).resolve("addon"));
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
        addon(bdsData, "1.26.36.1", NbtMap.builder().putInt("x", 1).putInt("extra", 2).build(), false);
        addon(bdsData, "1.26.9.1", NbtMap.builder().putInt("x", 1).build(), true);
        addon(bdsData, "1.26.45.1", NbtMap.builder().putInt("x", 1).build(), false);
        // a build without an add-on capture has nothing to check
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

    @Test
    void withoutADataDirectoryThereIsNothingToCheck(@TempDir Path root) throws IOException {
        Path components = root.resolve("components.json");
        Files.writeString(components, COMPONENTS);
        assertTrue(WireShapes.uncoveredByBuild(root.resolve("missing"), MAPPINGS, Model.read(components)).isEmpty());
    }
}
