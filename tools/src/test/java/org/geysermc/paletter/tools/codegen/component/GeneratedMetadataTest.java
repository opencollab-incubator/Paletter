package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import org.geysermc.paletter.annotation.ReplacedBy;
import org.geysermc.paletter.annotation.ServerOnly;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed generated components against the model: {@code @ServerOnly} and {@code @ReplacedBy} are exactly where the
 * model has a server-only window or a replacement (readable at runtime), every documented component has a Microsoft Learn
 * page, and the hand-written wire-only component that has a docs page links the same one.
 */
class GeneratedMetadataTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static ApiModel model() throws IOException {
        return ComponentGenerator.model(ComponentGenerator.read(ROOT.resolve("data/model"), Main.knownTransforms(ROOT), "1.0.0"));
    }

    @Test
    void serverOnlyAnnotationsMatchTheWindows() throws Exception {
        int annotated = 0;
        for (ApiModel.Comp c : model().components) {
            Class<?> type = Class.forName(c.kind.pkg() + "." + c.className);
            ServerOnly serverOnly = type.getAnnotation(ServerOnly.class);
            if (c.serverOnly == null) {
                assertNull(serverOnly, c.id);
            } else {
                annotated++;
                assertNotNull(serverOnly, c.id);
                assertEquals(c.serverOnly.since(), serverOnly.since(), c.id);
                assertEquals(c.serverOnly.until(), serverOnly.until(), c.id);
            }
        }
        assertTrue(annotated > 0, "the bundled model has components that are server only in older protocols");
    }

    @Test
    void replacedByAnnotationsMatchTheReplacements() throws Exception {
        ApiModel model = model();
        for (ApiModel.Comp c : model.components) {
            Class<?> type = Class.forName(c.kind.pkg() + "." + c.className);
            ReplacedBy replacedBy = type.getAnnotation(ReplacedBy.class);
            if (c.replacement == null) {
                assertNull(replacedBy, c.id);
                continue;
            }
            assertNotNull(type.getAnnotation(Deprecated.class), c.id);
            List<Class<?>> expected = new java.util.ArrayList<>();
            for (String id : c.replacement.by()) {
                ApiModel.Comp successor = model.component(id);
                if (successor != null && successor.kind == c.kind) {
                    expected.add(Class.forName(successor.kind.pkg() + "." + successor.className));
                }
            }
            if (expected.isEmpty()) {
                assertNull(replacedBy, c.id);
            } else {
                assertArrayEquals(expected.toArray(), replacedBy.value(), c.id);
            }
        }
    }

    @Test
    void everyReplacementNamesItsEvidenceAndASuccessorThatIsNotItself() throws IOException {
        ApiModel model = model();
        assertTrue(model.replacements.size() >= 8, model.replacements.toString());
        model.replacements.forEach((id, r) -> {
            assertTrue(!r.evidence().isBlank(), id);
            assertTrue(!r.by().isEmpty() && !r.by().contains(id), id);
        });
    }

    @Test
    void everyComponentPageHasALearnPage() throws IOException {
        ApiModel model = model();
        JsonObject components = ComponentGenerator.read(ROOT.resolve("data/model"), Main.knownTransforms(ROOT), "1.0.0").components();
        components.getAsJsonArray("components").forEach(e -> {
            JsonObject c = e.getAsJsonObject();
            if (c.get("category").getAsString().equals("component") || c.get("category").getAsString().equals("trait")) {
                assertNotNull(Docs.learnUrl(c.get("page").getAsString()), c.get("id").getAsString());
            }
        });
        assertEquals(3, model.lists.size());
        model.lists.values().forEach(page -> assertNotNull(Docs.learnUrl(page), page));
    }

    @Test
    void theHandWrittenRenderOffsetsLinksItsLearnPage() throws IOException {
        JsonObject components = ComponentGenerator.read(ROOT.resolve("data/model"), Main.knownTransforms(ROOT), "1.0.0").components();
        String page = components.getAsJsonArray("components").asList().stream().map(e -> e.getAsJsonObject())
            .filter(c -> c.get("id").getAsString().equals("item:minecraft:render_offsets")).findFirst().orElseThrow().get("page").getAsString();
        String source = Files.readString(ROOT.resolve("paletter/src/main/java/org/geysermc/paletter/item/component/RenderOffsets.java"),
            StandardCharsets.UTF_8);
        assertTrue(source.contains("@see <a href=\"" + Docs.learnUrl(page) + "\">"), "RenderOffsets links " + Docs.learnUrl(page));
    }
}
