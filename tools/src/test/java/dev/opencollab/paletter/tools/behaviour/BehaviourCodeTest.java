package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.internal.behaviour.Capture;
import dev.opencollab.paletter.internal.behaviour.VanillaBehaviour;
import dev.opencollab.paletter.internal.behaviour.VanillaVersion;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dev.opencollab.paletter.tools.behaviour.BehaviourFixture.bundled;
import static dev.opencollab.paletter.tools.behaviour.BehaviourFixture.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour generator on {@link BehaviourFixture}: what the classes say, how a version's changes are commented and
 * flagged, that nothing from a source ends a comment, that a retired version folds into the base, that the output is the
 * same every time, and that the generated code, compiled and run, gives the store's values under {@link BdsFirst}.
 */
class BehaviourCodeTest {
    private static BehaviourCode.Result generate(BehaviourStore store) {
        List<BehaviourImport.Bundled> versions = bundled().stream().filter(b -> store.generated().contains(b.version().name())).toList();
        return BehaviourCode.generate(new BehaviourModel(store, versions), "a test store");
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
        assertTrue(Pattern.compile("\\.collisionShape\\(TABLE_FACING_OPEN_BIT_[0-9A-F]{6}\\)").matcher(base).find(), base);
        String tables = file(result, "BehaviourTables");
        assertTrue(tables.contains("new String[] {\"facing\", \"open_bit\"},") && tables.contains("new Object[][] {{0, 1}, {false, true}},"), tables);
        String shapes = file(result, "BehaviourShapes");
        assertTrue(shapes.contains("[0, 0, 0, 16, 8, 16] (in pixels)") && shapes.contains("BOX_0_0_0_16_8_16 = BlockShape.of("), shapes);
        String captures = file(result, "BehaviourCaptures");
        assertTrue(captures.contains("SCRIPT_API_1_1_DESTROY_TIME = new Capture(") && captures.contains("CLOUDBURST_DATA_1_1 = new Capture("), captures);
        assertFalse(file(result, "Changes").contains("import static"), "a file imports only the constants it uses");
    }

    @Test
    void aVersionsClassHasWhatChangedWithTheCaptureThatDecidedAndItsFlags() {
        BehaviourCode.Result result = generate(store());
        String changes = file(result, "BehaviourChanges1_2");
        assertTrue(changes.contains("// friction 0.6 -> 0.4: BDS Script API dump 1.2.0; CloudburstMC/Data bbbbbbb says otherwise\n"), changes);
        assertTrue(changes.contains("// collision_shape EMPTY -> BOX_0_0_0_16_8_16: CloudburstMC/Data bbbbbbb; SOURCE ONLY (no BDS capture); "
            + "undone in 1.3 by the same source: suspected dump artefact\n"), changes);
        assertTrue(changes.contains("// first coverage, attack_damage = 8 for every item (1 changed): CloudburstMC/Data bbbbbbb; only a third-party "
            + "dump has it\n        v.everyItem().attackDamage(8);"), changes);
        assertFalse(changes.contains("test:new"), "a block without values has no entry");
        String undo = file(result, "BehaviourChanges1_3");
        assertTrue(undo.contains("undoes the change of 1.2 by the same source: suspected dump artefact"), undo);
        assertTrue(undo.contains("// not in the palette of 1.3\n        v.remove(\"test:stone\");"), undo);
        assertTrue(undo.contains("// other states than in 1.2 (facing=0|1,open_bit=false|true): values from CloudburstMC/Data ddddddd\n"
            + "        v.block(\"test:door\", \"facing=0|1,hinge=false|true,open_bit=false|true\")"), undo);
        assertEquals(List.of("FIRST_COVERAGE 1.2 every item attack_damage", "SOURCE_ONLY 1.2 test:crop collision_shape",
                "SOURCE_ONLY 1.2 test:stone tint_method", "SOURCE_ONLY 1.3 test:crop collision_shape",
                "SUSPECTED_ARTEFACT 1.2 test:crop collision_shape", "SUSPECTED_ARTEFACT 1.3 test:crop collision_shape"),
            result.flags().stream().map(f -> f.kind() + " " + f.version() + " " + f.id() + " " + f.key().name()).sorted().toList());
        String section = result.reportSection();
        assertTrue(section.contains("| 1.2 | `block:destroy_time` | BDS Script API dump 1.1.0: 1.5 | CloudburstMC/Data bbbbbbb: 2.0 | 1: test:stone |"),
            section);
        assertTrue(section.contains("[0, 0, 0, 16, 8, 16]"), "shapes in the report by their boxes: " + section);
    }

    @Test
    void nothingASourceSaysEndsAComment() {
        BehaviourCode.Result result = generate(store());
        String changes = file(result, "BehaviourChanges1_2");
        assertTrue(changes.contains("// tint_method \"None\" -> \"x<U+000A>System.exit(1); // \\\\u000a System.exit(2);\": CloudburstMC/Data"), changes);
        // in code a string literal, which JavaPoet splits at the line break
        assertTrue(changes.contains(".tintMethod(\"x\\n\"\n                        + \"System.exit(1); // \\\\u000a System.exit(2);\")"), changes);
        assertEquals("a<U+000A>b \\\\u000a *<U+002F>", BehaviourComments.safe("a\nb \\u000a */"));
    }

    @Test
    void marksHoldForTheVersionsTheirCaptureDecidesIn() {
        BehaviourCode.Result result = generate(store());
        String marks = file(result, "BehaviourMarks");
        assertTrue(marks.contains("b.note(V1_1, V1_1, CLOUDBURST_DATA_1_1, BlockBehaviour.COLLISION_SHAPE, \"test:door\", \"shapeSource: corrected\");"), marks);
        assertTrue(marks.contains("b.dispute(V1_1, V1_1, CLOUDBURST_DATA_1_1, BlockBehaviour.COLLISION_SHAPE, \"test:door\", ALLAY_DATA_1_1,"), marks);
        // the Script API's destroy time of 1.1 kept in 1.2 and 1.3 against CloudburstMC/Data of 1.2
        assertTrue(marks.contains("b.dispute(V1_2, V1_2, SCRIPT_API_1_1_DESTROY_TIME, BlockBehaviour.DESTROY_TIME, \"test:stone\", CLOUDBURST_DATA_1_2, 2.0f);"),
            marks);
    }

    @Test
    void retiringTheOldestVersionFoldsItIntoTheBase() {
        BehaviourCode.Result result = generate(store().retire("1.1"));
        assertFalse(result.files().containsKey(BehaviourCode.DIRECTORY + "/BehaviourChanges1_2.java"));
        String base = file(result, "BehaviourBase");
        assertTrue(base.contains("The behaviour values of 1.2, the oldest generated version"), base);
        assertTrue(base.contains("v.block(\"test:stone\", \"\")") && base.contains(".friction(0.4f)"), base);
        assertTrue(result.files().containsKey(BehaviourCode.DIRECTORY + "/BehaviourChanges1_3.java"));
    }

    @Test
    void theSameStoreGivesTheSameFiles() {
        assertEquals(generate(store()).files(), generate(store()).files());
    }

    @Test
    void longStatementListsAreSplitIntoMethodsAndClasses() {
        List<com.palantir.javapoet.CodeBlock> statements = java.util.stream.IntStream.range(0, 1000)
            .mapToObj(i -> com.palantir.javapoet.CodeBlock.builder().addStatement("v.remove($S)", "x" + i).build()).toList();
        List<com.palantir.javapoet.TypeSpec> types = BehaviourCode.chunked("Name", "doc", statements, BehaviourSetters.CHANGES, "v");
        // 60 statements a method: 17 methods, 8 a class
        assertEquals(List.of("Name", "NamePart2", "NamePart3"), types.stream().map(com.palantir.javapoet.TypeSpec::name).toList());
        assertEquals(10, types.getFirst().methodSpecs().size(), "the constructor, 8 parts and load");
    }

    @Test
    void theGeneratedCodeGivesTheStoresValues(@TempDir Path classes) throws Exception {
        BehaviourStore store = store();
        VanillaBehaviour data = CompiledBehaviour.load(generate(store).files(), classes);
        for (BehaviourImport.Bundled palette : bundled()) {
            VanillaVersion values = data.version(palette.version());
            for (Map.Entry<Integer, NbtMap> state : palette.states().entrySet()) {
                String id = state.getValue().getString("name");
                NbtMap properties = state.getValue().getCompound("states");
                VanillaVersion.BlockEntry entry = values.block(id);
                for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                    BehaviourStore.Resolved expected = BdsFirst.block(store, key, state.getKey(), palette.version());
                    Object value = entry == null || !entry.covers(properties) ? null : VanillaVersion.value(entry, key, properties);
                    assertEquals(expected == null ? null : expected.value(), value, id + " " + key + " " + palette.version());
                    if (expected != null) {
                        Capture capture = values.capture(id, key, properties);
                        assertEquals(expected.capture().reference(), capture.reference(), id + " " + key);
                        assertEquals(expected.postProcessing(), data.note(values, capture, key, id, properties), id + " " + key);
                        assertEquals(expected.disputes().stream().map(d -> d.capture().reference() + "=" + d.value()).toList(),
                            data.disputes(values, capture, key, id, properties).stream().map(d -> d.capture().reference() + "=" + d.value()).toList());
                    }
                }
            }
            BehaviourStore.Resolved sword = store.item(ItemBehaviour.ATTACK_DAMAGE, "test:sword", palette.version());
            assertEquals(sword == null ? null : sword.value(), values.item("test:sword", ItemBehaviour.ATTACK_DAMAGE));
        }
    }
}
