package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed capture of the probe on BDS 1.26.63.1: what it says, and that it is what its console gives. */
class BehaviourProbeCaptureTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final Path DIRECTORY = ROOT.resolve("data/bds/1.26.63.1").resolve(BehaviourProbe.DIRECTORY);

    private static JsonObject committed() throws IOException {
        return JsonParser.parseString(Files.readString(DIRECTORY.resolve(BehaviourProbe.CAPTURE))).getAsJsonObject();
    }

    @Test
    void theCaptureIsWhatTheCommittedConsoleGives() throws IOException {
        ProbeLog log = ProbeLog.parse(Files.readAllLines(DIRECTORY.resolve(BehaviourProbe.LOG)));
        assertEquals("1.26.63.1", log.version());
        assertEquals("53340801", log.build());
        assertEquals(committed(), BehaviourProbe.measure(log, "1.26.60"), "run behaviour-probe --from-log again");
        assertEquals(Json.pretty(committed()), Files.readString(DIRECTORY.resolve(BehaviourProbe.CAPTURE)), "written the way the tool writes it");
    }

    @Test
    void theMeasuredDefaultsOfACustomBlock() throws IOException {
        JsonArray defaults = committed().getAsJsonArray("defaults");
        java.util.Map<String, Double> values = new java.util.TreeMap<>();
        for (JsonElement entry : defaults) {
            JsonObject object = entry.getAsJsonObject();
            if (object.get("value").isJsonPrimitive()) {
                values.put(object.get("key").getAsString(), object.get("value").getAsDouble());
            }
            assertTrue(object.get("method").getAsString().length() > 40, "every value says how it was measured");
        }
        assertEquals(java.util.Map.of("block:friction", 0.6, "block:explosion_resistance", 0.0, "block:light_dampening", 15.0,
            "block:light_emission", 0.0, "block:burn_odds", 0.0, "block:flame_odds", 0.0), values);
        assertEquals("[[0.0,0.0,0.0,1.0,1.0,1.0]]", defaults.asList().stream().map(JsonElement::getAsJsonObject)
            .filter(entry -> entry.get("key").getAsString().equals("block:collision_shape")).findFirst().orElseThrow().get("value").toString());
        assertEquals(7, defaults.size(), "the selection box and the destroy time were not measured");
    }

    @Test
    void everyDoorIsMeasuredInEveryStateAndAllAreThe0_1825PanelOfTheWoodenDoor() throws IOException {
        JsonObject doors = committed().getAsJsonObject("doors");
        assertEquals(22, doors.size());
        JsonElement wooden = doors.get("minecraft:wooden_door");
        assertEquals(32, wooden.getAsJsonArray().size(), "4 directions, open, hinge, upper half");
        for (var door : doors.entrySet()) {
            assertEquals(wooden, door.getValue(), door.getKey() + " is the same as the wooden door");
            for (JsonElement panel : door.getValue().getAsJsonArray()) {
                double thickness = panel.getAsJsonObject().get("max").getAsDouble() - panel.getAsJsonObject().get("min").getAsDouble();
                assertEquals(0.1825, thickness, 1e-9);
            }
        }
    }

    @Test
    void theBuildIsThereForTheNextReader() throws IOException {
        JsonObject bds = committed().getAsJsonObject("bds");
        assertEquals("1.26.63.1", bds.get("version").getAsString());
        assertEquals("53340801", bds.get("build").getAsString());
        assertTrue(committed().get("generated").getAsString().contains("Do not edit"));
    }
}
