package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.internal.behaviour.Provenance.Source;
import dev.opencollab.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import org.cloudburstmc.nbt.NbtMap;

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
        assertEquals("1", store.block(BlockBehaviour.FRICTION, 2, V2).capture().version().name(), "V2 has no capture: inherited from V1");
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

    /** Two sources of V1 that disagree on state 1 (with a note on the stronger one), an item, a default and metadata. */
    private static BehaviourStore marked() {
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1, V2));
        int weak = builder.capture(V1, Source.ALLAY_DATA, "allay", List.of(1, 2), List.of("a:x", "a:y"));
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, weak, Map.of(1, SLAB, 2, BlockShape.FULL_CUBE));
        int strong = builder.capture(V1, Source.CLOUDBURST_DATA, "cb", List.of(1, 2), List.of());
        builder.blocks(BlockBehaviour.COLLISION_SHAPE, strong, Map.of(1, BlockShape.FULL_CUBE, 2, BlockShape.FULL_CUBE));
        builder.dispute(BlockBehaviour.COLLISION_SHAPE, strong, weak, Map.of(1, SLAB));
        builder.postProcessed(BlockBehaviour.COLLISION_SHAPE, strong, List.of(1), "shapeSource: corrected");
        builder.items(ItemBehaviour.ATTACK_DAMAGE, weak, Map.of("a:x", 4, "a:y", 5));
        builder.dispute(ItemBehaviour.ATTACK_DAMAGE, weak, strong, Map.of("a:y", 6));
        builder.postProcessed(ItemBehaviour.ATTACK_DAMAGE, weak, List.of("a:x"), "rounded");
        builder.defaultOf(BlockBehaviour.FRICTION, 0.6f, Source.NATIVE, "capture of 2026-11");
        builder.metadata(NbtMap.builder().putString("command", "make it").build());
        return builder.build();
    }

    @Test
    void disputesPostProcessingDefaultsAndMetadataAreStoredAndRead() throws IOException {
        for (BehaviourStore store : List.of(marked(), BehaviourStore.read(new ByteArrayInputStream(bytes(marked()))))) {
            BehaviourStore.Resolved disputed = store.block(BlockBehaviour.COLLISION_SHAPE, 1, V2);
            assertEquals(BlockShape.FULL_CUBE, disputed.value());
            assertEquals("shapeSource: corrected", disputed.postProcessing());
            assertEquals(1, disputed.disputes().size());
            assertEquals(SLAB, disputed.disputes().getFirst().value());
            assertEquals(Source.ALLAY_DATA, disputed.disputes().getFirst().capture().source());
            BehaviourStore.Resolved plain = store.block(BlockBehaviour.COLLISION_SHAPE, 2, V1);
            assertNull(plain.postProcessing());
            assertEquals(List.of(), plain.disputes());
            BehaviourStore.Resolved item = store.item(ItemBehaviour.ATTACK_DAMAGE, "a:y", V1);
            assertEquals(5, item.value());
            assertEquals(6, item.disputes().getFirst().value());
            assertEquals("rounded", store.item(ItemBehaviour.ATTACK_DAMAGE, "a:x", V1).postProcessing());
            assertEquals(0.6f, store.defaultOf(BlockBehaviour.FRICTION).value());
            assertEquals(Source.NATIVE, store.defaultOf(BlockBehaviour.FRICTION).source());
            assertNull(store.defaultOf(BlockBehaviour.DESTROY_TIME), "an unknown default stays unknown");
            assertEquals("make it", store.metadata().getString("command"));
        }
    }

    @Test
    void aDefaultNeedsDirectEvidence() {
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1));
        assertThrows(IllegalArgumentException.class, () -> builder.defaultOf(BlockBehaviour.FRICTION, 0.6f, Source.CLOUDBURST_DATA, "a third party"));
        assertThrows(IllegalArgumentException.class, () -> builder.defaultOf(BlockBehaviour.FRICTION, 0.6f, Source.NATIVE, " "));
        assertThrows(IllegalArgumentException.class, () -> builder.defaultOf(BlockBehaviour.FRICTION, 0.6f, Source.USER, "x"));
        assertThrows(IllegalArgumentException.class, () -> builder.defaultOf(BlockBehaviour.FRICTION, 0.6f, Source.UNKNOWN_DEFAULT, "x"));
    }

    @Test
    void marksNeedCoveredIdsAndAnotherCapture() {
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(V1));
        int a = builder.capture(V1, Source.CLOUDBURST_DATA, "a", List.of(1), List.of());
        int b = builder.capture(V1, Source.ALLAY_DATA, "b", List.of(1), List.of());
        assertThrows(IllegalArgumentException.class, () -> builder.postProcessed(BlockBehaviour.FRICTION, a, List.of(2), "x"));
        assertThrows(IllegalArgumentException.class, () -> builder.dispute(BlockBehaviour.FRICTION, a, a, Map.of(1, 0.5f)));
    }

    private static byte[] bytes(BehaviourStore store) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.write(out);
        return out.toByteArray();
    }
}
