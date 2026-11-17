package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every hand transform is backed by BDS: the inference found accepted variants of a captured build that set what it
 * compiles ({@code transforms.<path>.verified} in {@code mappings.json}), so the conformance suite compares its output
 * with what BDS sent. A transform no capture covers would only be checked by its own unit test. The table names methods
 * the runtime has, and a key covers the fields below it.
 */
class HandTransformsTest {
    @Test
    void everyHandTransformIsCoveredByACapturedVariant() throws IOException {
        JsonObject components = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("mappings.json")))
            .getAsJsonObject().getAsJsonObject("components");
        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, String> transform : HandTransforms.BY_PATH.entrySet()) {
            // "<component>:<path>": the component is the longest name in mappings.json the key starts with
            String key = transform.getKey();
            String component = components.keySet().stream().filter(name -> key.startsWith(name + ":"))
                .max((a, b) -> Integer.compare(a.length(), b.length())).orElse(null);
            JsonObject transforms = component == null ? null : components.getAsJsonObject(component).getAsJsonObject("transforms");
            String path = component == null ? null : key.substring(component.length() + 1);
            JsonObject entry = transforms == null || !transforms.has(path) ? null : transforms.getAsJsonObject(path);
            if (entry == null || !entry.has("verified") || entry.getAsJsonObject("verified").get("variants").getAsInt() == 0) {
                uncovered.add(key + " (" + transform.getValue() + ")");
            }
        }
        assertEquals(List.of(), uncovered, "hand transforms without an accepted captured variant (see coverage.md)");
    }

    /** Table entries of Transforms that no capture backs: hand-entered, listed here so that none is mistaken for BDS data. */
    private static final Set<String> UNVERIFIED_USE_ANIMATIONS = Set.of("block", "bow", "camera", "crossbow", "spyglass", "brush");

    private static final Set<String> UNVERIFIED_COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "gold",
        "gray", "dark_gray", "blue", "green", "aqua", "light_purple", "yellow", "white", "minecoin_gold", "red", "material_quartz",
        "material_iron", "material_netherite", "material_redstone", "material_copper", "material_gold", "material_emerald",
        "material_diamond", "material_lapis", "material_amethyst", "material_resin");

    /**
     * The tables of Transforms are typed by hand: every entry must equal what a capture shows, or be listed as unverified
     * above. The evidence is what {@code mappings.json} records: the table rule the inference fitted for use_animation,
     * and the captured name to code pairs of hover_text_color. A recapture that covers an unverified entry fails this test
     * until the entry leaves the list.
     */
    @Test
    void everyTableEntryIsCapturedOrListedAsUnverified() throws Exception {
        JsonObject components = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("mappings.json")))
            .getAsJsonObject().getAsJsonObject("components");
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");

        Map<String, String> animations = new java.util.HashMap<>();
        Matcher table = Pattern.compile("\"table\":\\{([^}]*)\\}").matcher(components.get("item:minecraft:use_animation").toString());
        while (table.find()) {
            Matcher pair = Pattern.compile("\"(\\w+)\":\"(\\d+)\"").matcher(table.group(1));
            while (pair.find()) {
                animations.put(pair.group(1), pair.group(2));
            }
        }
        checkTable(table(transforms, "USE_ANIMATIONS"), animations, UNVERIFIED_USE_ANIMATIONS);

        Map<String, String> colors = new java.util.HashMap<>();
        Matcher color = Pattern.compile("\\\\\"(\\w+)\\\\\" -> §(.) \\(string\\)").matcher(components.get("item:minecraft:hover_text_color").toString());
        while (color.find()) {
            colors.put(color.group(1), color.group(2));
        }
        checkTable(table(transforms, "FORMATTING"), colors, UNVERIFIED_COLORS);
        assertTrue(colors.size() >= 1 && animations.size() >= 4, "the evidence of mappings.json could not be read");
    }

    private static Map<String, Object> table(Class<?> transforms, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = transforms.getDeclaredField(name);
        field.setAccessible(true);
        Map<String, Object> table = new java.util.HashMap<>();
        ((Map<?, ?>) field.get(null)).forEach((k, v) -> table.put((String) k, v));
        return table;
    }

    private static void checkTable(Map<String, Object> entries, Map<String, String> captured, Set<String> unverified) {
        Set<String> uncaptured = new TreeSet<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            String code = captured.get(entry.getKey());
            if (code == null) {
                uncaptured.add(entry.getKey());
            } else {
                assertEquals(code, String.valueOf(entry.getValue()), entry.getKey() + " differs from the capture");
            }
        }
        assertEquals(new TreeSet<>(unverified), uncaptured, "entries without a capture must be listed as unverified, and only those");
    }

    /** Hand transforms of one field whose value the inference explains now, and why they still stand. */
    private static final Map<String, String> EXPLAINED_BUT_KEPT = Map.of("item:minecraft:use_animation:value",
        "the fitted table has only the captured names (none, eat, drink, spear); the others are the hand table's, see everyTableEntryIsCapturedOrListedAsUnverified");

    /**
     * A hand transform of a single field exists because no rule explains the field. When the inference fits a rule for
     * it (status {@code inferred} in the newest window), the transform and its Transforms method are to be deleted, unless
     * it is listed above with the reason it still covers more than the rule.
     */
    @Test
    void aHandTransformOfOneFieldIsNeededBecauseNoRuleExplainsIt() throws IOException {
        JsonObject components = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("mappings.json")))
            .getAsJsonObject().getAsJsonObject("components");
        List<String> explained = new ArrayList<>();
        for (String key : HandTransforms.BY_PATH.keySet()) {
            String component = components.keySet().stream().filter(name -> key.startsWith(name + ":"))
                .max((a, b) -> Integer.compare(a.length(), b.length())).orElseThrow();
            String path = key.substring(component.length() + 1);
            JsonObject newest = org.geysermc.paletter.tools.model.MappingWindows.newest(components.getAsJsonObject(component));
            JsonObject fields = newest.has("fields") ? newest.getAsJsonObject("fields") : new JsonObject();
            List<String> covered = fields.keySet().stream().filter(p -> !path.isEmpty() && (p.equals(path) || p.startsWith(path + ".") || p.startsWith(path + "["))).toList();
            if (covered.size() == 1 && fields.getAsJsonObject(covered.getFirst()).get("status").getAsString().equals("inferred")
                && !EXPLAINED_BUT_KEPT.containsKey(key)) {
                explained.add(key);
            }
        }
        assertEquals(List.of(), explained, "the inference explains these fields now: delete the hand transform");
        EXPLAINED_BUT_KEPT.keySet().forEach(key -> assertTrue(HandTransforms.BY_PATH.containsKey(key), key + " is no hand transform any more"));
    }

    @Test
    void everyTransformIsAPublicStaticMethodOfTransforms() throws ClassNotFoundException {
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");
        for (Map.Entry<String, String> e : HandTransforms.BY_PATH.entrySet()) {
            boolean exists = Arrays.stream(transforms.getMethods()).filter(m -> m.getName().equals(e.getValue()))
                .map(Method::getModifiers).anyMatch(Modifier::isStatic);
            assertTrue(exists, e.getKey() + " names Transforms." + e.getValue() + ", which is not a public static method");
        }
    }

    /** A key covers the fields below it ({@code MappingInference} looks transforms up this way). */
    @Test
    void aKeyCoversItsPathAndEverythingBelow() {
        Map<String, String> table = Map.of("c:a", "x", "c:[]", "y", "d:", "z");
        assertEquals("c:a", HandTransforms.covering(table, "c", "a"));
        assertEquals("c:a", HandTransforms.covering(table, "c", "a.b[].c"));
        assertEquals("c:[]", HandTransforms.covering(table, "c", "[].size"));
        assertEquals("d:", HandTransforms.covering(table, "d", "anything"));
        assertEquals("d:", HandTransforms.covering(table, "d", ""));
        assertNull(HandTransforms.covering(table, "c", "b"));
        assertNull(HandTransforms.covering(table, "c", ""));
    }
}
