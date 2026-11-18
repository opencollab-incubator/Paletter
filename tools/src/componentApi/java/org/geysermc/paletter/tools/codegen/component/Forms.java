package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Shorthand;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Type;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Window;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.codegen.component.ModelFields.DocField;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The value forms of a component besides its object: synthetic fields, shorthands of a field, list entries, keyed extras.
 */
final class Forms {
    private final Hand hand;
    private final TypeBuilder types;
    private final Limits limits;

    Forms(Hand hand, TypeBuilder types, Limits limits) {
        this.hand = hand;
        this.types = types;
        this.limits = limits;
    }

    void build(Comp c, List<DocField> fields, List<String> forms) {
        boolean objectForm = forms.contains("object") && (!fields.isEmpty() || forms.size() == 1);
        List<String> scalarForms = forms.stream().filter(f -> !f.equals("object")).toList();
        for (String form : scalarForms) {
            if (form.startsWith("array<object")) {
                // The value is a list of objects: one list field holding them
                Field list = synthetic(c, form, fields);
                c.root.fields.add(list);
                continue;
            }
            JsonObject declared = hand.api("forms").has(c.id) && hand.api("forms").getAsJsonObject(c.id).has(form)
                ? hand.api("forms").getAsJsonObject(c.id).getAsJsonObject(form) : null;
            if (declared != null && declared.has("field")) {
                c.pendingForms.put(form, declared);
            } else if (!objectForm) {
                c.pendingForms.put(form, null);
            } else {
                c.pendingForms.put(form, null);
                c.autoShorthandForms.add(form);
            }
        }
        // Object fields
        for (Field f : types.children(c, "", fields, c.root)) {
            c.root.fields.add(f);
        }
        limits.orders(c, c.root);
        limits.conflicts(c, c.root);
        keyedExtras(c);
        // Forms: synthetic fields, or shorthands of a field
        for (Map.Entry<String, JsonObject> form : c.pendingForms.entrySet()) {
            String type = form.getKey();
            JsonObject declared = form.getValue();
            if (c.autoShorthandForms.contains(type)) {
                Field target = shorthandTarget(c, type);
                if (target != null) {
                    c.shorthands.add(new Shorthand(type, target));
                }
                continue;
            }
            Field field = new Field();
            field.form = type;
            field.json = null;
            field.path = "<" + type + ">";
            field.java = declared != null ? declared.get("field").getAsString() : scalarForms.size() == 1 ? "value" : null;
            if (field.java == null) {
                throw new GenerationException(c.id + " has the value forms " + scalarForms + " but no object fields; name a field per form in api.json forms");
            }
            if (declared != null && declared.has("shorthandOf")) {
                String of = declared.get("shorthandOf").getAsString();
                Field target = c.root.fields.stream().filter(f -> f.java.equals(of)).findFirst()
                    .orElseThrow(() -> new GenerationException("api.json forms: " + c.id + " " + type + " is a shorthand of '" + of + "', which is no field"));
                c.shorthands.add(new Shorthand(type, target));
                continue;
            }
            field.type = types.scalarType(c, type, field.java, List.of());
            if (declared != null && declared.has("type")) {
                field.type = types.scalarType(c, declared.get("type").getAsString(), field.java, List.of());
            }
            field.mappingKey = "form:" + type;
            field.doc = declared != null && declared.has("doc") ? declared.get("doc").getAsString()
                : "The " + type + " form of the component (add-on JSON {@code \"" + c.name + "\": <" + type + ">}).";
            field.javadoc = true;
            field.transform = hand.transform(c.id, field.path);
            // The limits BDS gives the component's value itself
            limits.limits(c, field, "", null, List.of(), c.latest() == null ? null : c.latest().form(type));
            boolean only = scalarForms.size() == 1 && c.root.fields.isEmpty();
            field.required = only;
            c.root.fields.add(field);
            c.shorthands.add(new Shorthand(type, field));
        }
        elementForms(c, forms);
    }

    /**
     * api.json forms {@code {"object": {"listElementOf": "boxes"}}}: the component's value is a list of objects, and an add-on
     * may also write one object for the list of one entry. The API has the list only; the form is an {@code of(Entry)}
     * factory, and the conformance binder reads it from the index.
     */
    void elementForms(Comp c, List<String> forms) {
        JsonObject declared = hand.api("forms").has(c.id) ? hand.api("forms").getAsJsonObject(c.id) : new JsonObject();
        for (String form : declared.keySet()) {
            JsonObject d = declared.getAsJsonObject(form);
            if (!d.has("listElementOf")) {
                continue;
            }
            String list = d.get("listElementOf").getAsString();
            if (!forms.contains(form)) {
                throw new GenerationException("api.json forms: " + c.id + " has no " + form + " value form for listElementOf");
            }
            c.root.fields.stream().filter(f -> f.java.equals(list) && f.type instanceof ListOf l && l.element() instanceof ObjRef).findFirst()
                .orElseThrow(() -> new GenerationException("api.json forms: " + c.id + " " + form + " is an entry of '" + list + "', which is no list of objects"));
            c.elementForms.put(form, list);
        }
    }

    /**
     * api.json {@code keyedExtras}: the fields of a keyed set ({@code *} in the docs) are named by the caller, so next to the
     * fixed fields the docs list (here {@code all}, {@code up}, ...) the component gets a map for any other key (of
     * every form of the field, when it is a union).
     */
    void keyedExtras(Comp c) {
        if (!hand.api("keyedExtras").has(c.id)) {
            return;
        }
        JsonObject extra = hand.api("keyedExtras").getAsJsonObject(c.id);
        Field base = c.root.fields.stream().filter(f -> f.java.equals(extra.get("from").getAsString())).findFirst()
            .orElseThrow(() -> new GenerationException("api.json keyedExtras: " + c.id + " has no field " + extra.get("from").getAsString()));
        String doc = extra.get("doc").getAsString();
        Field map = new Field();
        map.path = base.path;
        map.json = base.json;
        map.java = extra.get("field").getAsString();
        map.type = new MapOf(base.type);
        map.defaultValue = new JsonObject();
        map.mappingKey = base.mappingKey;
        map.transform = base.transform;
        map.keyedExtraOf = base;
        // The keys the other fields of the same type are named by have their own fields
        map.reservedKeys = c.root.fields.stream().filter(f -> f.keyedExtraOf == null && f.json != null && sameKind(f.type, base.type))
            .map(f -> f.json).toList();
        map.doc = doc;
        map.javadoc = true;
        c.root.fields.add(map);
    }

    static boolean sameKind(Type a, Type b) {
        return a instanceof ApiModel.UnionRef x && b instanceof ApiModel.UnionRef y ? x.union() == y.union() : a.equals(b);
    }

    /** The field a scalar component form fills: the one whose value rules move the same leaves. */
    Field shorthandTarget(Comp c, String form) {
        JsonObject declared = hand.api("forms").has(c.id) ? hand.api("forms").getAsJsonObject(c.id) : null;
        if (declared != null && declared.has(form) && declared.getAsJsonObject(form).has("shorthandOf")) {
            String of = declared.getAsJsonObject(form).get("shorthandOf").getAsString();
            return c.root.fields.stream().filter(f -> of.equals(f.java)).findFirst()
                .orElseThrow(() -> new GenerationException("api.json forms: " + c.id + " " + form + " is a shorthand of '" + of + "', which is no field"));
        }
        // The newest window that observed the form
        Window w = null;
        for (Window candidate : c.windows) {
            if (candidate.sent() && candidate.form(form) != null) {
                w = candidate;
            }
        }
        Set<String> leaves = w == null ? Set.of() : WireRules.valueLeaves(w.form(form));
        if (!leaves.isEmpty()) {
            for (Field f : c.root.fields) {
                JsonObject fm = w.field(f.path);
                if (fm != null && WireRules.valueLeaves(fm).equals(leaves)) {
                    return f;
                }
            }
        }
        if (c.root.fields.size() == 1) {
            return c.root.fields.getFirst();
        }
        if (c.neverSent()) {
            // Nothing was observed for the form; the docs name no field for it: it is not offered as a shorthand
            return null;
        }
        throw new GenerationException(c.id + ": the " + form + " form moves " + leaves + ", which no single field does; "
            + "declare it in api.json forms");
    }

    Field synthetic(Comp c, String form, List<DocField> fields) {
        String name = hand.apiString("rootLists", c.id);
        if (name == null) {
            throw new GenerationException(c.id + " is a list of objects (" + form + "); name its list field in api.json rootLists");
        }
        Field list = new Field();
        list.path = "";
        list.json = null;
        list.form = form;
        list.java = name;
        list.mappingKey = "form:" + form;
        list.transform = hand.transform(c.id, "[]");
        list.doc = "The entries of the component (add-on JSON: the component's value is this list).";
        list.javadoc = true;
        ObjType element = new ObjType();
        element.path = "[]";
        element.name = types.typeName(c, "[]", null);
        element.doc = "An entry of {@code " + c.name + "}.";
        element.javadoc = true;
        element.fields.addAll(types.children(c, "[]", fields, element));
        list.type = new ListOf(new ObjRef(element));
        list.defaultValue = new JsonArray();
        types.register(c, element);
        return list;
    }
}
