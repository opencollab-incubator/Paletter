package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Scalar;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ScalarKind;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The limits BDS puts on the values of a field and between fields ({@code bds-feedback.json}), which the generated records
 * check: ranges, steps, orders of two numbers, values a field conflicts with.
 */
final class Limits {
    private final Hand hand;

    Limits(Hand hand) {
        this.hand = hand;
    }

    /**
     * The limits of a number field, which the record's compact constructor checks: the docs' range with the limits BDS
     * states (content log) over it; the values of an integer enum; the step of its rule (numbers and vectors). Only numbers have
     * the others.
     *
     * @param docsRange the docs' range of the field, or null
     * @param observed  the field's (or value form's) mapping in the newest window, or null
     */
    void limits(Comp c, Field f, String feedbackPath, JsonObject docsRange, List<JsonElement> enumValues, JsonObject observed) {
        if (observed != null && observed.has("rules")) {
            for (JsonElement rule : observed.getAsJsonArray("rules")) {
                if (rule.getAsJsonObject().has("multipleOf")) {
                    f.multipleOf = rule.getAsJsonObject().get("multipleOf").getAsDouble();
                }
            }
        }
        if (!(f.type instanceof Scalar s && (s.kind() == ScalarKind.INT || s.kind() == ScalarKind.FLOAT))) {
            return;
        }
        if (docsRange != null && docsRange.has("min")) {
            f.min = docsRange.get("min").getAsDouble();
            f.minSource = "docs";
        }
        if (docsRange != null && docsRange.has("max")) {
            f.max = docsRange.get("max").getAsDouble();
            f.maxSource = "docs";
        }
        // A limit BDS states wins over the docs' (a limit it does not state stays the docs': BDS refuses a value past it
        // some other way, or the conformance suite shows it accepted one)
        for (String note : hand.notes(c, feedbackPath)) {
            Matcher range = Pattern.compile("range (-?[\\d.]+) (-?[\\d.]+)").matcher(note);
            Matcher min = Pattern.compile("min (-?[\\d.]+)").matcher(note);
            Matcher max = Pattern.compile("max (-?[\\d.]+)").matcher(note);
            String bds = "BDS " + hand.bdsVersion();
            if (range.matches() || min.matches()) {
                f.min = Double.parseDouble((range.matches() ? range : min).group(1));
                f.minSource = bds;
            }
            if (range.matches() || max.matches()) {
                f.max = Double.parseDouble(range.matches() ? range.group(2) : max.group(1));
                f.maxSource = bds;
            }
        }
        if (s.kind() == ScalarKind.INT && !enumValues.isEmpty()) {
            f.allowed = enumValues.stream().map(JsonElement::getAsDouble).toList();
        }
    }

    /**
     * The order BDS demands between two number fields of an object ({@code bds-feedback.json}: {@code order <low> <high>}
     * on the object's path, from "max value cannot be lower than min value"): the record's constructor checks it.
     */
    void orders(Comp c, ObjType o) {
        for (String note : hand.notes(c, c.standalone && !o.path.isEmpty() ? hand.standalonePrefix(c.id) + "." + o.path : o.path)) {
            Matcher order = Pattern.compile("order (\\S+) (\\S+)").matcher(note);
            if (!order.matches()) {
                continue;
            }
            Field low = o.fields.stream().filter(f -> order.group(1).equals(f.json)).findFirst().orElse(null);
            Field high = o.fields.stream().filter(f -> order.group(2).equals(f.json)).findFirst().orElse(null);
            if (low != null && high != null && number(low) && number(high)) {
                high.notBelow = low;
                high.notBelowSource = "BDS " + hand.bdsVersion();
            }
        }
    }

    static boolean number(Field f) {
        return f.type instanceof Scalar s && (s.kind() == ScalarKind.INT || s.kind() == ScalarKind.FLOAT);
    }

    /**
     * The value of another field BDS refuses a field with ({@code bds-feedback.json}: {@code conflicts <path> '<value>'} on
     * the field's path, from "Cannot use bone visibility feature with: minecraft:geometry.full_block"): the record's
     * constructor checks it. For a map or a list, which BDS takes empty beside that value, and a string field of the same
     * object.
     */
    void conflicts(Comp c, ObjType o) {
        for (Field f : o.fields) {
            if (f.path == null || !(f.type instanceof MapOf || f.type instanceof ListOf)) {
                continue;
            }
            for (String note : hand.notes(c, hand.feedbackPath(c, f.path))) {
                Matcher conflict = Pattern.compile("conflicts (\\S+) '(.*)'").matcher(note);
                if (!conflict.matches()) {
                    continue;
                }
                o.fields.stream().filter(other -> other != f && other.path != null && hand.feedbackPath(c, other.path).equals(conflict.group(1))
                    && other.type instanceof Scalar s && s.kind() == ScalarKind.STRING).findFirst().ifPresent(other -> {
                        f.conflictsWith = other;
                        f.conflictValue = conflict.group(2);
                        f.conflictSource = "BDS " + hand.bdsVersion();
                    });
            }
        }
    }
}
