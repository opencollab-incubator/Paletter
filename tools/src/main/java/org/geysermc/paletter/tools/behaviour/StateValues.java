package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.BlockBehaviour;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The values of the per-state sections of the behaviour probe capture ({@link StateProbe}) for the states of one bundled version:
 * light emission, light dampening and friction.
 * <p>
 * A state BDS did not keep as placed ({@code changed}) has no measurement of its own. If every state the probe did measure of its
 * block has the same value, the block's states are uniform and the state gets that value, marked as filled; else the state has no
 * value from the probe. The states of the blocks BDS turns into another block ({@code around_recipes}) have a measurement if BDS
 * kept them in a recipe.
 */
final class StateValues {
    private StateValues() {
    }

    /**
     * @param capture the probe's {@code capture.json}
     * @param states the palette of the version
     * @param log the importer's log, to say which section is unverified and which states the palette lacks
     * @param where the source, for the log
     */
    static Values<Integer> blocks(JsonObject capture, Map<Integer, NbtMap> states, List<String> log, String where) {
        Values<Integer> values = new Values<>();
        Lookup lookup = new Lookup(states);
        section(capture, StateProbe.LIGHT, log, where, light -> {
            addKey(values, BlockBehaviour.LIGHT_EMISSION, "emission", light, lookup, Double::intValue);
            addKey(values, BlockBehaviour.LIGHT_DAMPENING, "dampening", light, lookup, Double::intValue);
        });
        section(capture, StateProbe.FRICTION, log, where, friction -> addKey(values, BlockBehaviour.FRICTION, "friction", friction, lookup, Double::floatValue));
        section(capture, StateProbe.AROUND, log, where, around -> addAround(values, around, lookup));
        if (lookup.missing > 0) {
            log.add(where + ": " + lookup.missing + " probed states are no state of the palette (blocks it does not have)");
        }
        return values;
    }

    private static void section(JsonObject capture, String name, List<String> log, String where, java.util.function.Consumer<JsonObject> read) {
        if (!capture.has(name)) {
            return;
        }
        JsonObject section = capture.getAsJsonObject(name);
        if (section.has(StateProbe.UNVERIFIED)) {
            log.add(where + ": " + name + " is unverified: " + section.get(StateProbe.UNVERIFIED).getAsString());
        } else {
            read.accept(section);
        }
    }

    private static <T extends Number> void addKey(Values<Integer> values, BehaviourKey<?> key, String member, JsonObject section,
                                                  Lookup lookup, Function<Double, T> number) {
        for (Map.Entry<String, JsonElement> entry : section.getAsJsonObject("blocks").entrySet()) {
            String block = entry.getKey();
            JsonObject measured = entry.getValue().getAsJsonObject();
            JsonObject summary = measured.getAsJsonObject(member);
            Map<Integer, Double> differ = new HashMap<>();
            for (JsonElement element : summary.getAsJsonArray("differ")) {
                JsonObject state = element.getAsJsonObject();
                differ.put(lookup.hash(block, state.get("state")), state.get("value").getAsDouble());
            }
            Set<Integer> unclear = new HashSet<>();
            for (JsonElement state : summary.getAsJsonArray("unclear")) {
                unclear.add(lookup.hash(block, state));
            }
            Set<Integer> changed = new HashSet<>();
            for (JsonElement element : measured.getAsJsonArray("changed")) {
                changed.add(lookup.hash(block, element.getAsJsonObject().get("requested")));
            }
            Double base = summary.get("base").isJsonNull() ? null : summary.get("base").getAsDouble();
            boolean uniform = base != null && differ.isEmpty() && unclear.isEmpty();
            lookup.palette.forEach((hash, state) -> {
                if (!state.getString("name").equals(block) || unclear.contains(hash)) {
                    return;
                }
                if (differ.containsKey(hash)) {
                    values.put(key, hash, number.apply(differ.get(hash)));
                } else if (changed.contains(hash)) {
                    if (uniform) {
                        values.put(key, hash, number.apply(base));
                        values.postProcessed(key, hash, "filled: BDS did not keep the state as placed, and every state of the block that the probe "
                            + "measured has this value");
                    }
                } else if (base != null) {
                    values.put(key, hash, number.apply(base));
                }
            });
        }
    }

    /** The emission of the states of the blocks BDS turns into another block: measured where BDS kept them in a recipe. */
    private static void addAround(Values<Integer> values, JsonObject around, Lookup lookup) {
        for (Map.Entry<String, JsonElement> entry : around.getAsJsonObject("blocks").entrySet()) {
            for (JsonElement element : entry.getValue().getAsJsonObject().getAsJsonArray("measured")) {
                JsonObject measured = element.getAsJsonObject();
                Integer hash = lookup.hash(entry.getKey(), measured.get("state"));
                if (hash != null) {
                    values.put(BlockBehaviour.LIGHT_EMISSION, hash, measured.get("emission").getAsInt());
                    // measured, so it is not a fill
                    values.postProcessed.getOrDefault(BlockBehaviour.LIGHT_EMISSION, new HashMap<>()).remove(hash);
                }
            }
        }
    }

    /** The palette states, and the states of the probe found in them (those it lacks are counted). */
    private static final class Lookup {
        final Map<Integer, NbtMap> palette;
        private final ScriptApiValues.Index index;
        int missing;

        Lookup(Map<Integer, NbtMap> palette) {
            this.palette = palette;
            this.index = new ScriptApiValues.Index(palette);
        }

        /** @return the palette hash of a Script API state, or null if the palette has none */
        Integer hash(String block, JsonElement state) {
            Integer hash = index.hash(block, state.getAsJsonObject());
            if (hash == null) {
                missing++;
            }
            return hash;
        }
    }
}
