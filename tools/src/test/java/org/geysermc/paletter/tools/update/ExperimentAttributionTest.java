package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Checker;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.tools.client.CapturedData;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.ExperimentContent;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.data.VersionFallbacks;
import org.geysermc.paletter.item.ItemFallback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attribution diffs on synthetic captures shaped like the y_2026_drop_4 one of BDS 1.26.63.1. */
class ExperimentAttributionTest {

    private static NbtMap item(String name, int id, int version, boolean componentBased) {
        return NbtMap.builder().putString("name", name).putShort("id", (short) id).putBoolean("componentBased", componentBased)
            .putInt("version", version).putCompound("data", NbtMap.EMPTY).build();
    }

    private static CapturedData capture(List<NbtMap> items, Map<String, NbtMap> blocks, List<CreativeStack> creative) {
        Map<String, NbtMap> byName = new LinkedHashMap<>();
        items.forEach(item -> byName.put(item.getString("name"), item));
        Map<CreativeCategory, List<CreativeNode>> categories = new java.util.EnumMap<>(CreativeCategory.class);
        categories.put(CreativeCategory.ITEMS, List.of(new CreativeGroup("itemGroup.name.test", null, creative)));
        return new CapturedData(byName, blocks, CreativeLayout.of(categories));
    }

    private static CreativeStack stack(String id, int damage) {
        return new CreativeStack(id, damage, 1, null, null);
    }

    private static BdsDump dump(Set<String> items, Set<String> blocks) {
        Map<String, BdsDump.Item> itemMap = new LinkedHashMap<>();
        items.forEach(item -> itemMap.put(item, new BdsDump.Item(64, item, List.of(), 0)));
        Map<String, Map<String, List<Object>>> blockMap = new LinkedHashMap<>();
        blocks.forEach(block -> blockMap.put(block, Map.of()));
        return new BdsDump("1.26.63.1", Map.of(), Map.of(), blockMap, Map.of(), Map.of(), itemMap, List.of());
    }

    private static final CapturedData BASE = capture(
        List.of(item("minecraft:stone", 1, 2, false), item("minecraft:icicle", 900, 2, false), item("minecraft:arrow", 300, 2, false)),
        Map.of("minecraft:old_block", NbtMap.builder().putInt("x", 1).build()),
        List.of(stack("minecraft:stone", 0), stack("minecraft:arrow", 0)));

    @Test
    void dropFour() {
        // Runtime ids shift (ice_ball takes 900): that is no change of icicle
        CapturedData with = capture(
            List.of(item("minecraft:stone", 1, 2, false), item("minecraft:ice_ball", 900, 1, true), item("minecraft:icicle", 901, 1, true),
                item("minecraft:arrow", 300, 2, false)),
            Map.of("minecraft:old_block", NbtMap.builder().putInt("x", 1).build(), "minecraft:icicle", NbtMap.EMPTY),
            List.of(stack("minecraft:stone", 0), stack("minecraft:arrow", 0), stack("minecraft:arrow", 48), stack("minecraft:ice_ball", 0),
                stack("minecraft:frostbite_spawn_egg", 0)));
        ExperimentAttribution.Diff diff = ExperimentAttribution.diff(BASE, with,
            dump(Set.of("minecraft:stone"), Set.of("minecraft:stone")),
            dump(Set.of("minecraft:stone", "minecraft:ice_ball"), Set.of("minecraft:stone", "minecraft:frost_block")));
        assertEquals(List.of("minecraft:ice_ball"), diff.itemsAdded());
        assertEquals(List.of("minecraft:icicle"), diff.itemsChanged());
        assertEquals(List.of("minecraft:ice_ball"), diff.scriptItemsAdded());
        assertEquals(List.of("minecraft:frost_block"), diff.blocksAdded());
        assertEquals(List.of("minecraft:icicle"), diff.dataDrivenBlocks());
        assertEquals(List.of("minecraft:arrow:48", "minecraft:frostbite_spawn_egg", "minecraft:ice_ball"), diff.creativeAdded());
        assertEquals(List.of(), diff.removed());

        ExperimentContent content = diff.content(ExperimentAttribution.creativeItems(BASE));
        // arrow:48 is a new variant of an item already in the creative inventory, not a gated item
        assertEquals(List.of("minecraft:frostbite_spawn_egg", "minecraft:ice_ball", "minecraft:icicle"), content.items());
        assertEquals(List.of("minecraft:frost_block", "minecraft:icicle"), content.blocks());
        assertEquals(diff.creativeAdded(), content.creative());
        assertTrue(diff.lines().stream().anyMatch(line -> line.startsWith("items added (1)")));
    }

    @Test
    void sameContentIsEmptyAndRemovalsAreListed() {
        assertTrue(ExperimentAttribution.diff(BASE, BASE, null, null).isEmpty());
        CapturedData less = capture(List.of(item("minecraft:stone", 1, 2, false)), Map.of(), List.of(stack("minecraft:stone", 0)));
        ExperimentAttribution.Diff diff = ExperimentAttribution.diff(BASE, less, null, null);
        assertEquals(List.of("creative minecraft:arrow", "data driven block minecraft:old_block", "item minecraft:arrow", "item minecraft:icicle"),
            diff.removed());
    }

    @Test
    void interactions() {
        ExperimentAttribution.Diff a = new ExperimentAttribution.Diff(List.of("x:a"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ExperimentAttribution.Diff both = new ExperimentAttribution.Diff(List.of("x:a", "x:ab"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals(List.of("item added x:ab"), ExperimentAttribution.unexplained(both, Map.of("a", a)));
        assertEquals(List.of(), ExperimentAttribution.unexplained(a, Map.of("a", a)));
    }

    @Test
    void checkerWantsForceFallbacksForContentGatedOnlyInOlderVersions() {
        VanillaData latest = data("27.0", Map.of(), VersionFallbacks.NONE);
        ExperimentContent drop = new ExperimentContent(List.of("minecraft:icicle"), List.of("minecraft:icicle"), List.of());
        VanillaData older = data("26.60", Map.of("y_2026_drop_4", drop), VersionFallbacks.NONE);
        List<String> problems = new ArrayList<>();
        Checker.experimentProblems(older, latest, Set.of("minecraft:stone", "minecraft:icicle"), Set.of("minecraft:stone", "minecraft:icicle"), problems);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("block minecraft:icicle is behind [y_2026_drop_4]"));

        // Acknowledged: force fallbacks (items), unmapped (blocks)
        VanillaData covered = data("26.60", Map.of("y_2026_drop_4", drop), new VersionFallbacks(Map.of(),
            Map.of("minecraft:icicle", new ItemFallback("minecraft:stone", true)), Set.of("minecraft:icicle")));
        problems.clear();
        Checker.experimentProblems(covered, latest, Set.of("minecraft:stone", "minecraft:icicle"), Set.of("minecraft:stone", "minecraft:icicle"), problems);
        assertEquals(List.of(), problems);

        // Still gated in the newest version: nothing to do
        problems.clear();
        Checker.experimentProblems(older, data("27.0", Map.of("y_2026_drop_4", drop), VersionFallbacks.NONE),
            Set.of("minecraft:stone", "minecraft:icicle"), Set.of("minecraft:stone", "minecraft:icicle"), problems);
        assertEquals(List.of(), problems);

        // A force fallback for content that is not gated is unused
        VanillaData forced = data("26.60", Map.of("y_2026_drop_4", drop), new VersionFallbacks(Map.of(),
            Map.of("minecraft:stone", new ItemFallback("minecraft:icicle", true)), Set.of("minecraft:icicle")));
        problems.clear();
        Checker.experimentProblems(forced, latest, Set.of("minecraft:stone", "minecraft:icicle"), Set.of("minecraft:stone", "minecraft:icicle"), problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("force item fallback minecraft:stone")), problems.toString());
    }

    private static VanillaData data(String name, Map<String, ExperimentContent> experiments, VersionFallbacks fallbacks) {
        List<NbtMap> states = List.of(BlockStates.of("minecraft:stone", NbtMap.EMPTY), BlockStates.of("minecraft:icicle", NbtMap.EMPTY));
        List<VanillaItem> items = List.of(new VanillaItem("minecraft:stone", 1, 2, false, null), new VanillaItem("minecraft:icicle", 2, 2, false, null));
        return new VanillaData(new DataVersion(name, 1), states, items, List.of(), CreativeLayout.of(Map.of()), Map.of(), fallbacks, Map.of(),
            experiments);
    }
}
