package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-written runtime of the generated behaviour data: cells, schemas, the version builder and the version lookup. */
class VanillaRuntimeTest {
    private static final DataVersion V1 = DataVersion.of("1.0", 10);
    private static final DataVersion V2 = DataVersion.of("2.0", 20);

    private static NbtMap states(Object... pairs) {
        var builder = NbtMap.builder();
        for (int i = 0; i < pairs.length; i += 2) {
            builder.put((String) pairs[i], pairs[i + 1]);
        }
        return builder.build();
    }

    @Test
    void aCellIsKeyedByPropertyValues() {
        Cell map = Cell.map("age", 0, 1f, 1, null);
        assertEquals(1f, map.get(states("age", 0)));
        assertNull(map.get(states("age", 1)), "unknown for that value");
        assertNull(map.get(states("age", 7)), "a value the cell does not know");
        Cell table = Cell.table(new String[] {"age", "open"}, new Object[][] {{0, 1}, {false, true}}, "a", "b", "c", "d");
        assertEquals("c", table.get(states("age", 1, "open", (byte) 0)), "a bit reads as a boolean, the last property fastest");
        assertEquals("b", table.get(states("age", 0, "open", (byte) 1)));
    }

    @Test
    void aSchemaReadsBackAsItsPropertiesAndValues() {
        String schema = BlockSchema.of(List.of(states("age", 0, "open", (byte) 0, "wood", "oak"), states("age", 1, "open", (byte) 1, "wood", "spruce"),
            states("age", 2, "open", (byte) 0, "wood", "oak")));
        assertEquals("age=0..2,open=false|true,wood='oak'|'spruce'", schema);
        BlockSchema.Parsed parsed = BlockSchema.parse(schema);
        assertArrayEquals(new String[] {"age", "open", "wood"}, parsed.properties());
        assertArrayEquals(new Object[][] {{0, 1, 2}, {false, true}, {"oak", "spruce"}}, parsed.domains());
        assertEquals("level=1|3,name='1'", BlockSchema.of(List.of(states("level", 1, "name", "1"), states("level", 3, "name", "1"))),
            "two ints are no range; a string never reads like a number");
        assertEquals(0, BlockSchema.parse("").properties().length);
    }

    @Test
    void anEntryCoversTheStatesOfItsSchemaOnly() {
        VanillaVersion.BlockEntry entry = new VanillaVersion.BlockEntry("age=0..2,open=false|true", new Object[BlockBehaviour.keys().size()]);
        assertTrue(entry.covers(states("age", 2, "open", (byte) 1)));
        assertFalse(entry.covers(states("age", 3, "open", (byte) 1)), "a value the version does not have");
        assertFalse(entry.covers(states("age", 2)), "a property less");
        assertFalse(entry.covers(states("age", 2, "open", (byte) 1, "lit", (byte) 0)), "a property more");
    }

    @Test
    void aVersionSharesWhatDidNotChange() {
        VanillaBehaviour.Builder behaviour = VanillaBehaviour.builder();
        VanillaVersionBuilder first = behaviour.version(V1);
        first.everyBlock()[VanillaVersion.index(BlockBehaviour.FRICTION)] = 0.6f;
        first.block("a:x", "");
        first.block("a:y", "")[VanillaVersion.index(BlockBehaviour.FRICTION)] = 0.9f;
        first.blockSources("a:y")[VanillaVersion.index(BlockBehaviour.FRICTION)] = new Capture(Provenance.Source.SCRIPT_API, "s", V1);
        VanillaVersionBuilder second = behaviour.version(V2);
        second.everyBlock()[VanillaVersion.index(BlockBehaviour.FRICTION)] = 0.6f;
        second.change("a:y")[VanillaVersion.index(BlockBehaviour.DESTROY_TIME)] = 1f;
        second.block("a:y", "");
        VanillaBehaviour built = behaviour.build();
        VanillaVersion one = built.versions().get(0);
        VanillaVersion two = built.versions().get(1);
        assertSame(one.block("a:x"), two.block("a:x"), "already 0.6: everyBlock keeps the entry shared");
        assertNotSame(one.block("a:y"), two.block("a:y"));
        assertEquals(0.6f, VanillaVersion.value(two.block("a:y"), BlockBehaviour.FRICTION, NbtMap.EMPTY), "declared again from the defaults");
        assertNull(VanillaVersion.value(two.block("a:y"), BlockBehaviour.DESTROY_TIME, NbtMap.EMPTY));
        assertEquals(0.9f, VanillaVersion.value(one.block("a:y"), BlockBehaviour.FRICTION, NbtMap.EMPTY), "the version before is not changed");
        assertEquals("s", one.capture("a:y", BlockBehaviour.FRICTION, NbtMap.EMPTY).reference());
        assertNull(two.capture("a:y", BlockBehaviour.FRICTION, NbtMap.EMPTY), "a block declared again drops its own captures");
    }

    @Test
    void aVersionWithoutDataUsesTheNewestOlderOne() {
        VanillaBehaviour.Builder behaviour = VanillaBehaviour.builder();
        behaviour.version(V1);
        behaviour.version(V2);
        VanillaBehaviour built = behaviour.build();
        assertSame(built.versions().get(1), built.version(DataVersion.preview("2.5", 25)));
        assertSame(built.versions().get(0), built.version(DataVersion.of("1.0", 99)), "by name first");
        assertNull(built.version(DataVersion.of("0.5", 5)));
    }

    @Test
    void theBundledVersionsShareTheBlocksThatDidNotChange() {
        List<VanillaVersion> versions = VanillaBehaviour.bundled().versions();
        Map<String, VanillaVersion.BlockEntry> oldest = versions.get(0).blocks;
        long shared = versions.get(1).blocks.entrySet().stream().filter(e -> e.getValue() == oldest.get(e.getKey())).count();
        assertTrue(shared > oldest.size() / 2, shared + " of " + oldest.size() + " blocks shared by the two oldest versions");
    }
}
