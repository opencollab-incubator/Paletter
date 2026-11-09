package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules from the probe's observations to values, on observations made up to be what BDS printed. */
class BehaviourProbeTest {
    private static final String[] SIDES = {"+x", "-x", "+z", "-z"};

    /** A console log of a complete probe run in which the world behaves like BDS 1.26.63.1 (numbers from that run). */
    private static List<String> console(double plainFriction, int plainExplosion) {
        return console(plainFriction, plainExplosion, 0.1);
    }

    private static List<String> console(double plainFriction, int plainExplosion, double friction90) {
        List<String> lines = new ArrayList<>();
        lines.add("[2026-11-09 20:30:06:870 INFO] Version: 1.26.63.1");
        lines.add("[2026-11-09 20:30:06:871 INFO] Build ID: 53340801");
        lines.add("[2026-11-09 20:30:07:000 INFO] Something else");
        for (String floor : List.of("minecraft:stone", "minecraft:ice", "probe:plain", "probe:friction_0", "probe:friction_40", "probe:friction_90")) {
            double friction = switch (floor) {
                case "minecraft:ice" -> 0.98;
                case "probe:plain" -> plainFriction;
                case "probe:friction_0" -> 1;
                case "probe:friction_40" -> 0.6;
                case "probe:friction_90" -> friction90;
                default -> 0.6;
            };
            StringBuilder track = new StringBuilder("[");
            double velocity = 0.5;
            for (int tick = 0; tick < 12; tick++) {
                track.append(tick == 0 ? "" : ",").append("[").append(tick).append(",").append(velocity).append(",0,200]");
                velocity *= friction * 0.91;
            }
            lines.add(observation("friction", "{\"block\":\"" + floor + "\",\"entity\":" + track + "]}"));
        }
        lines.add(observation("tunnel", "{\"block\":\"minecraft:stone\",\"behind\":0,\"inside\":0}"));
        lines.add(observation("tunnel", "{\"block\":\"probe:plain\",\"behind\":0,\"inside\":0}"));
        lines.add(observation("tunnel", "{\"block\":\"probe:light_dampening_0\",\"behind\":13,\"inside\":14}"));
        lines.add(observation("tunnel", "{\"block\":\"probe:light_dampening_14\",\"behind\":0,\"inside\":1}"));
        lines.add(observation("light", "{\"kind\":\"emit\",\"block\":\"minecraft:stone\",\"sky\":0,\"light\":0,\"self\":0}"));
        lines.add(observation("light", "{\"kind\":\"emit\",\"block\":\"probe:plain\",\"sky\":0,\"light\":0,\"self\":0}"));
        lines.add(observation("light", "{\"kind\":\"emit\",\"block\":\"probe:light_emission_15\",\"sky\":0,\"light\":14,\"self\":0}"));
        lines.add(observation("explosion", "{\"minecraft:dirt\":{\"6\":[36,48],\"7\":[0,48]},\"minecraft:sand\":{\"6\":[31,48],\"7\":[0,48]},"
            + "\"probe:plain\":{\"6\":[39,48],\"7\":[" + plainExplosion + ",48]},\"probe:explosion_resistance_0\":{\"6\":[35,48],\"7\":[14,48]},"
            + "\"probe:destructible_by_explosion\":{\"6\":[42,48],\"7\":[14,48]},\"probe:explosion_resistance_3\":{\"6\":[34,48],\"7\":[0,48]}}"));
        lines.add(observation("drop", "{\"block\":\"minecraft:stone\",\"blockY\":205,\"y\":206}"));
        lines.add(observation("drop", "{\"block\":\"probe:plain\",\"blockY\":205,\"y\":206}"));
        lines.add(observation("drop", "{\"block\":\"probe:no_collision\",\"blockY\":205,\"y\":159.8}"));
        lines.add(observation("flammable", "{\"minecraft:oak_planks\":{\"remaining\":0,\"total\":12},\"probe:plain\":{\"remaining\":12,\"total\":12},"
            + "\"probe:flammable\":{\"remaining\":0,\"total\":12},\"minecraft:stone\":{\"remaining\":12,\"total\":12}}"));
        for (String solid : List.of("minecraft:stone", "probe:plain")) {
            cell(lines, solid, "lower", null, new double[]{1.25, -0.25, 1.25, -0.25}, 200);
        }
        // A door closed towards the north: its panel is x 0.8175 to 1, bodies on the z axis pass
        for (String half : List.of("lower", "upper")) {
            int level = half.equals("upper") ? 201 : 200;
            cell(lines, "minecraft:wooden_door", half, "{\"minecraft:cardinal_direction\":\"north\",\"open_bit\":false,\"door_hinge_bit\":false,"
                + "\"upper_block_bit\":" + half.equals("upper") + ",\"direction\":2}", new double[]{1.25, 0.5675, -1.08, 2.08}, level);
        }
        lines.add("[2026-11-09 20:30:40:000 WARN] [Scripting] PROBE-DONE");
        return lines;
    }

    private static String observation(String tag, String json) {
        return "[2026-11-09 20:30:30:000 WARN] [Scripting] PROBE " + tag + " " + json;
    }

    private static void cell(List<String> lines, String block, String half, String state, double[] rel, int level) {
        for (int i = 0; i < SIDES.length; i++) {
            lines.add(observation("cell", "{\"block\":\"" + block + "\",\"half\":\"" + half + "\",\"state\":" + state + ",\"side\":\"" + SIDES[i]
                + "\",\"level\":" + level + ",\"rel\":" + rel[i] + ",\"y\":" + level + "}"));
        }
    }

    private static JsonObject measure(List<String> console) {
        return BehaviourProbe.measure(ProbeLog.parse(console), "1.26.60");
    }

    private static JsonObject defaultOf(JsonObject capture, String key) {
        for (var entry : capture.getAsJsonArray("defaults")) {
            if (entry.getAsJsonObject().get("key").getAsString().equals(key)) {
                return entry.getAsJsonObject();
            }
        }
        throw new AssertionError("no default " + key);
    }

    @Test
    void readsTheVersionTheBuildAndOnlyTheProbeLines() {
        ProbeLog log = ProbeLog.parse(console(0.6, 12));
        assertEquals("1.26.63.1", log.version());
        assertEquals("53340801", log.build());
        assertEquals(6, log.of("friction").size());
        assertTrue(log.extract().stream().noneMatch(line -> line.contains("Something else")));
        assertThrows(IllegalStateException.class, () -> log.of("nothing"));
    }

    @Test
    void anIncompleteOrFailedRunIsNotMeasured() {
        List<String> incomplete = console(0.6, 12);
        incomplete.removeIf(line -> line.contains("PROBE-DONE"));
        assertThrows(IllegalStateException.class, () -> ProbeLog.parse(incomplete));
        List<String> failed = console(0.6, 12);
        failed.add("[2026-11-09 20:31:00:000 WARN] [Scripting] PROBE-FAILED Error: x");
        assertThrows(IllegalStateException.class, () -> ProbeLog.parse(failed));
    }

    @Test
    void frictionIsASlideSpeedLossDividedByTheDrag() {
        JsonObject capture = measure(console(0.6, 12));
        assertEquals(0.6, defaultOf(capture, "block:friction").get("value").getAsDouble());
        JsonObject vanilla = capture.getAsJsonObject("friction").getAsJsonObject("vanilla");
        assertEquals(0.98, vanilla.get("minecraft:ice").getAsDouble());
        JsonArray components = capture.getAsJsonObject("friction").getAsJsonArray("components");
        assertEquals(3, components.size());
        assertEquals(0.1, components.get(2).getAsJsonObject().get("engine").getAsDouble(), "component 0.9");
    }

    @Test
    void aFrictionThatIsNotOneMinusTheComponentIsAnError() {
        // the floor of component 0.9 slid like friction 0.5: not 1 - 0.9
        assertThrows(IllegalStateException.class, () -> measure(console(0.6, 12, 0.5)));
    }

    @Test
    void theDefaultsOfACustomBlock() {
        JsonObject capture = measure(console(0.6, 12));
        assertEquals(15, defaultOf(capture, "block:light_dampening").get("value").getAsInt());
        assertEquals(0, defaultOf(capture, "block:light_emission").get("value").getAsInt());
        assertEquals(0, defaultOf(capture, "block:explosion_resistance").get("value").getAsInt());
        assertEquals(0, defaultOf(capture, "block:burn_odds").get("value").getAsInt());
        assertEquals(0, defaultOf(capture, "block:flame_odds").get("value").getAsInt());
        JsonArray box = defaultOf(capture, "block:collision_shape").getAsJsonArray("value").get(0).getAsJsonArray();
        assertEquals("[0.0,0.0,0.0,1.0,1.0,1.0]", box.toString());
        assertTrue(defaultOf(capture, "block:explosion_resistance").get("method").getAsString().contains("Fisher"));
    }

    @Test
    void anExplosionResistanceThatIsNeitherZeroNorFarFromItIsNotDecided() {
        // Destroyed 2 of 48 where explicit resistance 0 is destroyed 14 times: not the same as resistance 0
        assertThrows(IllegalStateException.class, () -> measure(console(0.6, 2)));
    }

    @Test
    void aDoorPanelIsTheIntervalOfTheAxisThatStoppedBodiesFromBothSides() {
        JsonObject doors = measure(console(0.6, 12)).getAsJsonObject("doors");
        JsonArray door = doors.getAsJsonArray("minecraft:wooden_door");
        assertEquals(2, door.size(), "lower and upper half");
        for (var entry : door) {
            JsonObject panel = entry.getAsJsonObject();
            assertEquals("x", panel.get("axis").getAsString());
            assertEquals(0.8175, panel.get("min").getAsDouble());
            assertEquals(1.0, panel.get("max").getAsDouble());
        }
        assertEquals("north", door.get(0).getAsJsonObject().getAsJsonObject("states").get("minecraft:cardinal_direction").getAsString());
        assertTrue(!door.get(0).getAsJsonObject().getAsJsonObject("states").has("direction"), "only the states of the palette");
    }

    @Test
    void aBodyThatEndedBesideTheCellWasNotStopped() {
        JsonObject free = new JsonObject();
        free.addProperty("rel", 0.0616);
        free.addProperty("y", 200);
        free.addProperty("level", 200);
        assertTrue(!isBlocked("+x", free));
        JsonObject stopped = new JsonObject();
        stopped.addProperty("rel", 1.25);
        stopped.addProperty("y", 200);
        stopped.addProperty("level", 200);
        assertTrue(isBlocked("+x", stopped));
        assertTrue(!isBlocked("-x", stopped), "1.25 is no stop from the negative side: that body would have gone through the cell");
        stopped.addProperty("y", 190);
        assertTrue(!isBlocked("+x", stopped), "fell out of the level");
    }

    private static boolean isBlocked(String side, JsonObject observation) {
        return BehaviourProbe.blocked(side, observation);
    }

    @Test
    void aContactThatIsNotOnTheGridIsAnError() {
        assertEquals(0.6, BehaviourProbe.snap(0.600001), "measured to 1e-6: a contact at 0.6");
        assertThrows(IllegalStateException.class, () -> BehaviourProbe.snap(0.60003), "3e-5 off every 4 decimal position");
    }

    @Test
    void fishersExactTest() {
        assertEquals(1.0, BehaviourProbe.fisher(10, 48, 10, 48), 1e-9);
        assertTrue(BehaviourProbe.fisher(12, 48, 14, 48) > 0.5);
        assertTrue(BehaviourProbe.fisher(12, 48, 0, 48) < 1e-3);
        assertEquals(BehaviourProbe.fisher(3, 20, 11, 30), BehaviourProbe.fisher(11, 30, 3, 20), 1e-12);
        // 2x2 table [[3, 1], [1, 3]] from Fisher's tea tasting: two sided p = 0.4857
        assertEquals(0.4857, BehaviourProbe.fisher(3, 4, 1, 4), 1e-4);
    }

    @Test
    void thePrettyPrinterKeepsShortElementsOnOneLine() {
        JsonObject object = new JsonObject();
        object.addProperty("note", "It's \"quoted\" & <tagged>");
        JsonArray list = new JsonArray();
        for (int i = 0; i < 40; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("i", i);
            list.add(entry);
        }
        object.add("list", list);
        String text = Json.pretty(object);
        assertTrue(text.contains("\"note\": \"It's \\\"quoted\\\" & <tagged>\""), text);
        assertTrue(text.contains("    {\"i\":3},\n"), text);
    }

    @Test
    void capturedKeepsOneLinePerDirectory(@TempDir Path root) throws IOException {
        Captured.record(root, "1.26.63.1", "53340801", "data/bds/1.26.63.1/behaviour-probe/", "first", "cmd | pipe");
        Captured.record(root, "1.26.70.1", "1", "data/bds/1.26.70.1/behaviour-probe/", "other", "cmd");
        Captured.record(root, "1.26.63.1", "53340801", "data/bds/1.26.63.1/behaviour-probe/", "again", "cmd");
        String text = Files.readString(root.resolve(Captured.FILE));
        assertEquals(1, text.lines().filter(line -> line.contains("1.26.63.1/behaviour-probe")).count(), text);
        assertTrue(text.contains("again") && !text.contains("first") && text.contains("other"), text);
        assertTrue(text.contains("cmd \\| pipe") || !text.contains("cmd | pipe"), text);
    }
}
