package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.component.BlockComponent;
import org.geysermc.paletter.component.ComponentType;
import org.geysermc.paletter.component.Confidence;
import org.geysermc.paletter.component.ItemComponent;
import org.geysermc.paletter.component.block.BlockComponentTypes;
import org.geysermc.paletter.component.item.ItemComponentTypes;
import org.geysermc.paletter.component.item.ItemProperties;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.item.ItemComponents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main correctness proof of the generated component types: every component of every vanilla data driven item and
 * block in the bundled data must survive {@code fromNbt -> toNbt} unchanged, value types included.
 */
class ComponentRoundTripTest {
    private static List<VanillaData> bundled() throws IOException {
        PaletteDataSource source = PaletteDataSource.bundled();
        List<VanillaData> data = new ArrayList<>();
        for (DataVersion version : source.versions()) {
            data.add(source.load(version));
        }
        return data;
    }

    @Test
    void everyVanillaItemComponentRoundTrips() throws IOException {
        Map<String, Integer> checked = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (VanillaData data : bundled()) {
            for (VanillaItem item : data.items()) {
                if (!item.componentBased() || item.componentData() == null) {
                    continue;
                }
                NbtMap components = item.componentData().getCompound("components");
                components.forEach((name, value) -> {
                    if (name.equals("item_tags")) {
                        return;
                    }
                    String where = data.version().name() + " " + item.identifier() + " " + name;
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
                        failures.add(where + "\n  vanilla " + nbt + "\n  typed   " + roundTripped);
                    }
                    checked.merge(name, 1, Integer::sum);
                });
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Item components round-tripped: " + checked);
        assertTrue(checked.size() > 25, checked.toString());
    }

    @Test
    void everyVanillaBlockComponentRoundTrips() throws IOException {
        Map<String, Integer> checked = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (VanillaData data : bundled()) {
            for (BlockPropertyData block : data.dataDrivenBlocks()) {
                List<NbtMap> componentSets = new ArrayList<>();
                componentSets.add(block.getProperties().getCompound("components"));
                for (NbtMap permutation : block.getProperties().getList("permutations", NbtType.COMPOUND, List.of())) {
                    componentSets.add(permutation.getCompound("components"));
                }
                for (NbtMap components : componentSets) {
                    components.forEach((name, value) -> {
                        if (name.startsWith("tag:")) {
                            return;
                        }
                        String where = data.version().name() + " " + block.getName() + " " + name;
                        ComponentType<? extends BlockComponent> type = BlockComponentTypes.byName(name);
                        if (type == null) {
                            failures.add(where + ": no generated type");
                            return;
                        }
                        NbtMap nbt = (NbtMap) value;
                        NbtMap roundTripped = type.read(nbt).toNbt();
                        if (!nbt.equals(roundTripped)) {
                            failures.add(where + "\n  vanilla " + nbt + "\n  typed   " + roundTripped);
                        }
                        checked.merge(name, 1, Integer::sum);
                    });
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Block components round-tripped: " + checked);
        assertTrue(checked.size() > 10, checked.toString());
    }

    @Test
    void vanillaComponentDataSurvivesItemComponents() throws IOException {
        List<String> failures = new ArrayList<>();
        for (VanillaData data : bundled()) {
            for (VanillaItem item : data.items()) {
                if (!item.componentBased() || item.componentData() == null || item.componentData().isEmpty()) {
                    continue;
                }
                Map<String, Object> expected = new TreeMap<>(item.componentData().getCompound("components"));
                if (expected.get("item_tags") instanceof List<?> tags && tags.isEmpty()) {
                    // ItemComponents does not send an empty tag list
                    expected.remove("item_tags");
                }
                NbtMap vanilla = NbtMap.fromMap(expected);
                NbtMap rebuilt = ItemComponents.fromComponentData(vanilla)
                    .toComponentData(item.identifier(), item.runtimeId(), null)
                    .getCompound("components");
                if (!vanilla.equals(rebuilt)) {
                    failures.add(data.version().name() + " " + item.identifier() + "\n  vanilla " + vanilla + "\n  rebuilt " + rebuilt);
                }
            }
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
