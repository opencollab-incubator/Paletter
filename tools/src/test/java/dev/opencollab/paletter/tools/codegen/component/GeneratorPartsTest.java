package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small parts the component generator is made of: names, windows, captures, defaults and the model's notation. */
class GeneratorPartsTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void javaNamesFollowTheModelsWords() {
        assertEquals("FoodItem", JavaNames.pascal("food_item"));
        assertEquals("foodItem", JavaNames.camel("food-item"));
        assertEquals("FIRE_RESISTANT", JavaNames.constant("minecraft:fireResistant"));
        assertEquals("_2D", JavaNames.constant("2d"));
        JavaNames.javaName("value", "x");
        assertThrows(GenerationException.class, () -> JavaNames.javaName("class", "x"));
        assertThrows(GenerationException.class, () -> JavaNames.javaName("a-b", "x"));
    }

    @Test
    void oneWindowOfAMappingHoldsForEveryProtocol() {
        assertEquals(List.of(), Windows.windows("item:a", null));
        List<Window> windows = Windows.windows("item:a", json("{\"windows\": [{\"protocols\": [100], \"status\": \"sent\"}]}"));
        assertEquals(1, windows.size());
        assertFalse(windows.getFirst().bounded(), "the oldest window also holds below its capture, the newest above");
        assertThrows(GenerationException.class, () -> Windows.windows("item:a", json("{\"status\": \"sent\"}")));
    }

    @Test
    void rejectedIsTheTrailingRunOfRejectingWindows() {
        Comp c = new Comp();
        c.id = "item:a";
        c.windows.add(new Window(Integer.MIN_VALUE, 10, json("{\"status\": \"sent\"}")));
        c.windows.add(new Window(10, 20, json("{\"status\": \"rejected\"}")));
        c.windows.add(new Window(20, Integer.MAX_VALUE, json("{\"status\": \"rejected\"}")));
        Window rejected = Windows.rejected(c);
        assertEquals(10, rejected.since());
        assertEquals(Integer.MAX_VALUE, rejected.until());
        assertNull(Windows.serverOnly(c));
    }

    @Test
    void capturesMustAgreeOnTheValuesHandCodeUses() {
        JsonObject same = json("{\"captures\": [{\"firstCustomBlockId\": 5}, {\"firstCustomBlockId\": 5}]}");
        assertEquals(5, Captures.sameInEveryCapture(same, "firstCustomBlockId"));
        assertNull(Captures.sameInEveryCapture(same, "blockItemIdPlusBlockId"));
        JsonObject differ = json("{\"captures\": [{\"firstCustomBlockId\": 5}, {\"firstCustomBlockId\": 6}]}");
        assertThrows(GenerationException.class, () -> Captures.sameInEveryCapture(differ, "firstCustomBlockId"));
    }

    @Test
    void bdsVersionIsTheNewestReleaseCapture() {
        JsonObject mappings = json("{\"captures\": [{\"bdsVersion\": \"1.0\"}, {\"bdsVersion\": \"1.1\"}, {\"bdsVersion\": \"1.2\", \"preview\": \"p\"}]}");
        assertEquals("1.1", Captures.bdsVersion(mappings));
        assertEquals("", Captures.bdsVersion(new JsonObject()));
    }

    @Test
    void aDefaultFitsOnlyTheTypeItIsOf() {
        assertTrue(Defaults.fits(new Scalar(ScalarKind.INT), json("{\"v\": 1}").get("v")));
        assertFalse(Defaults.fits(new Scalar(ScalarKind.BOOL), json("{\"v\": 1}").get("v")));
        assertFalse(Defaults.fits(new Scalar(ScalarKind.STRING), json("{\"v\": 1}").get("v")));
    }

    @Test
    void literalsOfTheSameNumberAndUnitAreTheSame() {
        assertTrue(Defaults.sameLiteral("1 (int)", "1.0 (int)"));
        assertFalse(Defaults.sameLiteral("1 (int)", "2 (int)"));
        assertFalse(Defaults.sameLiteral("1 (int)", "1 (float)"));
    }

    @Test
    void typesAndPathsOfTheModelsNotation() {
        JsonObject type = ModelFields.parseType("map<array<string>>");
        assertEquals("map<array<string>>", ModelFields.typeString(type));
        assertEquals("a.b", ModelFields.parent("a.b.c"));
        assertEquals("", ModelFields.parent("a"));
        assertEquals("c", ModelFields.token("a.b.c"));
        assertEquals("", ModelFields.text(json("{\"doc\": null}"), "doc"));
    }
}
