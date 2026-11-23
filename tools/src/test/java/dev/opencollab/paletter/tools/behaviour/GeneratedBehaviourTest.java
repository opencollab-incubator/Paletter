package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.RegisteredBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.internal.behaviour.BlockSchema;
import dev.opencollab.paletter.internal.behaviour.Capture;
import dev.opencollab.paletter.internal.behaviour.VanillaBehaviour;
import dev.opencollab.paletter.internal.behaviour.VanillaVersion;
import dev.opencollab.paletter.item.RegisteredItem;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated behaviour data holds exactly what the capture store says: for every bundled palette (a preview included,
 * which uses the newest older version's data), every block state and item, and every key, the same value, deciding capture,
 * post-processing note and disputes as the store's own lookup ({@link BehaviourStore#block}, {@link BehaviourStore#item}),
 * and the same defaults of custom content.
 */
class GeneratedBehaviourTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void theGeneratedDataIsTheStore() throws IOException {
        BehaviourStore store;
        try (InputStream in = Files.newInputStream(ROOT.resolve(BehaviourGenerator.CAPTURES))) {
            store = BehaviourStore.read(in);
        }
        VanillaBehaviour data = VanillaBehaviour.bundled();
        Paletter paletter = Paletter.builder().previews(true).behaviour(false).build();
        List<String> differences = new ArrayList<>();
        long compared = 0;
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.vanilla(version);
            VanillaVersion values = data.version(version);
            Map<RegisteredBlock, VanillaVersion.BlockEntry> entries = new HashMap<>();
            for (int i = 0; i < palette.blocks().size(); i++) {
                RegisteredBlockState state = palette.blocks().byIndex(i);
                RegisteredBlock block = Objects.requireNonNull(palette.blocks().block(state.identifier()));
                VanillaVersion.BlockEntry entry = values == null ? null : entries.computeIfAbsent(block, b -> values.block(b.identifier(),
                    BlockSchema.of(b.states().stream().map(RegisteredBlockState::states).toList())));
                for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                    BehaviourStore.Resolved expected = store.block(key, state.getRuntimeId(), version);
                    Object value = entry == null ? null : VanillaVersion.value(entry, key, state.states());
                    Capture capture = value == null ? null : values.capture(state.identifier(), key, state.states());
                    compare(differences, version + " " + state + " " + key, expected, value, capture, data, key, state.identifier(), state.states());
                    compared++;
                }
            }
            for (RegisteredItem item : palette.items().ownDefinitions()) {
                for (BehaviourKey<?> key : ItemBehaviour.keys()) {
                    BehaviourStore.Resolved expected = store.item(key, item.getIdentifier(), version);
                    Object value = values == null ? null : values.item(item.getIdentifier(), key);
                    Capture capture = value == null ? null : values.itemCapture(item.getIdentifier(), key);
                    compare(differences, version + " " + item.getIdentifier() + " " + key, expected, value, capture, data, key, item.getIdentifier(), NbtMap.EMPTY);
                    compared++;
                }
            }
        }
        for (BehaviourKey<?> key : allKeys()) {
            BehaviourStore.Default expected = store.defaultOf(key);
            VanillaBehaviour.Default actual = data.customDefault(key);
            if (!Objects.equals(expected == null ? null : List.of(expected.value(), expected.source(), expected.reference()),
                actual == null ? null : List.of(actual.value(), actual.source(), actual.reference()))) {
                differences.add("default of " + key + ": " + expected + " / " + actual);
            }
        }
        System.out.println("Compared " + compared + " values of " + paletter.versions().size() + " palettes, " + differences.size() + " differ");
        assertTrue(compared > 1_000_000, "every state and item of every palette: " + compared);
        assertEquals(List.of(), differences.stream().limit(20).toList(), differences.size() + " differences");
    }

    private static List<BehaviourKey<?>> allKeys() {
        List<BehaviourKey<?>> keys = new ArrayList<>(BlockBehaviour.keys());
        keys.addAll(ItemBehaviour.keys());
        return keys;
    }

    private static void compare(List<String> differences, String what, BehaviourStore.@Nullable Resolved expected, @Nullable Object value,
                                @Nullable Capture capture, VanillaBehaviour data, BehaviourKey<?> key, String id, NbtMap states) {
        if (expected == null || value == null) {
            if (expected != null || value != null) {
                differences.add(what + ": " + (expected == null ? null : expected.value()) + " / " + value);
            }
            return;
        }
        BehaviourStore.Capture decided = expected.capture();
        List<Object> expect = List.of(expected.value(), decided.source(), decided.reference(), decided.version().name(),
            String.valueOf(expected.postProcessing()), expected.disputes().stream().map(d -> List.of(d.capture().source(), d.capture().reference(),
                d.capture().version().name(), d.value())).toList());
        List<Object> actual = capture == null ? List.of(value) : List.of(value, capture.source(), capture.reference(), capture.version().name(),
            String.valueOf(data.note(capture, key, id, states)), data.disputes(capture, key, id, states).stream().map(d -> List.of(d.capture().source(),
                d.capture().reference(), d.capture().version().name(), d.value())).toList());
        if (!expect.equals(actual)) {
            differences.add(what + ": " + expect + " / " + actual);
        }
    }
}
