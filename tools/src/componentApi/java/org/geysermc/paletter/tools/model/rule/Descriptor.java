package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;

/**
 * A block or item descriptor: the JSON value names a block or item (and, for a block, its states and tags) and the wire
 * leaves below the rule's {@code wire} are written by the encoder's descriptor primitive, which resolves them against the
 * palette's registry ({@code Wire.blockDescriptor}, {@code Wire.itemDescriptor}). What the registry decides (the types of
 * the states, whether an item exists) is not a function of the JSON value, so no rule over the value fits it.
 * <ul>
 *   <li>{@code block}: the full triple {@code name}, {@code states}, {@code tags}, "" for what is not set;</li>
 *   <li>{@code item}: {@code name}, namespaced, or nothing for an item the registry lacks.</li>
 * </ul>
 */
public final class Descriptor extends Rule {
    static final String KIND = "descriptor";

    /** {@code block} or {@code item}. */
    public final String of;

    public Descriptor(String of) {
        this.of = of;
    }

    static Descriptor read(JsonObject json) {
        return new Descriptor(json.get("of").getAsString());
    }

    /**
     * @param prefix the leaf path of the descriptor ({@code a.use_on[0]}): its leaves are {@code prefix.name},
     *               {@code prefix.states.<state>} and {@code prefix.tags}
     * @return the rule if every observation sends the descriptor of its JSON value in one of the two layouts, else null.
     *         A block descriptor has {@code tags} leaves; an item descriptor must be sent for at least one item
     */
    public static Descriptor fit(List<Observation> set, String prefix) {
        boolean block = set.stream().anyMatch(p -> p.wire().containsKey(prefix + ".tags"));
        boolean sent = false;
        for (Observation p : set) {
            JsonElement json = p.json();
            String name = json.isJsonPrimitive() ? json.getAsString() : text(json.getAsJsonObject(), "name");
            Leaf leaf = p.wire().get(prefix + ".name");
            if (block) {
                if (!json.isJsonObject() || !sameText(leaf, name) || !sameText(p.wire().get(prefix + ".tags"), text(json.getAsJsonObject(), "tags"))
                    || !statesMatch(p, prefix, json.getAsJsonObject())) {
                    return null;
                }
            } else if (leaf != null) {
                if (name == null || !leaf.value().equals(namespaced(name))) {
                    return null;
                }
                sent = true;
            }
        }
        return block || sent ? new Descriptor(block ? "block" : "item") : null;
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    /** @return whether the leaf is the text, "" for no text */
    private static boolean sameText(Leaf leaf, String text) {
        return leaf != null && leaf.value().equals(text == null ? "" : text);
    }

    private static String namespaced(String identifier) {
        return identifier.contains(":") ? identifier : "minecraft:" + identifier;
    }

    /** @return whether the state leaves are the states of the JSON, a boolean as 0 or 1, and no others */
    private static boolean statesMatch(Observation p, String prefix, JsonObject json) {
        JsonObject states = json.has("states") ? json.getAsJsonObject("states") : new JsonObject();
        if (p.wire().keySet().stream().filter(k -> k.startsWith(prefix + ".states.")).count() != states.size()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> state : states.entrySet()) {
            Leaf leaf = p.wire().get(prefix + ".states." + state.getKey());
            JsonPrimitive value = state.getValue().getAsJsonPrimitive();
            boolean same = leaf != null && (value.isString() ? leaf.value().equals(value.getAsString())
                : leaf.numeric() && leaf.number() == (value.isBoolean() ? (value.getAsBoolean() ? 1 : 0) : value.getAsDouble()));
            if (!same) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean writesValue() {
        return true;
    }

    @Override
    void params(JsonObject out) {
        out.addProperty("of", of);
    }

    @Override
    public void emit(Emitter e) {
        if (of.equals("block")) {
            e.code().addStatement("$T.blockDescriptor(ctx, $L, $S, $L.name(), $L.states(), $L.tags())", Emitter.WIRE, e.node(), e.leaf(),
                e.value(), e.value(), e.value());
        } else {
            e.code().addStatement("$T.itemDescriptor(ctx, $L, $S, $L, $S, $L)", Emitter.WIRE, e.node(), e.leaf(), e.component(), e.path(),
                e.string());
        }
    }
}
