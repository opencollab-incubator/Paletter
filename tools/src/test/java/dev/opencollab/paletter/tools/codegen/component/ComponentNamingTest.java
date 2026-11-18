package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Context;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple names are unique across the public API (the collision set is computed, never listed) and experiment toggles come
 * from what the captured servers call them.
 */
class ComponentNamingTest {
    private static final String ITEM_PKG = "dev/opencollab/paletter/item/component/";
    private static final String BLOCK_PKG = "dev/opencollab/paletter/block/component/";

    /** The synthetic model plus a block that is also called {@code widget}. */
    private static Inputs withBlockWidget(Context context) {
        Inputs base = ComponentGeneratorTest.inputs();
        JsonObject components = base.components();
        components.getAsJsonArray("components").add(ComponentGeneratorTest.json("""
            {"id": "block:test:widget", "kind": "block", "category": "component", "name": "test:widget", "status": "active",
             "doc": "A block widget.", "page": "", "experiments": [], "forms": [{"type": "object"}],
             "fields": [{"path": "size", "types": [{"type": "integer"}], "defaultValue": 1, "required": false, "enumValues": [], "examples": [],
                         "doc": "The size."}],
             "notes": []}"""));
        JsonObject mappings = base.mappings();
        mappings.getAsJsonObject("components").add("block:test:widget", ComponentGeneratorTest.json("""
            {"status": "sent", "wire": ["test:block_widget"], "protocols": [100, 200],
             "fields": {"size": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "int", "tags": [], "wire": "test:block_widget.size"}],
                                 "whenAbsent": {"test:block_widget.size": "1 (int)"}}}}"""));
        return new Inputs(components, base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since(), context);
    }

    private static Inputs withExperiments(Context context, String... names) {
        Inputs base = ComponentGeneratorTest.inputs();
        for (var component : base.components().getAsJsonArray("components")) {
            if (component.getAsJsonObject().get("id").getAsString().equals("item:test:gadget")) {
                var experiments = component.getAsJsonObject().getAsJsonArray("experiments");
                while (!experiments.isEmpty()) {
                    experiments.remove(0);
                }
                for (String name : names) {
                    experiments.add(name);
                }
            }
        }
        return new Inputs(base.components(), base.fixtures(), base.feedback(), base.mappings(), base.transforms(), base.api(), base.since(), context);
    }

    @Test
    void aNameBothAnItemAndABlockHaveGetsAPrefixEachAndOnlyThose() {
        Map<String, String> files = ComponentGenerator.generate(withBlockWidget(Context.NONE));
        assertTrue(files.containsKey(ITEM_PKG + "ItemWidget.java"), files.keySet().toString());
        assertTrue(files.containsKey(BLOCK_PKG + "BlockWidget.java"), files.keySet().toString());
        assertFalse(files.containsKey(ITEM_PKG + "Widget.java"));
        assertFalse(files.containsKey(BLOCK_PKG + "Widget.java"));
        assertTrue(files.containsKey(ITEM_PKG + "Gadget.java"), "a name only one has stays as it is");
        assertTrue(files.containsKey(BLOCK_PKG + "Slab.java"));
    }

    @Test
    void thePrefixFollowsIntoTheInterfaceTheEncodersAndTheirMethods() {
        Map<String, String> files = ComponentGenerator.generate(withBlockWidget(Context.NONE));
        String block = files.get("dev/opencollab/paletter/internal/component/BlockWire.java");
        assertTrue(block.contains("BlockWidget"), block);
        assertTrue(block.contains("case BlockWidget c -> out.put(\"test:block_widget.size\""), "the dispatch case carries the prefixed type name");
        String item = files.get("dev/opencollab/paletter/internal/component/ItemWire.java");
        assertTrue(item.contains("ItemWidget"), item);
        assertTrue(files.get(ITEM_PKG + "ItemWidget.java").contains("public record ItemWidget("));
    }

    @Test
    void aNameOfAPublicTypeThatIsNotGeneratedHereGetsAPrefixToo() {
        Map<String, String> files = ComponentGenerator.generate(ComponentGeneratorTest.inputs().withContext(new Context(Set.of("Gadget"), Map.of(), Map.of())));
        assertTrue(files.containsKey(ITEM_PKG + "ItemGadget.java"), files.keySet().toString());
        assertFalse(files.containsKey(ITEM_PKG + "Gadget.java"));
        assertTrue(files.containsKey(ITEM_PKG + "Widget.java"));
    }

    @Test
    void aNestedTypeThatShadowsAPublicTypeIsAnError() {
        // Widget has a nested Entry record
        Inputs in = ComponentGeneratorTest.inputs().withContext(new Context(Set.of("Entry"), Map.of(), Map.of()));
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generate(in));
        assertTrue(e.getMessage().contains("nested type Entry") && e.getMessage().contains("typeNames"), e.getMessage());
    }

    @Test
    void anExperimentThatTheCapturesNameBecomesBedrockExperimentNotExperimental() {
        Inputs in = withExperiments(new Context(Set.of(), Map.of("Test Toggle", "test_toggle"), Map.of()), "Test Toggle");
        String gadget = ComponentGenerator.generate(in).get(ITEM_PKG + "Gadget.java");
        assertTrue(gadget.contains("@BedrockExperiment(\"test_toggle\")"), gadget);
        assertFalse(gadget.contains("@Experimental"), "Experimental means the API is unstable, nothing else");
        assertFalse(gadget.contains("Unverified: the creator docs name experiment"), "a named toggle needs no note about the name");
    }

    @Test
    void anExperimentTheCapturesDoNotNameIsOnlyInTheJavadoc() {
        // "Holiday Creator Features" is in no capture: nothing is guessed
        Inputs in = withExperiments(new Context(Set.of(), Map.of("Test Toggle", "test_toggle"), Map.of()), "Holiday Creator Features");
        String gadget = ComponentGenerator.generate(in).get(ITEM_PKG + "Gadget.java");
        assertFalse(gadget.contains("@BedrockExperiment"), gadget);
        assertTrue(gadget.contains("The creator docs name the experiment Holiday Creator Features for it (in some format versions); unverified against BDS."), gadget);
        assertTrue(gadget.contains("Unverified: the creator docs name experiment \"Holiday Creator Features\", which is in no capture."), gadget);
        assertFalse(gadget.contains("requires"), "the docs are not claimed to be enforced");
    }

    @Test
    void aGatingCaptureMakesTheJavadocBdsVerifiedForTheBuildsItCovers() {
        Context context = new Context(Set.of(), Map.of("Test Toggle", "test_toggle"), Map.of("item:test:gadget", List.of(
            new Gating.Result("1.26.36.1", Gating.REJECTED, Gating.ACCEPTED), new Gating.Result("1.26.45.1", Gating.IGNORED, Gating.ACCEPTED),
            new Gating.Result("1.26.52.3", Gating.ACCEPTED, Gating.ACCEPTED), new Gating.Result("1.26.63.1", Gating.REJECTED, Gating.REJECTED))));
        String gadget = ComponentGenerator.generate(withExperiments(context, "Test Toggle")).get(ITEM_PKG + "Gadget.java");
        assertTrue(gadget.contains("BDS-verified: in a world without experiments BDS rejects or ignores it in 1.26.36.1, 1.26.45.1."), gadget);
        assertTrue(gadget.contains("BDS takes it without any experiment in 1.26.52.3: the docs' gating does not hold there."), gadget);
        assertTrue(gadget.contains("BDS rejects it even with the experiments on in 1.26.63.1."), gadget);
        assertFalse(gadget.contains("unverified against BDS"), gadget);
        assertTrue(gadget.contains("@BedrockExperiment(\"test_toggle\")"), gadget);
    }

    @Test
    void aGatingCaptureVerifiesAnExperimentNoCaptureNamesToo() {
        Context context = new Context(Set.of(), Map.of(), Map.of("item:test:gadget", List.of(new Gating.Result("1.26.70.24", Gating.REJECTED, Gating.ACCEPTED))));
        String gadget = ComponentGenerator.generate(withExperiments(context, "Holiday Creator Features")).get(ITEM_PKG + "Gadget.java");
        assertTrue(gadget.contains("BDS-verified: in a world without experiments BDS rejects it in 1.26.70.24."), gadget);
        assertFalse(gadget.contains("@BedrockExperiment"), "the toggle that enables it is still unknown");
    }

    @Test
    void severalNamedToggleIdsOfOneComponentFailInsteadOfDroppingOne() {
        Inputs in = withExperiments(new Context(Set.of(), Map.of("A", "toggle_a", "B", "toggle_b"), Map.of()), "A", "B");
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generate(in));
        assertTrue(e.getMessage().contains("toggle_a") && e.getMessage().contains("toggle_b"), e.getMessage());
    }

    @Test
    void reservedNamesAreTheTypesThatAreNotGeneratedByTheComponentGenerator(@TempDir Path root) throws IOException {
        Path palette = root.resolve("paletter/src/main/java/dev/opencollab/paletter/Palette.java");
        Files.createDirectories(palette.getParent());
        Files.writeString(palette, "package x;\npublic final class Palette {\n    public static class Nested {}\n}\nfinal class Helper {}\n"
            + "public sealed interface Shape permits Circle {}\npublic @interface Marker {}\n");
        write(root.resolve("paletter/src/main/java/dev/opencollab/paletter/internal/Hidden.java"));
        write(root.resolve("paletter/src/main/java/dev/opencollab/paletter/item/component/WireOnlyItemComponent.java"));
        write(root.resolve("paletter/src/generated/java/dev/opencollab/paletter/vanilla/BedrockBlocks.java"));
        Path generated = root.resolve("paletter/src/generated/java/dev/opencollab/paletter/item/component/Generated.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "// " + Main.GENERATOR + " from data/model." + System.lineSeparator() + "package x;");
        write(root.resolve("paletter/src/generated/java/dev/opencollab/paletter/internal/component/ItemWire.java"));
        Set<String> reserved = Main.context(root).reserved();
        assertEquals(Set.of("Palette", "Shape", "Marker", "WireOnlyItemComponent", "BedrockBlocks"), reserved);
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String name = file.getFileName().toString().replace(".java", "");
        Files.writeString(file, "package x;\npublic final class " + name + " {}\n");
    }

    private static void capture(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experiments.json"), json);
    }
}
