package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * The per-state measurements of the behaviour probe: light emission and dampening and friction of every block state, and the
 * states BDS does not keep as placed, tried with the blocks around them. The add-on prints what it read; this class draws the
 * conclusions, checked against controls (custom blocks whose component values are known), and writes them into the probe's
 * capture as {@code light_states}, {@code friction_states} and {@code around_recipes}.
 * <p>
 * The sections are not essential. One the add-on could not run ({@code failed}), or whose controls do not hold, becomes
 * {@code {"unverified": reason}}: the importer then leaves the values to the other sources. A section the log does not have at all
 * (a log of an older probe) is not in the capture. A state a reading does not decide is {@code unclear}, never a guess.
 */
final class StateProbe {
    static final String LIGHT = "light_states";
    static final String FRICTION = "friction_states";
    static final String AROUND = "around_recipes";
    static final String UNVERIFIED = "unverified";

    /** Controls: custom blocks that set {@code minecraft:light_emission} to this value. Plain sets nothing and was measured as 0. */
    static final Map<String, Integer> EMISSION_CONTROLS = Map.of("probe:light_emission_7", 7, "probe:light_emission_15", 15);
    static final String EMISSION_ONE = "probe:light_emission_1";
    private static final String PLAIN = "probe:plain";
    /** Controls: custom blocks that set {@code minecraft:light_dampening} to this value. Plain sets nothing and was measured as 15. */
    static final Map<String, Integer> DAMPENING_CONTROLS = Map.of("probe:light_dampening_0", 0, "probe:light_dampening_1", 1,
        "probe:light_dampening_2", 2, "probe:light_dampening_7", 7, "probe:light_dampening_14", 14, "probe:light_dampening_15", 15);
    /** A body's speed loses at most this much of its per-tick factor over a slide before the slide is not one friction. */
    private static final double MAX_SPREAD = 0.005;
    /** A body that moved vertically more than this was not standing on the floor. */
    private static final double MAX_RISE = 0.005;
    private static final double FRICTION_TOLERANCE = 0.002;

    private StateProbe() {
    }

    /** Adds the sections the log has to the capture. */
    static void addTo(JsonObject capture, ProbeLog log) {
        JsonObject light = section(log, "light", "light_state", StateProbe::light);
        if (light != null) {
            capture.add(LIGHT, light);
        }
        JsonObject friction = section(log, "friction", "friction_state", StateProbe::friction);
        if (friction != null) {
            capture.add(FRICTION, friction);
        }
        JsonObject lightBlocks = light != null && light.has("blocks") ? light.getAsJsonObject("blocks") : null;
        JsonObject around = section(log, "around", "around", l -> around(l, lightBlocks));
        if (around != null) {
            capture.add(AROUND, around);
        }
    }

    private static JsonObject section(ProbeLog log, String name, String tag, Function<ProbeLog, JsonObject> measure) {
        for (JsonObject failed : log.optional("failed")) {
            if (failed.get("section").getAsString().equals(name)) {
                return unverified("the probe could not run it: " + firstLine(failed.get("error").getAsString()));
            }
        }
        if (log.optional(tag).isEmpty()) {
            return null;
        }
        try {
            return measure.apply(log);
        } catch (IllegalStateException e) {
            return unverified(e.getMessage());
        }
    }

    private static JsonObject unverified(String reason) {
        JsonObject result = new JsonObject();
        result.addProperty(UNVERIFIED, reason);
        return result;
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    // ---- shared: readings by block and state ----

    private static Map<String, JsonObject> byBlock(List<JsonObject> observations) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        observations.forEach(observation -> result.put(observation.get("block").getAsString(), observation));
        return result;
    }

    /** @return the reading of a control, which is placed once and so is the block's base */
    private static JsonElement control(Map<String, JsonObject> blocks, String block) {
        JsonObject observation = blocks.get(block);
        if (observation == null || observation.get("base").isJsonNull() || !observation.getAsJsonArray("differ").isEmpty()) {
            throw new IllegalStateException("control " + block + " was not read: " + observation);
        }
        return observation.get("base");
    }

    /**
     * What the readings of one block decide: the value of the reading most states have, the states with another value, and the
     * states whose reading decides none. A state the add-on could not place as asked is under {@code changed} of its block and is
     * not in any of these.
     */
    static JsonObject entry(JsonObject observation, Function<JsonElement, Double> conclude) {
        JsonElement base = observation.get("base");
        Double baseValue = base.isJsonNull() ? null : conclude.apply(base);
        JsonObject result = new JsonObject();
        result.add("base", baseValue == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(baseValue));
        JsonArray differ = new JsonArray();
        JsonArray unclear = new JsonArray();
        for (JsonElement element : observation.getAsJsonArray("differ")) {
            JsonArray pair = element.getAsJsonArray();
            Double value = conclude.apply(pair.get(1));
            if (value == null) {
                unclear.add(pair.get(0));
            } else if (!value.equals(baseValue)) {
                JsonObject state = new JsonObject();
                state.add("state", pair.get(0));
                state.addProperty("value", value);
                differ.add(state);
            }
        }
        result.add("differ", differ);
        result.add("unclear", unclear);
        return result;
    }

    /** {@code [requested, block, state]} of the add-on as objects. */
    private static JsonArray changed(JsonObject observation) {
        JsonArray result = new JsonArray();
        for (JsonElement element : observation.getAsJsonArray("changed")) {
            JsonArray triple = element.getAsJsonArray();
            JsonObject changed = new JsonObject();
            changed.add("requested", triple.get(0));
            changed.add("became", triple.get(1));
            changed.add("as", triple.get(2));
            result.add(changed);
        }
        return result;
    }

    // ---- light ----

    /**
     * Emission: in a sealed cell, light in the air cell next to a state is 1 less than what it emits. The light in the state's
     * own cell reads 0 for an opaque block (the control {@code probe:light_emission_15} did in 1.26.63.1), so it is only used where
     * the cell next to it reads 0: an emission of 1 reads like none next to an opaque block, which the control
     * {@code probe:light_emission_1} says, and the value is then 0, "lights nothing".
     * <p>
     * Dampening: with the sky open above, the sky light in the air cell below a state is 15 for a dampening of 0 and 15 minus
     * the dampening otherwise (no light under 15). The controls must follow that for every value they set.
     */
    private static JsonObject light(ProbeLog log) {
        Map<String, JsonObject> blocks = byBlock(log.of("light_state"));
        if (!conclude(control(blocks, PLAIN), StateProbe::emission).equals(0.0)) {
            throw new IllegalStateException("light emission control: a block that sets none reads " + control(blocks, PLAIN));
        }
        JsonObject controls = new JsonObject();
        JsonObject emission = new JsonObject();
        for (Map.Entry<String, Integer> expected : EMISSION_CONTROLS.entrySet()) {
            JsonElement reading = control(blocks, expected.getKey());
            if (!conclude(reading, StateProbe::emission).equals((double) expected.getValue())) {
                throw new IllegalStateException("light emission control " + expected.getKey() + " reads " + reading + ", which is not " + expected.getValue());
            }
            emission.addProperty(expected.getKey(), expected.getValue());
        }
        JsonElement one = control(blocks, EMISSION_ONE);
        boolean oneReadsAsNone = emission(one) != null && emission(one) == 0.0;
        if (!oneReadsAsNone && !conclude(one, StateProbe::emission).equals(1.0)) {
            throw new IllegalStateException("light emission control " + EMISSION_ONE + " reads " + one + ", which is not 1");
        }
        controls.add("emission", emission);
        controls.addProperty("emission_1_reads_as_none", oneReadsAsNone);
        JsonObject dampening = new JsonObject();
        for (Map.Entry<String, Integer> expected : DAMPENING_CONTROLS.entrySet()) {
            JsonElement reading = control(blocks, expected.getKey());
            if (!conclude(reading, StateProbe::dampening).equals((double) expected.getValue())) {
                throw new IllegalStateException("light dampening control " + expected.getKey() + " reads " + reading + ", which is not " + expected.getValue());
            }
            dampening.addProperty(expected.getKey(), expected.getValue());
        }
        controls.add("dampening", dampening);

        JsonObject result = new JsonObject();
        result.addProperty("method", "Every state in a column of a slab of stone: an air cell, the state above it, a stone ceiling. Sealed, "
            + "the light in the air cell next to the state is its emission minus 1 (the state's own cell reads 0 for an opaque block, so it "
            + "is only read where the air cell reads 0); with the ceiling open to the sky, the sky light in the air cell below it is 15 for a "
            + "dampening of 0 and 15 minus the dampening otherwise. Both rules hold for every control (custom blocks that set the component), "
            + "and a state is read only if it stayed as placed and nothing flowed into the cell below. A state BDS does not keep as placed "
            + "(a stair's shape follows its neighbours) is under `changed`: what was there was measured, the requested state was not.");
        result.add("controls", controls);
        JsonObject perBlock = new JsonObject();
        for (JsonObject observation : blocks.values()) {
            JsonObject block = new JsonObject();
            block.addProperty("states", observation.get("states").getAsInt());
            block.add("changed", changed(observation));
            block.add("emission", entry(observation, StateProbe::emission));
            block.add("dampening", entry(observation, StateProbe::dampening));
            perBlock.add(observation.get("block").getAsString(), block);
        }
        result.add("blocks", perBlock);
        return result;
    }

    private static Double conclude(JsonElement reading, Function<JsonElement, Double> rule) {
        Double value = rule.apply(reading);
        if (value == null) {
            throw new IllegalStateException("a control's reading decides nothing: " + reading);
        }
        return value;
    }

    /** @param reading {@code [light in the state's cell, light in the air cell below it, sky light there]} */
    static Double emission(JsonElement reading) {
        JsonArray values = reading.getAsJsonArray();
        int self = values.get(0).getAsInt();
        int next = values.get(1).getAsInt();
        if (next > 0) {
            return self == 0 || self == next + 1 ? (double) (next + 1) : null;
        }
        return self <= 1 ? (double) self : null;
    }

    static Double dampening(JsonElement reading) {
        int sky = reading.getAsJsonArray().get(2).getAsInt();
        return sky < 0 || sky > 15 ? null : (double) (sky == 15 ? 0 : 15 - sky);
    }

    // ---- friction ----

    /**
     * The speed of a body slid over a state falls by a constant share of its speed each tick: {@link BehaviourProbe#MOB_DRAG}
     * times the friction of what it stands on. The controls, custom blocks that set {@code minecraft:friction}, must have an
     * engine friction of 1 minus that value.
     */
    private static JsonObject friction(ProbeLog log) {
        Map<String, JsonObject> blocks = byBlock(log.of("friction_state"));
        JsonObject controls = new JsonObject();
        int checked = 0;
        for (Map.Entry<String, JsonObject> block : blocks.entrySet()) {
            if (!block.getKey().startsWith("probe:friction_")) {
                continue;
            }
            double component = Integer.parseInt(block.getKey().substring("probe:friction_".length())) / 100.0;
            Double engine = friction(control(blocks, block.getKey()));
            if (engine == null || Math.abs(engine - (1 - component)) > FRICTION_TOLERANCE) {
                throw new IllegalStateException("friction control " + block.getKey() + " reads " + engine + ", which is not 1 - " + component);
            }
            controls.addProperty(block.getKey(), engine);
            checked++;
        }
        if (checked < 3) {
            throw new IllegalStateException("only " + checked + " friction controls were read");
        }
        JsonObject result = new JsonObject();
        result.addProperty("method", "Every state as a floor of 8 blocks, a body (no gravity change, 0.5 wide) dropped on it, pushed, and its "
            + "horizontal speed read on 10 ticks: the engine friction is the median share of speed kept per tick divided by "
            + BehaviourProbe.MOB_DRAG + ", to 3 decimals, if the shares agree within " + MAX_SPREAD + " and the body stayed on the floor "
            + "(within " + MAX_RISE + " in height). The controls (custom blocks that set minecraft:friction) read 1 minus their value "
            + "(within " + FRICTION_TOLERANCE + "). A state BDS does not keep as placed is under `changed`: what was there was measured, "
            + "the requested state was not. Unverified: states that slow a body by more than their friction (a speed factor), which "
            + "this does not tell apart.");
        result.add("controls", controls);
        JsonObject perBlock = new JsonObject();
        for (JsonObject observation : blocks.values()) {
            JsonObject block = new JsonObject();
            block.addProperty("states", observation.get("states").getAsInt());
            block.add("changed", changed(observation));
            block.add("friction", entry(observation, StateProbe::friction));
            perBlock.add(observation.get("block").getAsString(), block);
        }
        result.add("blocks", perBlock);
        return result;
    }

    /** @param reading {@code {v: [horizontal velocity per tick], dy: the most the body's height moved}} */
    static Double friction(JsonElement reading) {
        JsonObject slide = reading.getAsJsonObject();
        if (slide.get("dy").getAsDouble() > MAX_RISE) {
            return null;
        }
        List<Double> velocities = new ArrayList<>();
        slide.getAsJsonArray("v").forEach(v -> velocities.add(v.getAsDouble()));
        OptionalDouble friction = slideFriction(velocities, MAX_SPREAD);
        return friction.isPresent() && friction.getAsDouble() >= 0 && friction.getAsDouble() <= 1 + FRICTION_TOLERANCE ? friction.getAsDouble() : null;
    }

    /**
     * @param velocities the horizontal velocity of a sliding body on consecutive ticks
     * @param maxSpread how far the per-tick shares may be from one another, {@link Double#MAX_VALUE} for any
     * @return the engine friction, or empty if fewer than 3 shares can be told (the body is nearly at rest) or they are too far apart
     */
    static OptionalDouble slideFriction(List<Double> velocities, double maxSpread) {
        List<Double> shares = new ArrayList<>();
        for (int i = 0; i + 1 < velocities.size(); i++) {
            double before = velocities.get(i);
            double after = velocities.get(i + 1);
            if (Math.abs(before) > 1e-4 && Math.abs(after) > 1e-4) {
                shares.add(after / before);
            }
        }
        if (shares.size() < 3) {
            return OptionalDouble.empty();
        }
        shares.sort(Double::compare);
        if (shares.get(shares.size() - 1) - shares.get(0) > maxSpread) {
            return OptionalDouble.empty();
        }
        double median = shares.get(shares.size() / 2);
        return OptionalDouble.of(Math.round(median / BehaviourProbe.MOB_DRAG * 1000) / 1000.0);
    }

    // ---- states BDS does not keep ----

    /**
     * The states of the blocks BDS turns into another block when it places them, tried in sealed boxes with the blocks around
     * them each recipe names. A state BDS kept in a recipe has the light of its cell as its emission, provided the blocks of the
     * recipe emit none (they were measured as 0 by the light section): else the light could be theirs.
     */
    private static JsonObject around(ProbeLog log, JsonObject lightBlocks) {
        JsonObject result = new JsonObject();
        result.addProperty("method", "Each state tried in a sealed 3x3x3 box of stone with the blocks around it its recipe names (the add-on's "
            + "AROUND table: recipes to try, nothing is taken from them). A state counts if BDS kept it as placed; its emission is the light "
            + "of its cell, if no block of the recipe emits light (the light section measured every one as 0).");
        JsonObject perBlock = new JsonObject();
        for (JsonObject observation : log.of("around")) {
            JsonObject block = perBlock.has(observation.get("block").getAsString()) ? perBlock.getAsJsonObject(observation.get("block").getAsString())
                : newBlock(perBlock, observation.get("block").getAsString());
            JsonObject kept = null;
            for (JsonElement element : observation.getAsJsonArray("attempts")) {
                if (element.getAsJsonObject().get("kept").getAsBoolean()) {
                    kept = element.getAsJsonObject();
                    break;
                }
            }
            if (kept == null) {
                block.getAsJsonArray("unclear").add(unclear(observation, "BDS kept the state in none of the recipes"));
                continue;
            }
            List<String> emitting = new ArrayList<>();
            for (JsonElement material : kept.getAsJsonArray("materials")) {
                if (!emitsNothing(lightBlocks, material.getAsString())) {
                    emitting.add(material.getAsString());
                }
            }
            if (!emitting.isEmpty()) {
                block.getAsJsonArray("unclear").add(unclear(observation, "the recipe '" + kept.get("recipe").getAsString()
                    + "' has blocks that emit light or were not measured: " + emitting));
                continue;
            }
            JsonObject measured = new JsonObject();
            measured.add("state", observation.get("state"));
            measured.addProperty("recipe", kept.get("recipe").getAsString());
            measured.addProperty("emission", kept.get("light").getAsInt());
            block.getAsJsonArray("measured").add(measured);
        }
        result.add("blocks", perBlock);
        return result;
    }

    private static JsonObject newBlock(JsonObject perBlock, String name) {
        JsonObject block = new JsonObject();
        block.add("measured", new JsonArray());
        block.add("unclear", new JsonArray());
        perBlock.add(name, block);
        return block;
    }

    private static JsonObject unclear(JsonObject observation, String reason) {
        JsonObject result = new JsonObject();
        result.add("state", observation.get("state"));
        result.addProperty("reason", reason);
        return result;
    }

    /** Whether the light section measured every state of the block as emitting 0. */
    private static boolean emitsNothing(JsonObject lightBlocks, String block) {
        if (lightBlocks == null || !lightBlocks.has(block)) {
            return false;
        }
        JsonObject emission = lightBlocks.getAsJsonObject(block).getAsJsonObject("emission");
        return !emission.get("base").isJsonNull() && emission.get("base").getAsDouble() == 0 && emission.getAsJsonArray("differ").isEmpty()
            && emission.getAsJsonArray("unclear").isEmpty();
    }
}
