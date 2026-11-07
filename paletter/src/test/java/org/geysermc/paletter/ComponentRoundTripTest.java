package org.geysermc.paletter;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.component.BlockComponent;
import org.geysermc.paletter.component.ComponentType;
import org.geysermc.paletter.component.Confidence;
import org.geysermc.paletter.component.ItemComponent;
import org.geysermc.paletter.component.block.BlockComponentTypes;
import org.geysermc.paletter.component.block.BlockDefinition;
import org.geysermc.paletter.component.item.ItemComponentTypes;
import org.geysermc.paletter.component.item.ItemProperties;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.item.ItemComponents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main correctness proof of the generated component types: every component of every data driven item and block in
 * the bundled vanilla data and in the BDS captures ({@code codegen/captures}) must survive {@code fromNbt -> toNbt}
 * unchanged, value types included.
 */
class ComponentRoundTripTest {
    private static final Path CAPTURES = Path.of("../codegen/captures");

    /**
     * Wire NBT of one source: item component data ({@code {components}}) by item, block property data by block.
     */
    record WireData(String label, Map<String, NbtMap> items, Map<String, NbtMap> blocks) {
    }

    private static List<WireData> wireData() throws IOException {
        List<WireData> all = new ArrayList<>();
        PaletteDataSource source = PaletteDataSource.bundled();
        for (DataVersion version : source.versions()) {
            VanillaData data = source.load(version);
            Map<String, NbtMap> items = new TreeMap<>();
            for (VanillaItem item : data.items()) {
                if (item.componentBased() && item.componentData() != null && !item.componentData().isEmpty()) {
                    items.put(item.identifier(), item.componentData());
                }
            }
            Map<String, NbtMap> blocks = new TreeMap<>();
            for (BlockPropertyData block : data.dataDrivenBlocks()) {
                blocks.put(block.getName(), block.getProperties());
            }
            all.add(new WireData("vanilla " + version.name(), items, blocks));
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CAPTURES)) {
            files = walk.filter(file -> file.toString().endsWith(".nbt")).sorted().toList();
        }
        Map<String, NbtMap> items = new TreeMap<>();
        Map<String, NbtMap> blocks = new TreeMap<>();
        for (Path file : files) {
            NbtMap root;
            try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
                root = (NbtMap) input.readTag();
            }
            for (NbtMap item : root.getList("items", NbtType.COMPOUND, List.of())) {
                if (!item.getCompound("data").isEmpty()) {
                    items.put(item.getString("name"), item.getCompound("data"));
                }
            }
            for (NbtMap block : root.getList("blocks", NbtType.COMPOUND, List.of())) {
                blocks.put(block.getString("name"), block.getCompound("properties"));
            }
        }
        assertTrue(items.size() > 80 && blocks.size() > 60, "captures missing: " + items.size() + " items, " + blocks.size() + " blocks");
        all.add(new WireData("capture", items, blocks));
        return all;
    }

    @Test
    void everyWireItemComponentRoundTrips() throws IOException {
        Map<String, Integer> checked = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (WireData data : wireData()) {
            data.items().forEach((identifier, componentData) -> {
                componentData.getCompound("components").forEach((name, value) -> {
                    if (name.equals("item_tags")) {
                        return;
                    }
                    String where = data.label() + " " + identifier + " " + name;
                    NbtMap nbt = (NbtMap) value;
                    NbtMap roundTripped;
                    if (name.equals("item_properties")) {
                        roundTripped = ItemProperties.fromNbt(nbt).toNbt();
                    } else {
                        ComponentType<? extends ItemComponent> type = ItemComponentTypes.byName(name);
                        if (type == null) {
                            failures.add(where + ": no generated type");
                            return;
                        }
                        assertEquals(Confidence.HIGH, type.confidence(), where);
                        roundTripped = type.read(nbt).toNbt();
                    }
                    if (!nbt.equals(roundTripped)) {
                        failures.add(where + "\n  wire  " + nbt + "\n  typed " + roundTripped);
                    }
                    checked.merge(name, 1, Integer::sum);
                });
            });
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Item components round-tripped: " + checked);
        assertTrue(checked.size() > 40, checked.toString());
    }

    @Test
    void everyWireBlockComponentRoundTrips() throws IOException {
        Map<String, Integer> checked = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (WireData data : wireData()) {
            data.blocks().forEach((identifier, properties) -> {
                List<NbtMap> componentSets = new ArrayList<>();
                componentSets.add(properties.getCompound("components"));
                for (NbtMap permutation : properties.getList("permutations", NbtType.COMPOUND, List.of())) {
                    componentSets.add(permutation.getCompound("components"));
                }
                for (NbtMap components : componentSets) {
                    components.forEach((name, value) -> {
                        String where = data.label() + " " + identifier + " " + name;
                        ComponentType<? extends BlockComponent> type = BlockComponentTypes.byName(name);
                        if (type == null) {
                            failures.add(where + ": no generated type");
                            return;
                        }
                        assertEquals(Confidence.HIGH, type.confidence(), where);
                        NbtMap nbt = (NbtMap) value;
                        NbtMap roundTripped = type.read(nbt).toNbt();
                        if (!nbt.equals(roundTripped)) {
                            failures.add(where + "\n  wire  " + nbt + "\n  typed " + roundTripped);
                        }
                        checked.merge(name, 1, Integer::sum);
                    });
                }
            });
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Block components round-tripped: " + checked);
        assertTrue(checked.size() > 25, checked.toString());
    }

    @Test
    void everyWireBlockDefinitionRoundTrips() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (WireData data : wireData()) {
            for (Map.Entry<String, NbtMap> block : data.blocks().entrySet()) {
                Map<String, Object> definition = new TreeMap<>(block.getValue());
                definition.remove("components");
                definition.remove("permutations");
                NbtMap nbt = NbtMap.fromMap(definition);
                NbtMap roundTripped = BlockDefinition.fromNbt(nbt).toNbt();
                if (!nbt.equals(roundTripped)) {
                    failures.add(data.label() + " " + block.getKey() + "\n  wire  " + nbt + "\n  typed " + roundTripped);
                }
                checked++;
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Block definitions round-tripped: " + checked);
    }

    @Test
    void wireComponentDataSurvivesItemComponents() throws IOException {
        List<String> failures = new ArrayList<>();
        for (WireData data : wireData()) {
            data.items().forEach((identifier, componentData) -> {
                Map<String, Object> expected = new TreeMap<>(componentData.getCompound("components"));
                if (expected.get("item_tags") instanceof List<?> tags && tags.isEmpty()) {
                    // ItemComponents does not send an empty tag list
                    expected.remove("item_tags");
                }
                NbtMap wire = NbtMap.fromMap(expected);
                NbtMap rebuilt = ItemComponents.fromComponentData(wire)
                    .toComponentData(identifier, 0, null)
                    .getCompound("components");
                if (!wire.equals(rebuilt)) {
                    failures.add(data.label() + " " + identifier + "\n  wire    " + wire + "\n  rebuilt " + rebuilt);
                }
            });
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyGeneratedTypeIsRegisteredUnderItsName() {
        for (ComponentType<? extends ItemComponent> type : ItemComponentTypes.ALL) {
            assertEquals(ComponentType.Kind.ITEM, type.kind());
            assertNotNull(ItemComponentTypes.byName(type.name()));
        }
        for (ComponentType<? extends BlockComponent> type : BlockComponentTypes.ALL) {
            assertEquals(ComponentType.Kind.BLOCK, type.kind());
            assertNotNull(BlockComponentTypes.byName(type.name()));
        }
    }
}
