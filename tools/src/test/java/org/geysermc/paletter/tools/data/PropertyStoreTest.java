package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.internal.creative.RawLayout;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.PropertyStore;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.data.VersionFallbacks;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.property.Property;
import org.geysermc.paletter.property.PropertyKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyStoreTest {
    private static final DataVersion V1 = new DataVersion("1.0", 100);
    private static final DataVersion V2 = new DataVersion("1.1", 200);
    private static final DataVersion V3 = new DataVersion("1.2", 300);

    private static NbtMap stack(int size) {
        return NbtMap.builder().putInt("max_stack_size", size).build();
    }

    private static NbtMap icon(String texture) {
        return NbtMap.builder().putString("texture", texture).build();
    }

    private static NbtMap item(String texture, int stackSize, String wire) {
        NbtMapBuilder components = NbtMap.builder();
        components.putCompound("minecraft:icon", icon(texture));
        components.putCompound("minecraft:max_stack_size", stack(stackSize));
        return NbtMap.builder().putCompound("components", components.build()).putString("wire_key", wire)
            .putList("tags", NbtType.STRING, "a", "b").build();
    }

    private static VanillaData data(DataVersion version, List<VanillaItem> items, List<BlockPropertyData> blocks) {
        return new VanillaData(version, List.of(BlockStates.of("test:b", Map.of())), items, blocks, RawLayout.of(Map.of()),
            Map.of(), VersionFallbacks.NONE, Map.of());
    }

    private static VanillaItem vanillaItem(String id, NbtMap components) {
        return new VanillaItem(id, 1, 0, true, components);
    }

    private static byte[] bytes(PropertyStore store) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.write(bytes);
        return bytes.toByteArray();
    }

    private static PropertyStore roundTrip(PropertyStore store) throws IOException {
        return PropertyStore.read(new ByteArrayInputStream(bytes(store)));
    }

    @Test
    void equalValuesAreOneRange() {
        PropertyStore store = PropertyStore.build(List.of(
            data(V1, List.of(vanillaItem("test:a", item("x", 64, "w"))), List.of()),
            data(V2, List.of(vanillaItem("test:a", item("x", 64, "w"))), List.of()),
            data(V3, List.of(vanillaItem("test:a", item("x", 64, "w"))), List.of())));
        // component:minecraft:icon, component:minecraft:max_stack_size, wire:wire_key and wire:tags: one range each
        assertEquals(4, store.rangeCount());
        assertEquals(List.of("1.0", "1.1", "1.2"), store.versions());
        assertEquals(item("x", 64, "w"),
            store.attach(data(V3, List.of(vanillaItem("test:a", null)), List.of())).items().getFirst().componentData());
    }

    @Test
    void changedValueSplitsTheRange() {
        PropertyStore store = PropertyStore.build(List.of(
            data(V1, List.of(vanillaItem("test:a", item("x", 64, "w"))), List.of()),
            data(V2, List.of(vanillaItem("test:a", item("x", 16, "w"))), List.of()),
            data(V3, List.of(vanillaItem("test:a", item("x", 16, "w"))), List.of())));
        // stack size: [1.0], [1.1-1.2]; the other three are one range each
        assertEquals(5, store.rangeCount());
        assertEquals(stack(64), store.item("test:a", V1).component("minecraft:max_stack_size"));
        assertEquals(stack(16), store.item("test:a", V2).component("minecraft:max_stack_size"));
        assertEquals(stack(16), store.item("test:a", V3).component("minecraft:max_stack_size"));
    }

    @Test
    void propertyAbsentInAMiddleVersion() {
        NbtMap with = item("x", 64, "w");
        NbtMap without = NbtMap.builder().putCompound("components", NbtMap.builder().putCompound("minecraft:icon", icon("x")).build())
            .putString("wire_key", "w").putList("tags", NbtType.STRING, "a", "b").build();
        PropertyStore store = PropertyStore.build(List.of(
            data(V1, List.of(vanillaItem("test:a", with)), List.of()),
            data(V2, List.of(vanillaItem("test:a", without)), List.of()),
            data(V3, List.of(vanillaItem("test:a", with)), List.of())));
        // the stack size is two ranges with a gap, even though both values are equal
        assertEquals(stack(64), store.item("test:a", V1).component("minecraft:max_stack_size"));
        assertNull(store.item("test:a", V2).component("minecraft:max_stack_size"));
        assertEquals(stack(64), store.item("test:a", V3).component("minecraft:max_stack_size"));
        assertEquals(5, store.rangeCount());
        assertEquals(without, store.attach(data(V2, List.of(vanillaItem("test:a", null)), List.of())).items().getFirst().componentData());
    }

    @Test
    void propertiesAreSplitByKind() {
        PropertyStore store = PropertyStore.build(List.of(data(V1, List.of(vanillaItem("test:a", item("x", 64, "w"))), List.of())));
        Properties properties = store.item("test:a", V1);
        assertEquals(2, properties.of(PropertyKind.COMPONENT).size());
        assertEquals(2, properties.of(PropertyKind.WIRE).size());
        Property wire = properties.get(PropertyKind.WIRE, "wire_key");
        assertNotNull(wire);
        assertEquals("w", wire.value());
        assertEquals("wire:wire_key", wire.fullName());
        assertTrue(properties.of(PropertyKind.BEHAVIOUR).isEmpty());
        assertEquals(Properties.EMPTY, store.item("test:missing", V1));
        assertEquals(Properties.EMPTY, store.item("test:a", new DataVersion("9.9", 999)));
    }

    @Test
    void emptyAndMissingComponentsSurvive() throws Exception {
        NbtMap hollow = NbtMap.builder().putCompound("components", NbtMap.EMPTY).build();
        PropertyStore store = roundTrip(PropertyStore.build(List.of(data(V1,
            List.of(vanillaItem("test:empty", NbtMap.EMPTY), vanillaItem("test:none", null), vanillaItem("test:hollow", hollow)), List.of()))));
        VanillaData attached = store.attach(data(V1, List.of(vanillaItem("test:empty", null), vanillaItem("test:none", null),
            vanillaItem("test:hollow", null)), List.of()));
        assertEquals(NbtMap.EMPTY, attached.items().get(0).componentData());
        assertNull(attached.items().get(1).componentData());
        assertEquals(hollow, attached.items().get(2).componentData());
        // an empty compound is not a property anybody can see
        assertTrue(store.item("test:empty", V1).isEmpty());
    }

    @Test
    void blocksAreStoredLikeItems() throws Exception {
        NbtMap lamp1 = NbtMap.builder().putString("menu_category", "a").putInt("molangVersion", 1)
            .putCompound("components", NbtMap.builder().putCompound("minecraft:light_emission", NbtMap.builder().putInt("emission", 5).build()).build()).build();
        NbtMap lamp2 = lamp1.toBuilder().putCompound("components", NbtMap.builder()
            .putCompound("minecraft:light_emission", NbtMap.builder().putInt("emission", 9).build()).build()).build();
        PropertyStore store = roundTrip(PropertyStore.build(List.of(
            data(V1, List.of(), List.of(new BlockPropertyData("test:lamp", lamp1))),
            data(V2, List.of(), List.of(new BlockPropertyData("test:lamp", lamp2), new BlockPropertyData("test:new", NbtMap.EMPTY))))));
        assertEquals(1, store.block("test:lamp", V1).of(PropertyKind.COMPONENT).size());
        assertEquals(2, store.block("test:lamp", V1).of(PropertyKind.WIRE).size());
        assertEquals(List.of(new BlockPropertyData("test:lamp", lamp1)), store.attach(data(V1, List.of(), List.of())).dataDrivenBlocks());
        assertEquals(List.of(new BlockPropertyData("test:lamp", lamp2), new BlockPropertyData("test:new", NbtMap.EMPTY)),
            store.attach(data(V2, List.of(), List.of())).dataDrivenBlocks());
        // data that has its own is left alone
        VanillaData own = data(V1, List.of(), List.of(new BlockPropertyData("test:own", NbtMap.EMPTY)));
        assertEquals(own.dataDrivenBlocks(), store.attach(own).dataDrivenBlocks());
        // versions the store does not cover are not touched
        VanillaData other = data(new DataVersion("7.0", 700), List.of(vanillaItem("test:a", null)), List.of());
        assertEquals(other, store.attach(other));
    }

    @Test
    void behaviourPropertiesComeInThroughTheBuilder() throws Exception {
        PropertyStore.Builder builder = new PropertyStore.Builder(List.of(V2, V1));
        builder.put(PropertyStore.Owner.BLOCK, "test:b", PropertyKind.BEHAVIOUR, "friction", V1, 0.6f);
        builder.put(PropertyStore.Owner.BLOCK, "test:b", PropertyKind.BEHAVIOUR, "friction", V2, 0.6f);
        PropertyStore store = roundTrip(builder.build());
        assertEquals(1, store.rangeCount());
        Property friction = store.block("test:b", V2).get(PropertyKind.BEHAVIOUR, "friction");
        assertNotNull(friction);
        assertEquals(0.6f, friction.value());
        // behaviour is not network data: it does not end up in the data driven blocks
        assertTrue(store.attach(data(V1, List.of(), List.of())).dataDrivenBlocks().stream()
            .allMatch(block -> block.getProperties().isEmpty()));
    }

    @Test
    void sameBytesEveryTime() throws Exception {
        List<VanillaData> versions = new ArrayList<>();
        for (DataVersion version : List.of(V1, V2, V3)) {
            List<VanillaItem> items = new ArrayList<>();
            for (String id : List.of("test:z", "test:a", "test:m")) {
                items.add(vanillaItem(id, item(id, version == V1 ? 64 : 16, "w")));
            }
            versions.add(data(version, items, List.of()));
        }
        byte[] first = bytes(PropertyStore.build(versions));
        assertArrayEquals(first, bytes(PropertyStore.build(versions.reversed())));
        assertArrayEquals(first, bytes(PropertyStore.read(new ByteArrayInputStream(first))));
    }

    @Test
    void format1RoundTrips() throws Exception {
        // the bundled versions as old format 1 files had them: components in the version file
        PaletteDataSource source = PaletteDataSource.bundled();
        List<VanillaData> versions = new ArrayList<>();
        for (DataVersion version : source.versions()) {
            ByteArrayOutputStream format1 = new ByteArrayOutputStream();
            PaletteDataCodec.write(source.load(version), format1, PaletteDataCodec.FORMAT_1);
            versions.add(PaletteDataCodec.read(new ByteArrayInputStream(format1.toByteArray())));
        }
        PropertyStore store = roundTrip(PropertyStore.build(versions));
        for (VanillaData original : versions) {
            ByteArrayOutputStream format2 = new ByteArrayOutputStream();
            PaletteDataCodec.write(original, format2);
            VanillaData stripped = PaletteDataCodec.read(new ByteArrayInputStream(format2.toByteArray()));
            assertTrue(stripped.items().stream().allMatch(item -> item.componentData() == null), "format 2 has no components");
            assertTrue(stripped.dataDrivenBlocks().isEmpty());
            VanillaData again = store.attach(stripped);
            String name = original.version().name();
            assertEquals(original.items(), again.items(), name);
            assertEquals(new HashSet<>(original.dataDrivenBlocks()), new HashSet<>(again.dataDrivenBlocks()), name);
        }
        assertTrue(store.rangeCount() > 0);
    }

    @Test
    void committedFilesAreWhatTheWriterProduces(@TempDir Path directory) throws Exception {
        PaletteDataSource source = PaletteDataSource.bundled();
        for (DataVersion version : source.versions()) {
            assertTrue(source.properties().versions().contains(version.name()), version.name());
        }
        Path resources = Path.of(System.getProperty("paletter.root", "..")).resolve("paletter/src/main/resources/paletter");
        assertTrue(Files.isDirectory(resources), resources.toString());
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.toList()) {
                Files.copy(file, directory.resolve(file.getFileName()));
            }
        }
        BundledFiles.rewrite(directory);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.toList()) {
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(directory.resolve(file.getFileName())), file.getFileName().toString());
            }
        }
    }
}
