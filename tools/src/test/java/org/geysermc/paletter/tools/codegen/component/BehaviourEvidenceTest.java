package org.geysermc.paletter.tools.codegen.component;

import org.geysermc.paletter.tools.codegen.component.BehaviourKeys.Conversion;
import org.geysermc.paletter.tools.codegen.component.BehaviourKeys.Key;
import org.geysermc.paletter.tools.codegen.component.BehaviourKeys.Leaf;
import org.geysermc.paletter.tools.codegen.component.BehaviourKeys.Owner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviourEvidenceTest {
    private static final BehaviourEvidence EVIDENCE = new BehaviourEvidence("dump", Set.of("Block.isSolid", "Block.getLightLevel",
        "Block.getMapColor", "BlockMapColorComponent.color"), Set.of("ItemStack.maxAmount"),
        Map.of("Block.getLightLevel", Set.of("minecraft:stone"), "Block.getMapColor", Set.of("minecraft:grass_block")),
        "probe", Set.of("climbing"), "inventory", Set.of("minecraft:friction"), Set.of("MaxStackSizeItemComponent"), false);

    private static Key key(Owner owner, String name, List<String> sources, BehaviourKeys.Wire wire) {
        return new Key(owner, name, "Boolean", "", sources, Conversion.NONE, wire);
    }

    @Test
    void theKeysOfTheRepositoryMatchTheNewestCaptures() throws Exception {
        Path root = Path.of(System.getProperty("paletter.root"));
        assertEquals(List.of(), BehaviourEvidence.read(root).problems(BehaviourKeys.KEYS, BehaviourKeys.IGNORED));
    }

    @Test
    void everyDumpedPropertyIsAKeyOrIgnored() {
        List<Key> keys = List.of(key(Owner.BLOCK, "solid", List.of("SAPI:Block.isSolid"), null),
            key(Owner.BLOCK, "map_color", List.of("SAPI:BlockMapColorComponent.color", "SAPI:Block.getMapColor"), null),
            key(Owner.BLOCK, "climbable", List.of("PROBE:climbing", "NATIVE:BlockType.mProperties.Climbable"),
                new Leaf("minecraft:friction", "", "minecraft:friction.value")),
            key(Owner.ITEM, "max_stack_size", List.of("SAPI:ItemStack.maxAmount"), new Leaf("minecraft:max_stack_size", "value", "x.y")));
        assertEquals(List.of(), EVIDENCE.problems(keys, Map.of("SAPI:Block.getLightLevel", "world-dependent: the light")));

        List<String> problems = EVIDENCE.problems(keys.subList(1, 4), Map.of("SAPI:Block.getLightLevel", "the light", "SAPI:Block.gone", "x"));
        assertTrue(problems.contains("ignored Block.getLightLevel is world-dependent (dump): say so in its reason"), problems.toString());
        assertTrue(problems.contains("ignored Block.gone is not in dump: remove it"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("Block.isSolid (dump) is neither the source of a key nor ignored")), problems.toString());
    }

    @Test
    void sourcesMustBeInTheirCapture() {
        List<String> problems = EVIDENCE.problems(List.of(
            key(Owner.BLOCK, "light", List.of("SAPI:Block.getLightLevel"), null),
            key(Owner.BLOCK, "gone", List.of("SAPI:Block.isGone", "PROBE:nothing"), new Leaf("minecraft:nothing", "", "a.b"))),
            Map.of("SAPI:Block.isSolid", "x", "SAPI:Block.getMapColor", "world-dependent", "SAPI:BlockMapColorComponent.color", "x",
                "SAPI:ItemStack.maxAmount", "x"));
        assertEquals(List.of(
            "block:light: Block.getLightLevel is world-dependent (dump, world check); it can only fill in after another source",
            "block:gone: Block.isGone is not in dump",
            "block:gone: PROBE:nothing is not in probe",
            "block:gone: BDS has no component minecraft:nothing (inventory)"), problems);
    }

    @Test
    void directionsExpand() {
        assertEquals(6, BehaviourKeys.expand("Block.liquidCanFlowFromDirection(Water,{direction})").size());
        assertEquals("Block.liquidCanFlowFromDirection(Water,Down)", BehaviourKeys.expand("Block.liquidCanFlowFromDirection(Water,{direction})").getFirst());
        assertEquals("MaxStackSizeItemComponent", BehaviourEvidence.itemComponentClass("minecraft:max_stack_size"));
    }
}
