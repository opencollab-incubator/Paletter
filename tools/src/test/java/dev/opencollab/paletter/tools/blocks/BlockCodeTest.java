package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block definition generator on three small versions: what the base and the change classes say, that the generated
 * code, compiled and run, gives every palette again, that a retired version folds into the base, and that palettes the
 * definitions cannot say (not a cartesian product, no unique property order, blocks out of name hash order) fail.
 */
class BlockCodeTest {
    private static final StateValues FACING = StateValues.ints("facing", 0, 3);
    private static final StateValues OPEN = StateValues.bool("open_bit");
    private static final StateValues HINGE = StateValues.bool("hinge_bit");
    private static final StateValues GROWTH = StateValues.ints("growth", 0, 2);
    private static final StateValues COLOUR = StateValues.strings("test:colour", "red", "blue");
    private static final StateValues OTHER_COLOUR = StateValues.strings("colour", "green", "red");

    /** 1.1: a, door (default facing 2), crop. 1.2: crop gone, new, door gains a hinge. 1.3: the door's order turns, new's default moves. */
    static Map<String, BlockDefinitions.Palette> palettes() {
        BlockDefinitions v1 = new BlockDefinitions();
        v1.block("test:a");
        v1.block("test:door", FACING, OPEN).defaultState(2, false);
        v1.block("test:crop", GROWTH);
        BlockDefinitions v2 = v1.copy();
        v2.remove("test:crop");
        v2.block("test:new", COLOUR, OTHER_COLOUR).defaultState("blue", "green");
        v2.addProperties("test:door", 1, HINGE);
        BlockDefinitions v3 = v2.copy();
        v3.redefine("test:door", OPEN, HINGE, FACING).defaultState(false, false, 2);
        v3.defaultState("test:new", "red", "red");
        Map<String, BlockDefinitions.Palette> palettes = new LinkedHashMap<>();
        palettes.put("1.1", v1.derive());
        palettes.put("1.2", v2.derive());
        palettes.put("1.3", v3.derive());
        return palettes;
    }

    private static Map<String, String> generate(Map<String, BlockDefinitions.Palette> palettes) {
        return BlockCode.generate(BlockModel.versions(palettes), Map.of("1.2", "BDS 1.2.0"), "a test");
    }

    private static String file(Map<String, String> files, String name) {
        return files.get(BlockCode.DIRECTORY + "/" + name + ".java");
    }

    @Test
    void theBaseHasOneLinePerBlockAndTheValuesAreShared() {
        Map<String, String> files = generate(palettes());
        String base = file(files, "BlockBase");
        assertTrue(base.startsWith(BlockCode.GENERATOR + " from a test."), base);
        assertTrue(base.contains("v.block(\"test:a\");"), base);
        assertTrue(base.contains("v.block(\"test:door\", FACING_0_3, OPEN_BIT).defaultState(2, false);"), base);
        assertTrue(base.contains("v.block(\"test:crop\", GROWTH_0_2);"), base);
        String values = file(files, "BlockValues");
        assertTrue(values.contains("static final StateValues FACING_0_3 = StateValues.ints(\"facing\", 0, 3);"), values);
        assertTrue(values.contains("static final StateValues OPEN_BIT = StateValues.bool(\"open_bit\");"), values);
        // test:colour and colour strip to one name: the namespaced one keeps its namespace
        assertTrue(values.contains("TEST_COLOUR = StateValues.strings(\"test:colour\", \"red\", \"blue\");"), values);
        assertTrue(values.contains(" COLOUR = StateValues.strings(\"colour\", \"green\", \"red\");"), values);
        assertFalse(values.contains("BlockValues."), values);
    }

    @Test
    void aVersionsClassHasItsChangesWithTheCaptureTheyComeFrom() {
        Map<String, String> files = generate(palettes());
        String changes = file(files, "BlockChanges1_2");
        assertTrue(changes.contains("// removed: BDS 1.2.0\n        v.remove(\"test:crop\");"), changes);
        assertTrue(changes.contains("// new: BDS 1.2.0\n        v.block(\"test:new\", TEST_COLOUR, COLOUR).defaultState(\"blue\", \"green\");"), changes);
        assertTrue(changes.contains("// gains hinge_bit at position 1 of its order: BDS 1.2.0\n        v.addProperties(\"test:door\", 1, HINGE_BIT);"),
            changes);
        String turned = file(files, "BlockChanges1_3");
        assertTrue(turned.contains("// another property order: [facing, hinge_bit, open_bit] -> [open_bit, hinge_bit, facing]: no BDS capture in data/bds\n"
            + "        v.redefine(\"test:door\", OPEN_BIT, HINGE_BIT, FACING_0_3).defaultState(false, false, 2);"), turned);
        assertTrue(turned.contains("// default state [blue, green] -> [red, red]: no BDS capture in data/bds\n        v.defaultState(\"test:new\", \"red\", \"red\");"),
            turned);
        assertTrue(file(files, "BlockData").contains("BlockChanges1_3.load(v1_3);"));
    }

    @Test
    void theGeneratedCodeGivesEveryPaletteAgain(@TempDir Path directory) throws Exception {
        Map<String, BlockDefinitions.Palette> palettes = palettes();
        Map<String, BlockDefinitions> loaded = CompiledBlocks.load(generate(palettes), directory);
        assertEquals(List.copyOf(palettes.keySet()), List.copyOf(loaded.keySet()));
        palettes.forEach((version, palette) -> {
            BlockDefinitions.Palette derived = loaded.get(version).derive();
            assertEquals(palette.states(), derived.states(), version);
            assertEquals(palette.defaults(), derived.defaults(), version);
        });
    }

    @Test
    void retiringTheOldestVersionFoldsItIntoTheBase() {
        Map<String, BlockDefinitions.Palette> palettes = palettes();
        palettes.remove("1.1");
        Map<String, String> files = generate(palettes);
        assertFalse(files.containsKey(BlockCode.DIRECTORY + "/BlockChanges1_2.java"));
        String base = file(files, "BlockBase");
        assertTrue(base.contains("The vanilla blocks of 1.2, the oldest generated version, imported from BDS 1.2.0"), base);
        assertTrue(base.contains("v.block(\"test:door\", FACING_0_3, HINGE_BIT, OPEN_BIT).defaultState(2, false, false);"), base);
        assertFalse(base.contains("test:crop"), base);
    }

    @Test
    void theSamePalettesGiveTheSameFiles() {
        assertEquals(generate(palettes()), generate(palettes()));
    }

    @Test
    void whatTheDefinitionsCannotSayFails() {
        BlockDefinitions.Palette palette = palettes().get("1.1");
        List<NbtMap> states = palette.states();

        List<NbtMap> lacking = new ArrayList<>(states);
        lacking.remove(states.stream().filter(s -> BlockStates.name(s).equals("test:door")).reduce((a, b) -> b).orElseThrow());
        assertMessage("1.1: test:door is not the full cartesian product of its values", lacking, palette.defaults());

        List<NbtMap> shuffled = new ArrayList<>(states);
        int first = shuffled.indexOf(states.stream().filter(s -> BlockStates.name(s).equals("test:door")).findFirst().orElseThrow());
        // facing 0, 2, 1, 3 by first appearance: a valid value order, but not an odometer of it
        java.util.Collections.swap(shuffled, first + 2, first + 4);
        assertMessage("1.1: state", shuffled, palette.defaults());

        List<NbtMap> reordered = new ArrayList<>(states);
        String firstBlock = BlockStates.name(reordered.getFirst());
        List<NbtMap> run = reordered.stream().filter(s -> BlockStates.name(s).equals(firstBlock)).toList();
        reordered.removeAll(run);
        reordered.addAll(run);
        assertMessage("the blocks are not ordered by the unsigned FNV-1 64 hash of their identifiers", reordered, palette.defaults());

        List<NbtMap> single = new ArrayList<>(states);
        single.add(BlockStates.of("test:single", NbtMap.builder().putString("x", "a").putString("y", "b").build()));
        assertMessage("the property order of test:single is ambiguous", single, Map.of());
    }

    private static void assertMessage(String expected, List<NbtMap> states, Map<String, NbtMap> defaults) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BlockModel.definitions("1.1", new BlockDefinitions.Palette(states, defaults)));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
