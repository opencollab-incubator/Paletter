package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.internal.creative.RawLayout;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.PropertyStore;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.data.VersionFallbacks;
import org.geysermc.paletter.property.PropertyKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The importer writes format 2 version files and keeps {@code properties.nbt} up to date across versions. */
class ImporterPropertiesTest {
    private static final DataVersion A = new DataVersion("1.0", 100);
    private static final DataVersion B = new DataVersion("1.1", 200);

    @TempDir
    Path directory;

    private static NbtMap sword(int stackSize, String texture) {
        return NbtMap.builder()
            .putCompound("components", NbtMap.builder()
                .putCompound("minecraft:max_stack_size", NbtMap.builder().putInt("max_stack_size", stackSize).build())
                .putCompound("minecraft:icon", NbtMap.builder().putString("texture", texture).build()).build())
            .putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", stackSize).build())
            .putList("tags", NbtType.STRING, "minecraft:is_sword")
            .build();
    }

    private static VanillaData version(DataVersion version, int stackSize) {
        List<VanillaItem> items = List.of(
            new VanillaItem("test:apple", 1, 0, false, NbtMap.EMPTY),
            new VanillaItem("test:sword", 2, 2, true, sword(stackSize, "sword")));
        List<BlockPropertyData> blocks = List.of(new BlockPropertyData("test:lamp", NbtMap.builder()
            .putString("menu_category", "construction").putCompound("components", NbtMap.builder()
                .putCompound("minecraft:light_emission", NbtMap.builder().putInt("emission", 7).build()).build()).build()));
        return new VanillaData(version, List.of(BlockStates.of("test:lamp", Map.of("lit", (byte) 0))), items, blocks,
            RawLayout.of(Map.of()), Map.of(), VersionFallbacks.NONE, Map.of());
    }

    private void importVersion(VanillaData data, Path output) throws Exception {
        Path source = Files.createDirectories(directory.resolve("source-" + data.version().name()));
        DiffSourceTest.write(data, source, true);
        new Importer(output).run(source, data.version().name(), data.version().protocolVersion(), null, null);
    }

    @Test
    void importWritesFormat2AndTheStore() throws Exception {
        Path output = directory.resolve("out");
        VanillaData first = version(A, 64);
        VanillaData second = version(B, 16);
        importVersion(first, output);
        importVersion(second, output);

        // the version files carry no properties
        try (InputStream stream = Files.newInputStream(output.resolve("1.1.nbt"))) {
            VanillaData raw = PaletteDataCodec.read(stream);
            assertTrue(raw.dataDrivenBlocks().isEmpty());
            raw.items().forEach(item -> assertNull(item.componentData(), item.identifier()));
        }

        // the store covers both versions, and the second import did not lose the first version's properties
        PropertyStore store = BundledFiles.readStore(output);
        assertEquals(List.of("1.0", "1.1"), store.versions());
        assertEquals(64, store.item("test:sword", A).component("minecraft:max_stack_size").getInt("max_stack_size"));
        assertEquals(16, store.item("test:sword", B).component("minecraft:max_stack_size").getInt("max_stack_size"));
        assertEquals("sword", store.item("test:sword", B).component("minecraft:icon").getString("texture"));
        assertEquals("construction", store.block("test:lamp", B).get(PropertyKind.WIRE, "menu_category").value());

        // reading puts the data together again
        Map<String, VanillaData> all = BundledFiles.readAll(output);
        assertEquals(first.items(), all.get("1.0").items());
        assertEquals(second.items(), all.get("1.1").items());
        assertEquals(second.dataDrivenBlocks(), all.get("1.1").dataDrivenBlocks());

        // the icon, the lamp and the tags did not change: one range for both versions
        // sword: icon, stack size (2 ranges), item_properties (2 ranges), tags; apple: marker; lamp: category, light emission
        assertEquals(1 + 1 + 2 + 2 + 1 + 1 + 1, store.rangeCount());
    }

    @Test
    void reimportingAVersionReplacesItsProperties() throws Exception {
        Path output = directory.resolve("out");
        importVersion(version(A, 64), output);
        importVersion(version(B, 16), output);
        importVersion(version(A, 32), output);
        PropertyStore store = BundledFiles.readStore(output);
        assertEquals(32, store.item("test:sword", A).component("minecraft:max_stack_size").getInt("max_stack_size"));
        assertEquals(16, store.item("test:sword", B).component("minecraft:max_stack_size").getInt("max_stack_size"));
    }

    @Test
    void rewriteConvertsFormat1Files() throws Exception {
        Path output = directory.resolve("old");
        Files.createDirectories(output);
        VanillaData first = version(A, 64);
        VanillaData second = version(B, 16);
        for (VanillaData data : List.of(first, second)) {
            try (var stream = Files.newOutputStream(output.resolve(data.version().name() + ".nbt"))) {
                PaletteDataCodec.write(data, stream, PaletteDataCodec.FORMAT_1);
            }
        }
        BundledFiles.rewrite(output);
        Map<String, VanillaData> all = BundledFiles.readAll(output);
        assertEquals(first.items(), all.get("1.0").items());
        assertEquals(second.items(), all.get("1.1").items());
        assertEquals(second.dataDrivenBlocks(), all.get("1.1").dataDrivenBlocks());
        try (InputStream stream = Files.newInputStream(output.resolve("1.0.nbt"))) {
            assertTrue(PaletteDataCodec.read(stream).items().stream().allMatch(item -> item.componentData() == null));
        }
        // rewriting again changes nothing
        byte[] store = Files.readAllBytes(output.resolve("properties.nbt"));
        byte[] file = Files.readAllBytes(output.resolve("1.1.nbt"));
        BundledFiles.rewrite(output);
        assertEquals(List.of(store.length, file.length), List.of(Files.readAllBytes(output.resolve("properties.nbt")).length,
            Files.readAllBytes(output.resolve("1.1.nbt")).length));
        assertTrue(java.util.Arrays.equals(store, Files.readAllBytes(output.resolve("properties.nbt"))));
        assertTrue(java.util.Arrays.equals(file, Files.readAllBytes(output.resolve("1.1.nbt"))));
    }
}
