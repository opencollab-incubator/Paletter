package org.geysermc.paletter.internal.behaviour;

import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.Provenance.Source;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BehaviourStoreTest {
    private static final DataVersion V1 = new DataVersion("1", 10);
    private static final DataVersion V2 = new DataVersion("2", 20);
    private static final DataVersion V3 = new DataVersion("3", 30);
    private static final DataVersion NEWER = new DataVersion("4", 40);
    private static final BlockShape SLAB = BlockShape.of(0, 0, 0, 1, 0.5f, 1);

    /** V1: two sources, V2: nothing, V3: the strong source changes one value and adds a state. */
    private static BehaviourStore store() {
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V3, V1, V2));
        int weak = builder.capture(V1, Source.ALLAY_DATA, "allay", List.of(3), List.of("a:x"));
        builder.blocks(BlockBehaviour.FRICTION, weak, Map.of(3, 0.5f));
        builder.items(ItemBehaviour.ATTACK_DAMAGE, weak, Map.of("a:x", 4));
        int strong = builder.capture(V1, Source.CLOUDBURST_DATA, "cb1", List.of(2, 1), List.of());
        builder.blocks(BlockBehaviour.FRICTION, strong, Map.of(1, 0.6f, 2, 0.98f));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, strong, Map.of(1, BlockShape.FULL_CUBE, 2, SLAB));
        int later = builder.capture(V3, Source.CLOUDBURST_DATA, "cb3", List.of(1, 2, -7), List.of());
        builder.blocks(BlockBehaviour.FRICTION, later, Map.of(1, 0.6f, 2, 0.9f, -7, 0.6f));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, later, Map.of(1, BlockShape.FULL_CUBE, 2, SLAB, -7, BlockShape.EMPTY));
        return builder.build();
    }

    @Test
    void valuesInheritUntilALaterCaptureChangesThem() {
        BehaviourStore store = store();
        assertEquals(0.98f, store.block(BlockBehaviour.FRICTION, 2, V1).value());
        assertEquals(0.98f, store.block(BlockBehaviour.FRICTION, 2, V2).value());
        assertEquals("1", store.block(BlockBehaviour.FRICTION, 2, V2).capture().version(), "V2 has no capture: inherited from V1");
        assertEquals(0.9f, store.block(BlockBehaviour.FRICTION, 2, V3).value());
        assertEquals(0.9f, store.block(BlockBehaviour.FRICTION, 2, NEWER).value(), "a newer unknown version inherits the newest");
        assertNull(store.block(BlockBehaviour.FRICTION, -7, V2), "added in V3");
        assertEquals(BlockShape.EMPTY, store.block(BlockBehaviour.COLLISION_SHAPE, -7, V3).value());
        assertNull(store.block(BlockBehaviour.FRICTION, 2, new DataVersion("0", 5)), "older than every capture");
    }

    @Test
    void anUnchangedValueIsConfirmedByTheNewerCapture() {
        BehaviourStore.Resolved resolved = store().block(BlockBehaviour.FRICTION, 1, V3);
        assertEquals(0.6f, resolved.value());
        assertEquals("cb3", resolved.capture().reference());
    }

    @Test
    void theStrongerSourceDecidesAndTheWeakerFillsTheRest() {
        BehaviourStore store = store();
        assertEquals(Source.CLOUDBURST_DATA, store.block(BlockBehaviour.FRICTION, 1, V1).capture().source());
        assertEquals(Source.ALLAY_DATA, store.block(BlockBehaviour.FRICTION, 3, V3).capture().source(), "only the weak source has it");
        assertEquals(4, store.item(ItemBehaviour.ATTACK_DAMAGE, "a:x", V3).value());
        assertNull(store.item(ItemBehaviour.MAX_STACK_SIZE, "a:x", V3), "no source has the key");
    }

    @Test
    void writesAndReadsTheSameStore() throws IOException {
        BehaviourStore store = store();
        byte[] bytes = bytes(store);
        BehaviourStore read = BehaviourStore.read(new ByteArrayInputStream(bytes));
        for (DataVersion version : List.of(V1, V2, V3, NEWER)) {
            for (int id : new int[] {1, 2, 3, -7}) {
                for (var key : List.of(BlockBehaviour.FRICTION, BlockBehaviour.COLLISION_SHAPE)) {
                    BehaviourStore.Resolved a = store.block(key, id, version);
                    BehaviourStore.Resolved b = read.block(key, id, version);
                    assertEquals(a == null ? null : a.value(), b == null ? null : b.value(), key + " " + id + " " + version);
                    assertEquals(a == null ? null : a.capture().reference(), b == null ? null : b.capture().reference());
                }
            }
        }
        assertArrayEquals(bytes, bytes(read), "deterministic");
    }

    @Test
    void rejectsValuesOutsideTheCapture() {
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1));
        int capture = builder.capture(V1, Source.CLOUDBURST_DATA, "x", List.of(1), List.of());
        assertThrows(IllegalArgumentException.class, () -> builder.blocks(BlockBehaviour.FRICTION, capture, Map.of(2, 0.6f)));
        assertThrows(IllegalArgumentException.class, () -> builder.items(BlockBehaviour.FRICTION, capture, Map.of()));
    }

    private static byte[] bytes(BehaviourStore store) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.write(out);
        return out.toByteArray();
    }
}
