package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.codegen.component.Docs;

import java.util.ArrayList;
import java.util.List;

/**
 * One rule of {@code data/model/mappings.json}: how the JSON value of a field decides one wire leaf. Each kind is fitted
 * from what BDS sent for the variants of a spec capture ({@code fit} methods of the kinds), writes its part of the
 * generated encoder ({@link #emit}) and evaluates itself the way that encoder does ({@link #evaluate}).
 * <p>
 * A new kind is a new subclass: its members ({@link #params}, {@link #of}), its fit (called by the inference's
 * {@code RuleFit}), its encoder and its evaluation.
 */
public abstract sealed class Rule permits Identity, Linear, Table, Affix, Bitset, Count, OrderedSet, Constant, EmptyList, EmptyMap,
    Structure, CrossField, EnumTable {
    /** A table entry or constant for a leaf BDS does not send. */
    public static final String NOT_SENT = "<not sent>";

    /** The generic wire path of the leaf ({@code a[].b}; {@code a{}} for every key of a map). */
    public String wire;
    /** The NBT type of the leaf; null if no observation sent it. */
    public String nbt;
    /** What the rule says about the field for readers (renamed, unit-conversion, ...); null if none were given. */
    public List<String> tags;
    /** The element of a JSON list the leaf follows; null for the whole value. */
    public Integer element;
    /** Whether the leaf follows the value of a map entry whose key is part of the leaf path. */
    public boolean mapEntry;
    /** The map key of a {@link #mapEntry} rule while the inference merges the entries; never written. */
    public String key;
    /** The step the API's values must be a multiple of (a linear rule onto whole steps). */
    public Long multipleOf;
    /** Bits every value of an integral table sets that no field explains. */
    public List<Integer> residualBits;

    /** @return the name of the kind in {@code mappings.json} */
    public abstract String kind();

    /**
     * @return whether the field's value decides the leaf: an encoder writes it from the value (else the field's presence
     *         or its children decide it, or nothing does)
     */
    public abstract boolean writesValue();

    /** @return whether the leaf is one the field's presence switches on (a sibling default BDS writes with it), not its value */
    public boolean presence() {
        return false;
    }

    /**
     * @return whether, onto a list leaf ({@code wire} ending in {@code []}) from a list of scalars or a vector, the rule is
     *         of every element: it writes the whole list ({@link Emitter#convert} and {@link Emitter#strings} give the list)
     */
    public boolean wholeList() {
        return false;
    }

    /** @return the tags the kind itself adds for readers */
    public List<String> kindTags() {
        return List.of();
    }

    /** Writes the members of the kind. */
    void params(JsonObject out) {
    }

    /**
     * Writes the encoder code that puts the leaf from the field's value. A rule that writes no value writes nothing: the
     * encoder writes the constants a field's presence switches on itself, and the children of a structure write theirs.
     */
    public void emit(Emitter e) {
    }

    /**
     * Evaluates the rule on a JSON value the way the encoder does, as a {@code value (type)} literal; null if the rule
     * cannot be evaluated on it.
     */
    public final String evaluate(JsonElement json) {
        if (nbt == null || json == null || !json.isJsonPrimitive() || element != null) {
            return null;
        }
        return evaluate(json.getAsJsonPrimitive());
    }

    String evaluate(JsonPrimitive p) {
        return null;
    }

    /** @return the rules of a field or form of {@code mappings.json}; none for null */
    public static List<Rule> all(JsonObject mapping) {
        List<Rule> rules = new ArrayList<>();
        if (mapping != null && mapping.has("rules")) {
            mapping.getAsJsonArray("rules").forEach(r -> rules.add(of(r.getAsJsonObject())));
        }
        return rules;
    }

    /** Reads a rule of {@code mappings.json}. */
    public static Rule of(JsonObject json) {
        String kind = json.get("kind").getAsString();
        Rule rule = switch (kind) {
            case Identity.KIND -> new Identity();
            case Linear.KIND -> Linear.read(json);
            case Table.KIND -> Table.read(json);
            case Affix.KIND -> Affix.read(json);
            case Bitset.KIND -> Bitset.read(json);
            case Count.KIND -> new Count();
            case OrderedSet.KIND -> OrderedSet.read(json);
            case Constant.KIND -> new Constant(json.get("value").getAsString());
            case EmptyList.KIND -> new EmptyList();
            case EmptyMap.KIND -> new EmptyMap();
            case Structure.KIND -> new Structure();
            case CrossField.KIND -> new CrossField();
            case EnumTable.KIND -> EnumTable.read(json);
            default -> throw new IllegalArgumentException("unknown rule kind " + kind + ": " + json);
        };
        rule.wire = json.has("wire") ? json.get("wire").getAsString() : null;
        rule.nbt = json.has("nbt") ? json.get("nbt").getAsString() : null;
        rule.tags = json.has("tags") ? json.getAsJsonArray("tags").asList().stream().map(JsonElement::getAsString).toList() : null;
        rule.element = json.has("element") ? json.get("element").getAsInt() : null;
        rule.mapEntry = json.has("mapEntry") && json.get("mapEntry").getAsBoolean();
        rule.key = json.has("key") ? json.get("key").getAsString() : null;
        rule.multipleOf = json.has("multipleOf") ? json.get("multipleOf").getAsLong() : null;
        rule.residualBits = json.has("residualBits")
            ? json.getAsJsonArray("residualBits").asList().stream().map(JsonElement::getAsInt).toList() : null;
        return rule;
    }

    /** The rule as {@code mappings.json} has it. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind());
        params(out);
        if (element != null) {
            out.addProperty("element", element);
        }
        if (mapEntry) {
            out.addProperty("mapEntry", true);
        }
        if (key != null) {
            out.addProperty("key", key);
        }
        if (nbt != null) {
            out.addProperty("nbt", nbt);
        }
        if (tags != null) {
            JsonArray array = new JsonArray();
            tags.forEach(array::add);
            out.add("tags", array);
        }
        if (wire != null) {
            out.addProperty("wire", wire);
        }
        if (multipleOf != null) {
            out.addProperty("multipleOf", multipleOf);
        }
        if (residualBits != null) {
            JsonArray array = new JsonArray();
            residualBits.forEach(array::add);
            out.add("residualBits", array);
        }
        return out;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    // ---- Shared by the kinds ------------------------------------------------------------------------------------------

    static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-5 * Math.max(1, Math.abs(a));
    }

    static double round(double d) {
        return Math.round(d * 1e6) / 1e6;
    }

    /** A number as the {@code value (type)} literal of a leaf of the NBT type. */
    static String typed(double v, String nbt) {
        return switch (nbt) {
            case "byte" -> (byte) (long) v + " (byte)";
            case "short" -> (short) (long) v + " (short)";
            case "int" -> (int) (long) v + " (int)";
            case "long" -> (long) v + " (long)";
            case "float" -> (float) v + " (float)";
            case "double" -> v + " (double)";
            case "string" -> Docs.number(v) + " (string)";
            default -> null;
        };
    }
}
