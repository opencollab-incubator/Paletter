package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.behaviour.Provenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-state sections of the probe capture and the food probe capture in the behaviour import. */
class BehaviourImportStatesTest {
    private static final DataVersion V1 = new DataVersion("1.0", 10);

    /** Hashes 1 and 2: the stairs, corner a and b; item minecraft:apple. */
    private static BehaviourImport.Bundled bundled() {
        Map<Integer, NbtMap> states = new LinkedHashMap<>();
        states.put(1, NbtMap.builder().putString("name", "minecraft:stairs").putCompound("states", NbtMap.builder().putString("corner", "a").build()).build());
        states.put(2, NbtMap.builder().putString("name", "minecraft:stairs").putCompound("states", NbtMap.builder().putString("corner", "b").build()).build());
        return new BehaviourImport.Bundled(V1, states, Set.of("minecraft:apple"));
    }

    private static void build(Path bds, String directory, String capture) throws IOException {
        Path build = Files.createDirectories(bds.resolve("1.0.0.1").resolve(directory));
        Files.writeString(bds.resolve("1.0.0.1").resolve("experiments.json"), "{\"protocol\": 10}");
        Files.writeString(build.resolve("capture.json"), capture);
    }

    private static final String HEADER = "\"bds\": {\"version\": \"1.0.0.1\", \"build\": \"42\"}";

    @Test
    void theLightAndFrictionOfStatesComeFromTheProbeAndFillInTheStatesBdsDidNotKeep(@TempDir Path bds) throws IOException {
        build(bds, "behaviour-probe", "{" + HEADER + ", \"doors\": {}, \"light_states\": {\"blocks\": {\"minecraft:stairs\": {\"states\": 2, "
            + "\"changed\": [{\"requested\": {\"corner\": \"b\"}, \"became\": \"minecraft:stairs\", \"as\": {\"corner\": \"a\"}}], "
            + "\"emission\": {\"base\": 0.0, \"differ\": [], \"unclear\": []}, \"dampening\": {\"base\": 15.0, \"differ\": [], \"unclear\": []}}}}, "
            + "\"friction_states\": {\"unverified\": \"control probe:friction_90 reads null\"}}");
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.probe(bds);
        assertEquals(2, importer.captures().size(), "the doors, and the states");
        BehaviourImport.Capture states = importer.captures().get(1);
        assertEquals(Provenance.Source.BDS_PROBE, states.source());
        assertEquals(Set.of(1, 2), states.blocks().ids);
        assertTrue(importer.log.stream().anyMatch(line -> line.contains("friction_states is unverified: control probe:friction_90 reads null")), importer.log.toString());

        BehaviourStore store = importer.build();
        assertEquals(15, store.block(BlockBehaviour.LIGHT_DAMPENING, 1, V1).value());
        assertEquals(15, store.block(BlockBehaviour.LIGHT_DAMPENING, 2, V1).value(), "filled: every measured state of the block has it");
        assertTrue(store.block(BlockBehaviour.LIGHT_DAMPENING, 2, V1).postProcessing().startsWith("filled:"));
        assertNull(store.block(BlockBehaviour.LIGHT_DAMPENING, 1, V1).postProcessing());
        assertEquals(0, store.block(BlockBehaviour.LIGHT_EMISSION, 1, V1).value());
        assertNull(store.block(BlockBehaviour.FRICTION, 1, V1), "unverified: nothing from the probe");
    }

    @Test
    void theFoodOfItemsComesFromTheFoodProbe(@TempDir Path bds) throws IOException {
        build(bds, "food-probe", "{" + HEADER + ", \"items\": {\"minecraft:apple\": {\"use_duration\": 32, \"nutrition\": 4, \"saturation_modifier\": 0.3, "
            + "\"can_always_eat\": false}, \"probe:control_food\": {\"nutrition\": 4}}, \"unclear\": {}}");
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.foodProbe(bds);
        assertEquals(1, importer.captures().size());
        assertEquals("BDS 1.0.0.1, Build ID 42", importer.captures().getFirst().match());
        BehaviourStore store = importer.build();
        assertEquals(4, store.item(ItemBehaviour.NUTRITION, "minecraft:apple", V1).value());
        assertEquals(0.3f, store.item(ItemBehaviour.SATURATION_MODIFIER, "minecraft:apple", V1).value());
        assertEquals(32, store.item(ItemBehaviour.USE_DURATION, "minecraft:apple", V1).value());
        assertEquals(false, store.item(ItemBehaviour.CAN_ALWAYS_EAT, "minecraft:apple", V1).value());
        assertEquals(Provenance.Source.BDS_PROBE, store.item(ItemBehaviour.NUTRITION, "minecraft:apple", V1).capture().source());
    }

    @Test
    void anUnverifiedFoodProbeGivesNothingAndIsLogged(@TempDir Path bds) throws IOException {
        build(bds, "food-probe", "{" + HEADER + ", \"unverified\": \"control probe:control_food was not tried\"}");
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.foodProbe(bds);
        assertTrue(importer.captures().isEmpty());
        assertTrue(importer.log.stream().anyMatch(line -> line.contains("the food probe is unverified: control probe:control_food was not tried")));
    }

    @Test
    void withoutCapturesNothingIsImported(@TempDir Path bds) throws IOException {
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.foodProbe(bds);
        importer.probe(bds);
        assertTrue(importer.captures().isEmpty());
    }
}
