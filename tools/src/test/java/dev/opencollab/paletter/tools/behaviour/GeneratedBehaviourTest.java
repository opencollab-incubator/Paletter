package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.behaviour.Capture;
import dev.opencollab.paletter.internal.behaviour.VanillaBehaviour;
import dev.opencollab.paletter.internal.behaviour.VanillaVersion;
import dev.opencollab.paletter.item.RegisteredItem;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated behaviour data holds exactly what the capture store says under the rule the generator applies: for every
 * bundled palette (a preview included, which uses the newest older version's data), every block state and item, and every
 * key, the same value, deciding capture, post-processing note and disputes as the store's own lookup with
 * {@link BdsFirst} on top, and the same defaults of custom content. It also prints where the rule differs from the store's
 * own lookup (the values a direct capture keeps against a newer dump).
 */
class GeneratedBehaviourTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void theGeneratedDataIsTheStore() throws IOException {
        BehaviourStore store = BehaviourGenerator.read(ROOT);
        VanillaBehaviour data = VanillaBehaviour.bundled();
        Paletter paletter = Paletter.builder().previews(true).behaviour(false).build();
        List<String> differences = new ArrayList<>();
        Map<String, Integer> ruled = new TreeMap<>();
        long compared = 0;
        long known = 0;
        long marked = 0;
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.vanilla(version);
            VanillaVersion values = data.version(version);
            for (int i = 0; i < Access.palette().blocks(palette).size(); i++) {
                RegisteredBlockState state = Access.palette().blocks(palette).byIndex(i);
                VanillaVersion.BlockEntry entry = values == null ? null : values.block(state.identifier());
                boolean covered = entry != null && entry.covers(state.states());
                for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                    BehaviourStore.Resolved expected = BdsFirst.block(store, key, state.getRuntimeId(), version);
                    if (BdsFirst.blockKept(store, key, state.getRuntimeId(), version) != null) {
                        ruled.merge(version.name() + " " + key, 1, Integer::sum);
                    }
                    Object value = covered ? VanillaVersion.value(entry, key, state.states()) : null;
                    Capture capture = value == null ? null : values.capture(state.identifier(), key, state.states());
                    marked += compare(differences, version + " " + state + " " + key, expected, value, capture, data, values, key,
                        state.identifier(), state.states());
                    compared++;
                    known += value == null ? 0 : 1;
                }
            }
            for (RegisteredItem item : Access.palette().items(palette).ownDefinitions()) {
                for (BehaviourKey<?> key : ItemBehaviour.keys()) {
                    BehaviourStore.Resolved expected = BdsFirst.item(store, key, item.getIdentifier(), version);
                    if (BdsFirst.itemKept(store, key, item.getIdentifier(), version) != null) {
                        ruled.merge(version.name() + " " + key, 1, Integer::sum);
                    }
                    Object value = values == null ? null : values.item(item.getIdentifier(), key);
                    Capture capture = value == null ? null : values.itemCapture(item.getIdentifier(), key);
                    marked += compare(differences, version + " " + item.getIdentifier() + " " + key, expected, value, capture, data, values, key,
                        item.getIdentifier(), NbtMap.EMPTY);
                    compared++;
                    known += value == null ? 0 : 1;
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
        System.out.println("Compared " + compared + " values of " + paletter.versions().size() + " palettes (" + known + " known, " + marked
            + " with a note or dispute), " + differences.size() + " differ");
        System.out.println("Where a direct capture keeps its value against a newer dump (BdsFirst), by version and key: " + ruled);
        // Not an empty store and empty data: most values are known, and there are notes and disputes
        assertTrue(compared > 1_000_000 && known > compared / 2 && marked > 1_000, compared + " compared, " + known + " known, " + marked + " marked");
        assertEquals(List.of(), differences.stream().limit(20).toList(), differences.size() + " differences");
    }

    private static List<BehaviourKey<?>> allKeys() {
        List<BehaviourKey<?>> keys = new ArrayList<>(BlockBehaviour.keys());
        keys.addAll(ItemBehaviour.keys());
        return keys;
    }

    /** @return 1 if the expected value has a note or disputes, else 0 */
    private static int compare(List<String> differences, String what, BehaviourStore.@Nullable Resolved expected, @Nullable Object value,
                               @Nullable Capture capture, VanillaBehaviour data, @Nullable VanillaVersion values, BehaviourKey<?> key,
                               String id, NbtMap states) {
        if (expected == null || value == null) {
            if (expected != null || value != null) {
                differences.add(what + ": " + (expected == null ? null : expected.value()) + " / " + value);
            }
            return 0;
        }
        BehaviourStore.Capture decided = expected.capture();
        List<Object> expect = List.of(expected.value(), decided.source(), decided.reference(), decided.version().name(),
            String.valueOf(expected.postProcessing()), expected.disputes().stream().map(d -> List.of(d.capture().source(), d.capture().reference(),
                d.capture().version().name(), d.value())).toList());
        List<Object> actual = capture == null ? List.of(value) : List.of(value, capture.source(), capture.reference(), capture.version().name(),
            String.valueOf(data.note(values, capture, key, id, states)), data.disputes(values, capture, key, id, states).stream()
                .map(d -> List.of(d.capture().source(), d.capture().reference(), d.capture().version().name(), d.value())).toList());
        if (!expect.equals(actual)) {
            differences.add(what + ": " + expect + " / " + actual);
        }
        return expected.postProcessing() != null || !expected.disputes().isEmpty() ? 1 : 0;
    }
}
