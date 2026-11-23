package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block definition generator on small versions: what the base and the change classes say (what each version was
 * imported from, once; whether a state order is confirmed or inferred), that the generated code, compiled and run, gives
 * every palette again, that a retired version folds into the base, and the palettes it must refuse.
 */
class BlockCodeTest {
    private static final StateValues OPEN = BlockGeneratorFixture.OPEN;

    private static Map<String, BlockDefinitions.Palette> palettes() {
        return BlockGeneratorFixture.palettes();
    }

    private static BlockCode.Generated generated(Map<String, BlockDefinitions.Palette> palettes) {
        return BlockCode.generate(BlockModel.versions(palettes), Map.of("1.2", "BDS 1.2.0 */ System.exit(1); \\u000a"),
            Map.of("1.2", Set.of("test:new")), "a test");
    }

    private static Map<String, String> generate(Map<String, BlockDefinitions.Palette> palettes) {
        return generated(palettes).files();
    }

    /** A generated file, its Javadoc lines joined so a sentence can be found whole. */
    static String file(Map<String, String> files, String name) {
        return files.get(BlockCode.DIRECTORY + "/" + name + ".java").replace("\n * ", " ");
    }

    @Test
    void theBaseHasOneLinePerBlockAndTheValuesAreShared() {
        Map<String, String> files = generate(palettes());
        String base = file(files, "BlockBase");
        assertTrue(base.startsWith(BlockCode.GENERATOR + " from a test."), base);
        assertTrue(base.contains("v.block(\"test:a\");"), base);
        assertTrue(base.contains("// " + BlockCode.INFERRED + "\n        v.block(\"test:door\", FACING_0_3, OPEN_BIT).defaultState(2, false);"), base);
        assertTrue(base.contains("v.block(\"test:crop\", GROWTH_0_2);"), base);
        String values = file(files, "BlockValues");
        assertTrue(values.contains("static final StateValues FACING_0_3 = StateValues.ints(\"facing\", 0, 3);"), values);
        assertTrue(values.contains("static final StateValues OPEN_BIT = StateValues.bool(\"open_bit\");"), values);
        assertTrue(values.contains("static final StateValues ODD_4_2_9 = StateValues.intList(\"odd\", 4, 2, 9);"), values);
        // test:colour and colour strip to one name: the namespaced one keeps it; two lists of colour say their size and first value
        assertTrue(values.contains("TEST_COLOUR = StateValues.strings(\"test:colour\", \"red\", \"blue\");"), values);
        assertTrue(values.contains(" COLOUR_2_GREEN = StateValues.strings(\"colour\", \"green\", \"red\");"), values);
        assertTrue(values.contains(" COLOUR_2_WHITE = StateValues.strings(\"colour\", \"white\", \"black\");"), values);
        assertFalse(values.contains("BlockValues."), values);
    }

    @Test
    void aVersionsClassNamesItsImportOnceAndEachLineWhatItChanges() {
        Map<String, String> files = generate(palettes());
        String changes = file(files, "BlockChanges1_2");
        // What a source says cannot end the comment or start a Unicode escape
        assertTrue(changes.contains("imported from BDS 1.2.0 *<U+002F> System.exit(1); \\\\u000a."), changes);
        assertTrue(changes.contains("// removed\n        v.remove(\"test:crop\");"), changes);
        assertTrue(changes.contains("// new; " + BlockCode.CONFIRMED + "\n        v.block(\"test:new\", TEST_COLOUR, COLOUR_2_GREEN).defaultState(\"blue\", \"green\");"),
            changes);
        assertTrue(changes.contains("// gains hinge_bit at 1; " + BlockCode.INFERRED + "\n        v.addProperties(\"test:door\", 1, HINGE_BIT);"), changes);
        String turned = file(files, "BlockChanges1_3");
        assertTrue(turned.contains("imported from no record of its import."), turned);
        assertTrue(turned.contains("// another property order: [facing, hinge_bit, open_bit] -> [open_bit, hinge_bit, facing]; " + BlockCode.INFERRED + "\n"
            + "        v.redefine(\"test:door\", OPEN_BIT, HINGE_BIT, FACING_0_3).defaultState(false, false, 2);"), turned);
        assertTrue(turned.contains("// default state [blue, green] -> [red, red]\n        v.defaultState(\"test:new\", \"red\", \"red\");"), turned);
        assertTrue(turned.contains("// loses open_bit\n        v.removeProperty(\"test:odd\", \"open_bit\");"), turned);
        assertTrue(file(files, "BlockData").contains("BlockChanges1_3.load(v1_3);"));
        assertEquals(Map.of("1.1", List.of("test:door"), "1.2", List.of("test:door", "test:odd"), "1.3", List.of("test:door")),
            generated(palettes()).inferred());
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
    void manyBlocksAreSplitIntoParts(@TempDir Path directory) throws Exception {
        BlockDefinitions definitions = new BlockDefinitions();
        for (int i = 0; i < 1000; i++) {
            definitions.block("test:block_" + i, OPEN);
        }
        Map<String, BlockDefinitions.Palette> palettes = Map.of("1.1", definitions.derive());
        Map<String, String> files = generate(palettes);
        assertTrue(files.containsKey(BlockCode.DIRECTORY + "/BlockBasePart2.java"), files.keySet().toString());
        assertEquals(palettes.get("1.1").states(), CompiledBlocks.load(files, directory).get("1.1").derive().states());
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
    }

    private static void assertMessage(String expected, List<NbtMap> states, Map<String, NbtMap> defaults) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BlockModel.definitions("1.1", new BlockDefinitions.Palette(states, defaults)));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
