package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ModelFields.DocField;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sealed types of the fields that take several JSON forms (api.json {@code unions}).
 */
final class Unions {
    private final Hand hand;
    private final ApiModel model;
    private final TypeBuilder builder;

    Unions(Hand hand, ApiModel model, TypeBuilder builder) {
        this.hand = hand;
        this.model = model;
        this.builder = builder;
    }

    /**
     * A field that takes several JSON forms (api.json {@code unions}: {@code name} of the sealed type, {@code variants} the
     * record name of each JSON type, sorted by it): the forms named must be forms the model has
     * (the others it lists are left out, as api.json {@code use} did), an integer standing for a number; a form the docs
     * leave out (the tags of a list of items) can be named too, unless it is an object, every field of the same type name
     * must have the same forms, and the type is nested in the component when a form is one of its own types.
     */
    Type unionType(Comp c, DocField d, List<DocField> fields, List<JsonObject> types, JsonObject spec) {
        String where = c.id + ":" + d.path;
        String name = spec.get("name").getAsString();
        JsonObject named = spec.getAsJsonObject("variants");
        Set<String> documented = new LinkedHashSet<>(types.stream().map(t -> family(ModelFields.typeString(t))).toList());
        if (named.keySet().stream().noneMatch(k -> documented.contains(family(k)))) {
            throw new GenerationException("api.json unions: " + where + " is " + String.join(" | ", documented) + " in the model, which "
                + "has none of the forms it names (" + String.join(" | ", named.keySet()) + ")");
        }
        ApiModel.UnionType union = new ApiModel.UnionType();
        union.name = name;
        union.kind = c.kind;
        ObjRef object = null;
        // By record name: the order of the keys in api.json carries no meaning
        for (Map.Entry<String, JsonElement> e : named.entrySet().stream().sorted(Comparator.comparing(x -> x.getValue().isJsonObject() ? x.getValue().getAsJsonObject().get("name").getAsString() : x.getValue().getAsString())).toList()) {
            JsonObject form = types.stream().filter(t -> family(ModelFields.typeString(t)).equals(family(e.getKey()))).findFirst().orElse(null);
            Type type;
            if (form == null && e.getKey().equals("object")) {
                throw new GenerationException("api.json unions: " + where + " names an object form, which the model does not have");
            }
            if (form != null && form.get("type").getAsString().equals("object")) {
                object = (ObjRef) builder.single(c, d, form, fields);
                type = object;
            } else {
                type = variantType(c, e.getKey(), JavaNames.camel(ModelFields.token(d.path)));
            }
            // A record name, or {name, doc}
            String variant = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("name").getAsString() : e.getValue().getAsString();
            String meaning = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("doc").getAsString() : null;
            union.variants.add(new ApiModel.Variant(variant, e.getKey(), meaning, type));
        }
        if (spec.has("noOverload")) {
            spec.getAsJsonArray("noOverload").forEach(n -> union.noOverload.add(n.getAsString()));
            if (!union.noOverload.stream().allMatch(n -> union.variants.stream().anyMatch(v -> v.name.equals(n)))) {
                throw new GenerationException("api.json unions: " + where + " noOverload names a form the union does not have");
            }
        }
        union.descriptor = hand.apiString("descriptors", where);
        boolean own = union.variants.stream().anyMatch(v -> v.type instanceof ObjRef || v.type instanceof EnumRef || v.type instanceof Vec3);
        union.owner = own ? c : null;
        ApiModel.UnionType known = model.unions.get(name);
        if (known == null) {
            model.unions.put(name, union);
            known = union;
        } else {
            if (!shape(known).equals(shape(union))) {
                throw new GenerationException("name collision: the union " + name + " has the forms " + shape(known) + " and, at " + where + ", "
                    + shape(union) + "; give one of them another name in api.json unions");
            }
            if (known.kind != c.kind || known.owner != union.owner && (known.owner != null || union.owner != null)
                || known.owner != null && known.owner != c) {
                throw new GenerationException("the union " + name + " is declared in " + (known.owner == null ? known.kind : known.owner.id)
                    + " and used at " + where + "; a union with a type of its component cannot be shared, name it differently");
            }
        }
        known.users.add(where);
        return new ApiModel.UnionRef(known, object);
    }

    /** A JSON type for comparing forms: an integer is a number (the model says {@code array<number>}, a color has integers). */
    static String family(String type) {
        return type.replace("integer", "number");
    }

    static String shape(ApiModel.UnionType u) {
        return u.variants.stream().map(v -> v.name + "=" + v.json).toList() + " without overloads for " + u.noOverload;
    }

    Field unionShorthand(Comp c, DocField d, ObjType object, JsonObject union) {
        if (union != null && union.has("shorthand")) {
            String name = union.get("shorthand").getAsString();
            return object.fields.stream().filter(f -> f.json.equals(name)).findFirst()
                .orElseThrow(() -> new GenerationException("api.json unions: " + c.id + " " + d.path + " shorthand '" + name + "' is no field of it"));
        }
        Window w = c.evidence(d.path);
        JsonObject own = w == null ? null : w.field(d.path);
        Set<String> leaves = own == null ? Set.of() : WireRules.valueLeaves(own);
        if (!leaves.isEmpty()) {
            for (Field child : object.fields) {
                JsonObject m = w.field(child.path);
                if (m != null && !WireRules.valueLeaves(m).isEmpty() && leaves.containsAll(WireRules.valueLeaves(m))) {
                    return child;
                }
            }
        }
        for (String name : List.of("name", "identifier", "value")) {
            for (Field child : object.fields) {
                if (child.json.equals(name)) {
                    return child;
                }
            }
        }
        throw new GenerationException("ambiguous union: " + c.id + " " + d.path + " has a scalar form that fills no single field of "
            + object.name + "; settle it in api.json unions");
    }

    /** The type of a union variant that is no object: a scalar ({@code string}) or a list of scalars ({@code array<string>}). */
    Type variantType(Comp c, String type, String fieldName) {
        JsonObject parsed = ModelFields.parseType(type);
        if (parsed.get("type").getAsString().equals("array")) {
            return new ListOf(builder.scalarType(c, parsed.getAsJsonObject("items").get("type").getAsString(), fieldName, List.of()));
        }
        return builder.scalarType(c, type, fieldName, List.of());
    }
}
