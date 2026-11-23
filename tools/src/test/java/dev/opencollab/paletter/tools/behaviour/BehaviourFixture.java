package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.Provenance.Source;
import org.cloudburstmc.nbt.NbtMap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small capture store over three versions for the generator's tests:
 * <ul>
 *     <li>1.1: test:crop (growth 0 to 2; hashes 1-3), test:door (facing 0-1, open bit; 11-14), test:stone (21). AllayMC/Allay
 *     (disputes the door's collision of state 11), CloudburstMC/Data (notes the door's collision as corrected), and the
 *     Script API for destroy time.</li>
 *     <li>1.2: adds test:new (31) that nothing covers. CloudburstMC/Data says stone's destroy time is 2.0 against the Script
 *     API's 1.5 of 1.1 (the BDS value stays: needs recapture), changes the crop's collision and the stone's tint (a string
 *     that would end a comment), and covers the sword first; the Script API changes stone's friction.</li>
 *     <li>1.3: no stone; the door gets a hinge bit (51-58). CloudburstMC/Data takes the crop's collision back (a suspected
 *     artefact) and gives the new door states their collision.</li>
 * </ul>
 */
final class BehaviourFixture {
    static final DataVersion V1 = new DataVersion("1.1", 10);
    static final DataVersion V2 = new DataVersion("1.2", 20);
    static final DataVersion V3 = new DataVersion("1.3", 30);
    static final BlockShape SLAB = BlockShape.of(0, 0, 0, 1, 0.5f, 1);
    static final BlockShape PANEL = BlockShape.of(0, 0, 0, 1, 1, 0.1875f);
    /** A tint that ends a {@code //} comment twice: a raw line break, and a Unicode escape javac would read as one. */
    static final String HOSTILE = "x\nSystem.exit(1); // \\u000a System.exit(2);";

    private BehaviourFixture() {
    }

    static List<BehaviourImport.Bundled> bundled() {
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
        Map<Integer, NbtMap> v3 = new LinkedHashMap<>();
        v2.forEach((h, s) -> {
            if (h < 11 || h == 31) {
                v3.put(h, s);
            }
        });
        hash = 51;
        for (int facing = 0; facing <= 1; facing++) {
            for (byte open = 0; open <= 1; open++) {
                for (byte hinge = 0; hinge <= 1; hinge++) {
                    v3.put(hash++, state("test:door", NbtMap.builder().putInt("facing", facing).putByte("open_bit", open).putByte("hinge", hinge).build()));
                }
            }
        }
        Set<String> items = Set.of("test:sword");
        return List.of(new BehaviourImport.Bundled(V1, v1, items), new BehaviourImport.Bundled(V2, v2, items), new BehaviourImport.Bundled(V3, v3, items));
    }

    private static NbtMap state(String name, NbtMap states) {
        return NbtMap.builder().putString("name", name).putCompound("states", states).build();
    }

    private static <T> Map<Integer, T> all(T value, int... ids) {
        Map<Integer, T> map = new HashMap<>();
        for (int id : ids) {
            map.put(id, value);
        }
        return map;
    }

    static BehaviourStore store() {
        List<Integer> states = List.of(1, 2, 3, 11, 12, 13, 14, 21);
        int[] ids = {1, 2, 3, 11, 12, 13, 14, 21};
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1, V2, V3));
        int allay = builder.capture(V1, Source.ALLAY_DATA, "AllayMC/Allay@" + "c".repeat(40), List.of(11, 12, 13, 14), List.of());
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, allay, Map.of(11, SLAB, 12, SLAB, 13, BlockShape.FULL_CUBE, 14, BlockShape.EMPTY));
        int cb1 = builder.capture(V1, Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "a".repeat(40), states, List.of());
        builder.blocks(BlockBehaviour.DESTROY_TIME, cb1, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 1.5f));
        builder.blocks(BlockBehaviour.FRICTION, cb1, all(0.6f, ids));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, cb1, Map.of(1, BlockShape.EMPTY, 2, BlockShape.EMPTY, 3, BlockShape.EMPTY,
            11, PANEL, 12, SLAB, 13, BlockShape.FULL_CUBE, 14, BlockShape.EMPTY, 21, BlockShape.FULL_CUBE));
        builder.blocks(BlockBehaviour.TINT_METHOD, cb1, all("None", ids));
        builder.dispute(BlockBehaviour.COLLISION_SHAPE, cb1, allay, Map.of(11, SLAB));
        builder.postProcessed(BlockBehaviour.COLLISION_SHAPE, cb1, List.of(11, 12, 13, 14), "shapeSource: corrected");
        int sapi1 = builder.capture(V1, Source.SCRIPT_API, "Paletter data/bds/1.1.0/behaviour-dump.json.gz (destroy_time)", states, List.of());
        builder.blocks(BlockBehaviour.DESTROY_TIME, sapi1, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 1.5f));
        int cb2 = builder.capture(V2, Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "b".repeat(40), states, List.of("test:sword"));
        builder.blocks(BlockBehaviour.DESTROY_TIME, cb2, Map.of(1, 0f, 2, 0.5f, 3, 1f, 11, 3f, 12, 3f, 13, 3f, 14, 3f, 21, 2f));
        Map<Integer, Float> friction = all(0.6f, ids);
        friction.put(21, 0.5f);
        builder.blocks(BlockBehaviour.FRICTION, cb2, friction);
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, cb2, Map.of(1, SLAB, 2, SLAB, 3, SLAB,
            11, PANEL, 12, SLAB, 13, BlockShape.FULL_CUBE, 14, BlockShape.EMPTY, 21, BlockShape.FULL_CUBE));
        Map<Integer, String> tint = all("None", ids);
        tint.put(21, HOSTILE);
        builder.blocks(BlockBehaviour.TINT_METHOD, cb2, tint);
        builder.items(ItemBehaviour.ATTACK_DAMAGE, cb2, Map.of("test:sword", 8));
        int sapi2 = builder.capture(V2, Source.SCRIPT_API, "Paletter data/bds/1.2.0/behaviour-dump.json.gz (friction)", states, List.of());
        Map<Integer, Float> measured = all(0.6f, ids);
        measured.put(21, 0.4f);
        builder.blocks(BlockBehaviour.FRICTION, sapi2, measured);
        int cb3 = builder.capture(V3, Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "d".repeat(40),
            List.of(1, 2, 3, 51, 52, 53, 54, 55, 56, 57, 58), List.of());
        Map<Integer, BlockShape> back = all(BlockShape.FULL_CUBE, 51, 52, 53, 54, 55, 56, 57, 58);
        back.putAll(all(BlockShape.EMPTY, 1, 2, 3));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, cb3, back);
        return builder.build();
    }
}
