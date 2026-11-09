package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpecDiff} on two synthetic captures: a value that changes with the same keys and types, a leaf that disappears. */
class SpecDiffTest {
    /** Item {@code paletter:a_N}: component {@code minecraft:box} with {@code minX} and, optionally, {@code extra}. */
    private static void capture(Path dir, double minX, boolean extra, boolean secondRejected) throws IOException {
        Files.createDirectories(dir);
        List<NbtMap> items = new ArrayList<>();
        JsonArray variants = new JsonArray();
        List<String> log = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String id = "paletter:a_" + i;
            JsonObject variant = new JsonObject();
            variant.addProperty("identifier", id);
            variant.addProperty("kind", "item");
            variant.addProperty("file", "items/a_" + i + ".json");
            variant.addProperty("component", "item:minecraft:box");
            variant.addProperty("role", "field");
            variant.addProperty("path", "origin");
            variant.addProperty("label", "number:" + i);
            variant.addProperty("value", i);
            variant.add("json", new JsonObject());
            variants.add(variant);
            org.cloudburstmc.nbt.NbtMapBuilder box = NbtMap.builder().putFloat("minX", (float) (minX * i));
            if (extra && i == 1) {
                box.putInt("extra", 1);
            }
            items.add(NbtMap.builder().putString("name", id).putBoolean("componentBased", true)
                .putCompound("data", NbtMap.builder().putCompound("components", NbtMap.builder().putCompound("minecraft:box", box.build()).build()).build())
                .build());
            if (i == 2 && secondRejected) {
                log.add("ERROR [Item]  Error Parsing Item '" + id + "':");
            }
        }
        writeGzip(dir.resolve("custom_item_registry.nbt"), NbtMap.builder().putList("items", NbtType.COMPOUND, items).build());
        writeGzip(dir.resolve("custom_block_properties.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND)).build());
        Files.writeString(dir.resolve("content-log.txt"), String.join("\n", log) + "\n");
        Files.writeString(dir.resolve("variants.json"), variants.toString());
        JsonObject meta = new JsonObject();
        meta.addProperty("bdsVersion", dir.getParent().getFileName().toString());
        meta.addProperty("protocol", 1);
        meta.add("experiments", new JsonArray());
        Files.writeString(dir.resolve("capture.json"), meta.toString());
    }

    private static void writeGzip(Path file, NbtMap map) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file)); NBTOutputStream nbt = NbtUtils.createWriter(out)) {
            nbt.writeTag(map);
        }
    }

    @Test
    void aChangedValueIsFoundWithItsOldAndNewValue(@TempDir Path root) throws IOException {
        capture(root.resolve("1.0/spec"), 1, false, false);
        capture(root.resolve("2.0/spec"), -1, false, false);
        List<SpecDiff.Change> changes = SpecDiff.compare(root.resolve("1.0/spec"), root.resolve("2.0/spec"));
        assertEquals(2, changes.size(), changes.toString());
        SpecDiff.Change first = changes.getFirst();
        assertEquals("item:minecraft:box", first.component());
        assertEquals("paletter:a_1", first.variant());
        assertEquals("minecraft:box.minX", first.path());
        assertEquals("1.0 (float)", first.before());
        assertEquals("-1.0 (float)", first.after());
        assertTrue(first.valueChange());
        assertEquals("paletter:a_1 (number:1) minecraft:box.minX: 1.0 (float) -> -1.0 (float)", first.toString());
    }

    @Test
    void aLeafThatIsNoLongerSentIsAChangeButNoValueChange(@TempDir Path root) throws IOException {
        capture(root.resolve("1.0/spec"), 1, true, false);
        capture(root.resolve("2.0/spec"), 1, false, false);
        List<SpecDiff.Change> changes = SpecDiff.compare(root.resolve("1.0/spec"), root.resolve("2.0/spec"));
        assertEquals(1, changes.size(), changes.toString());
        assertEquals("minecraft:box.extra", changes.getFirst().path());
        assertTrue(!changes.getFirst().valueChange());
        assertEquals("not sent", changes.getFirst().toString().substring(changes.getFirst().toString().indexOf("-> ") + 3));
    }

    @Test
    void identicalCapturesAndRejectedVariantsGiveNoChanges(@TempDir Path root) throws IOException {
        capture(root.resolve("1.0/spec"), 1, false, false);
        capture(root.resolve("2.0/spec"), 1, false, false);
        assertTrue(SpecDiff.compare(root.resolve("1.0/spec"), root.resolve("2.0/spec")).isEmpty());
        // a variant the newer build rejects has nothing to compare
        capture(root.resolve("3.0/spec"), -1, false, true);
        List<SpecDiff.Change> changes = SpecDiff.compare(root.resolve("1.0/spec"), root.resolve("3.0/spec"));
        assertEquals(List.of("paletter:a_1"), changes.stream().map(SpecDiff.Change::variant).distinct().toList());
    }
}
