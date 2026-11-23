package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.BlockSchema;
import dev.opencollab.paletter.internal.behaviour.Capture;
import dev.opencollab.paletter.internal.behaviour.Provenance.Source;
import dev.opencollab.paletter.internal.behaviour.VanillaBehaviour;
import dev.opencollab.paletter.internal.behaviour.VanillaVersion;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour generator on a small store: what the classes say, how a version's changes are commented, that a retired
 * version folds into the base, that the output is the same every time, and that the generated code, compiled and run,
 * gives the store's values.
 */
class BehaviourCodeTest {
    private static final DataVersion V1 = new DataVersion("1.1", 10);
    private static final DataVersion V2 = new DataVersion("1.2", 20);
    private static final BlockShape SLAB = BlockShape.of(0, 0, 0, 1, 0.5f, 1);
    private static final BlockShape PANEL = BlockShape.of(0, 0, 0, 1, 1, 0.1875f);

    /** test:crop (growth 0 to 2, hashes 1-3), test:door (facing 0-1, open bit, 11-14), test:stone (21); V2 adds test:new (31). */
    private static List<BehaviourImport.Bundled> bundled() {
        Map<Integer, NbtMap> v1 = new LinkedHashMap<>();
        for (int growth = 0; growth <= 2; growth++) {
            v1.put(1 + growth, state("test:crop", NbtMap.builder().putInt("growth", growth).build()));
        }
        int hash = 11;
        for (int facing = 0; facing <= 1; facing++) {
            for (byte open = 0; open <= 1; open++) {
                v1.put(hash++, state("test:door", NbtMap.builder().putInt("facing", facing).putByte("open_bit", open).build()));
            }
        }
        v1.put(21, state("test:stone", NbtMap.EMPTY));
        Map<Integer, NbtMap> v2 = new LinkedHashMap<>(v1);
        v2.put(31, state("test:new", NbtMap.EMPTY));
        return List.of(new BehaviourImport.Bundled(V1, v1, Set.of("test:sword")), new BehaviourImport.Bundled(V2, v2, Set.of("test:sword")));
    }

    private static NbtMap state(String name, NbtMap states) {
        return NbtMap.builder().putString("name", name).putCompound("states", states).build();
    }

    /** V1 from CloudburstMC/Data; V2 from the Script API (destroy time) and CloudburstMC/Data (the rest). */
    static BehaviourStore store() {
        List<Integer> v1States = List.of(1, 2, 3, 11, 12, 13, 14, 21);
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1, V2));
        int cb1 = builder.capture(V1, Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "a".repeat(40), v1States, List.of("test:sword"));
        builder.blocks(BlockBehaviour.DESTROY_TIME, cb1, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 1.5f));
        builder.blocks(BlockBehaviour.FRICTION, cb1, Map.of(1, 0.6f, 2, 0.6f, 3, 0.6f, 11, 0.6f, 12, 0.6f, 13, 0.6f, 14, 0.6f, 21, 0.6f));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, cb1, Map.of(1, BlockShape.EMPTY, 2, BlockShape.EMPTY, 3, BlockShape.EMPTY,
            11, PANEL, 12, SLAB, 13, BlockShape.FULL_CUBE, 14, BlockShape.EMPTY, 21, BlockShape.FULL_CUBE));
        builder.items(ItemBehaviour.ATTACK_DAMAGE, cb1, Map.of("test:sword", 7));
        int cb2 = builder.capture(V2, Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "b".repeat(40), v1States, List.of("test:sword"));
        builder.blocks(BlockBehaviour.DESTROY_TIME, cb2, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 2f));
        builder.blocks(BlockBehaviour.FRICTION, cb2, Map.of(1, 0.6f, 2, 0.6f, 3, 0.6f, 11, 0.6f, 12, 0.6f, 13, 0.6f, 14, 0.6f, 21, 0.5f));
        builder.items(ItemBehaviour.ATTACK_DAMAGE, cb2, Map.of("test:sword", 8));
        int sapi = builder.capture(V2, Source.SCRIPT_API, "Paletter data/bds/1.2.0/behaviour-dump.json.gz (destroy_time)", v1States, List.of());
        builder.blocks(BlockBehaviour.DESTROY_TIME, sapi, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 2f));
        return builder.build();
    }

    private static BehaviourCode.Result generate(BehaviourStore store) {
        List<BehaviourImport.Bundled> bundled = bundled().stream().filter(b -> store.generated().contains(b.version().name())).toList();
        return BehaviourCode.generate(new BehaviourModel(store, bundled), "a test store");
    }

    private static String file(BehaviourCode.Result result, String name) {
        return result.files().get(BehaviourCode.DIRECTORY + "/" + name + ".java");
    }

    @Test
    void theBaseHasOneChainPerBlockWithItsValuesAsData() {
        BehaviourCode.Result result = generate(store());
        String base = file(result, "BehaviourBase");
        assertTrue(base.startsWith(BehaviourCode.GENERATOR + " from a test store"), base);
        assertTrue(base.contains("v.block(\"test:crop\", \"growth=0..2\")"), base);
        assertTrue(base.contains(".destroyTime(\"growth\", 0, 0.0f, 1, 0.5f, 2, 1.0f)"), base);
        assertTrue(base.contains(".collisionShape(TEST_DOOR_COLLISION_SHAPE)"), base);
        assertTrue(base.contains("v.item(\"test:sword\")"), base);
        String tables = file(result, "BehaviourTables");
        assertTrue(tables.contains("Cell TEST_DOOR_COLLISION_SHAPE = Cell.table(new String[] {\"facing\", \"open_bit\"}, new Object[][] {{0, 1}, {false, true}}"), tables);
        assertTrue(file(result, "BehaviourShapes").contains("BOX_0_0_0_16_8_16 = BlockShape.of(0.0f, 0.0f, 0.0f, 1.0f, 0.5f, 1.0f)"));
        assertTrue(file(result, "BehaviourCaptures").contains("CLOUDBURST_DATA_1_1 = new Capture(Provenance.Source.CLOUDBURST_DATA"));
    }

    @Test
    void aVersionsClassHasWhatChangedWithTheCaptureThatDecided() {
        BehaviourCode.Result result = generate(store());
        String changes = file(result, "BehaviourChanges1_2");
        assertTrue(changes.contains("// destroy_time 1.5 -> 2.0: BDS Script API dump 1.2.0, confirmed by CloudburstMC/Data bbbbbbb\n"), changes);
        assertTrue(changes.contains("// friction 0.6 -> 0.5: CloudburstMC/Data bbbbbbb; SOURCE ONLY (no BDS capture)\n"), changes);
        assertTrue(changes.contains("v.change(\"test:stone\").destroyTime(2.0f).friction(0.5f);"), changes);
        // the only item changed: a new value for every item
        assertTrue(changes.contains("// attack_damage -> 8 for every item (1 changed): CloudburstMC/Data bbbbbbb; SOURCE ONLY (no BDS capture)\n"
            + "        v.everyItem().attackDamage(8);"), changes);
        assertFalse(changes.contains("test:crop"), "unchanged blocks are not repeated");
        assertFalse(changes.contains("test:new"), "a block without values has no entry");
        assertEquals(List.of("test:stone friction", "every item attack_damage"),
            result.sourceOnly().stream().map(s -> s.id() + " " + s.key().name()).toList());
        String sources = file(result, "BehaviourSources1_2");
        assertTrue(sources.contains(".destroyTime(SCRIPT_API_1_2)"), sources);
    }

    @Test
    void retiringTheOldestVersionFoldsItIntoTheBase() {
        BehaviourCode.Result result = generate(store().retire("1.1"));
        assertFalse(result.files().containsKey(BehaviourCode.DIRECTORY + "/BehaviourChanges1_2.java"));
        String base = file(result, "BehaviourBase");
        assertTrue(base.contains("The behaviour values of 1.2, the oldest generated version"), base);
        // 2.0 is the destroy time of most blocks now, the default; stone keeps the friction it got in 1.2
        assertTrue(base.contains("v.everyBlock()\n                .destroyTime(2.0f)"), base);
        assertTrue(base.contains("v.block(\"test:stone\", \"\").friction(0.5f);"), base);
    }

    @Test
    void theSameStoreGivesTheSameFiles() {
        assertEquals(generate(store()).files(), generate(store()).files());
    }

    @Test
    void theGeneratedCodeGivesTheStoresValues(@TempDir Path classes) throws Exception {
        BehaviourStore store = store();
        VanillaBehaviour data = CompiledBehaviour.load(generate(store).files(), classes);
        for (BehaviourImport.Bundled palette : bundled()) {
            VanillaVersion values = data.version(palette.version());
            for (Map.Entry<Integer, NbtMap> state : palette.states().entrySet()) {
                String id = state.getValue().getString("name");
                List<NbtMap> states = palette.states().values().stream().filter(s -> s.getString("name").equals(id)).map(s -> s.getCompound("states")).toList();
                VanillaVersion.BlockEntry entry = values.block(id, BlockSchema.of(states));
                for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                    BehaviourStore.Resolved expected = store.block(key, state.getKey(), palette.version());
                    NbtMap properties = state.getValue().getCompound("states");
                    Object value = entry == null ? null : VanillaVersion.value(entry, key, properties);
                    assertEquals(expected == null ? null : expected.value(), value, id + " " + key + " " + palette.version());
                    if (expected != null) {
                        Capture capture = values.capture(id, key, properties);
                        assertEquals(expected.capture().reference(), capture.reference(), id + " " + key);
                    }
                }
            }
            BehaviourStore.Resolved sword = store.item(ItemBehaviour.ATTACK_DAMAGE, "test:sword", palette.version());
            assertEquals(sword.value(), values.item("test:sword", ItemBehaviour.ATTACK_DAMAGE));
            assertEquals(sword.capture().reference(), values.itemCapture("test:sword", ItemBehaviour.ATTACK_DAMAGE).reference());
        }
    }
}
