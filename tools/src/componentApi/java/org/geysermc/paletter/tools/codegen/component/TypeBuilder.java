package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Scalar;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ScalarKind;
import org.geysermc.paletter.tools.codegen.component.ApiModel.States;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Type;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Vec3;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Window;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.codegen.component.ModelFields.DocField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Java type of each field: scalars, enums, objects, lists, maps and unions, from the docs' types and the api.json decisions.
 */
final class TypeBuilder {
    private final Hand hand;
    private final ApiModel model;
    private final Limits limits;
    private final Unions unions;

    TypeBuilder(Hand hand, ApiModel model, Limits limits) {
        this.hand = hand;
        this.model = model;
        this.limits = limits;
        this.unions = new Unions(hand, model, this);
    }

    List<Field> children(Comp c, String prefix, List<DocField> fields, ObjType owner) {
        List<Field> out = new ArrayList<>();
        for (DocField f : fields) {
            if (f.path.equals("[]") || f.path.equals("{}") || f.path.equals(prefix)) {
                continue;
            }
            String parent = ModelFields.parent(f.path);
            if (!parent.equals(prefix) || (prefix.isEmpty() && f.path.startsWith("[]"))) {
                continue;
            }
            out.add(field(c, f, fields, owner));
        }
        return out;
    }

    Field field(Comp c, DocField d, List<DocField> fields, ObjType owner) {
        Field f = new Field();
        f.path = d.path;
        f.json = ModelFields.token(d.path);
        String renamed = hand.apiString("fields", c.id + ":" + d.path);
        f.java = renamed != null ? renamed : JavaNames.camel(f.json);
        JavaNames.javaName(f.java, c.id + " " + d.path);
        f.doc = d.doc;
        f.minFormat = d.minFormat;
        f.sincePreview = c.sincePreview != null ? null : Windows.sincePreview(model, c, w -> {
            JsonObject field = w.field(c.standalone ? c.standalonePath + "." + d.path : d.path);
            return field == null || !field.has("status") ? null : field.get("status").getAsString();
        });
        // A standalone type's fields are below its object in the definition's mappings (menu_category.category)
        f.mappingKey = c.standalone ? c.standalonePath + "." + d.path : d.path;
        f.transform = hand.transform(c.id, d.path);
        f.descriptor = hand.apiString("descriptors", c.id + ":" + d.path);
        f.type = type(c, d, fields);
        if (isOpenEnum(c, d) && !d.enumValues.isEmpty() && f.type instanceof Scalar s && s.kind() == ScalarKind.STRING) {
            f.documentedValues = d.enumValues.stream().map(JsonElement::getAsString).toList();
        }
        f.docsDefault = d.defaultValue;
        String feedbackPath = c.standalone ? hand.standalonePrefix(c.id) + "." + d.path : d.path;
        List<String> notes = hand.notes(c, feedbackPath);
        // Required unless BDS accepted the field's omission (it then knows what to send)
        Window latest = c.latest();
        JsonObject observed = latest == null ? null : latest.field(d.path);
        boolean omissionAccepted = observed != null && observed.has("whenAbsent");
        boolean optional = hand.listed("optional", c.id + ":" + d.path);
        f.required = !optional && (d.required || (notes.contains("required") && !omissionAccepted));
        limits.limits(c, f, feedbackPath, d.range, d.enumValues, observed);
        return f;
    }

    Type type(Comp c, DocField d, List<DocField> fields) {
        String override = hand.apiString("types", c.id + ":" + d.path);
        List<JsonObject> types = d.types;
        if (override != null) {
            if (override.equals("states")) {
                return new States();
            }
            if (override.startsWith("component:")) {
                return new ApiModel.ComponentRef(override.substring("component:".length()));
            }
            types = List.of(ModelFields.parseType(override));
        }
        JsonObject union = hand.api("unions").has(c.id + ":" + d.path) ? hand.api("unions").getAsJsonObject(c.id + ":" + d.path) : null;
        if (union != null && union.has("name")) {
            return unions.unionType(c, d, fields, types, union);
        }
        if (union != null && union.has("use")) {
            types = List.of(ModelFields.parseType(union.get("use").getAsString()));
        }
        if (override != null && override.startsWith("component:")) {
            return new ApiModel.ComponentRef(override.substring("component:".length()));
        }
        boolean hasChildren = fields.stream().anyMatch(f -> ModelFields.parent(f.path).equals(d.path) || ModelFields.parent(f.path).equals(d.path + "[]"));
        if (types.size() > 1) {
            List<String> kinds = types.stream().map(ModelFields::typeString).toList();
            boolean vecChildren = fields.stream().filter(f -> ModelFields.parent(f.path).equals(d.path)).map(f -> ModelFields.token(f.path)).toList().equals(List.of("x", "y", "z"));
            if (vecChildren && kinds.stream().anyMatch(k -> k.equals("vec3") || k.equals("array<number>"))) {
                c.usesVec3 = true;
                return new Vec3();
            }
            JsonObject object = types.stream().filter(t -> t.get("type").getAsString().equals("object")).findFirst().orElse(null);
            JsonObject array = types.stream().filter(t -> t.get("type").getAsString().equals("array") && t.has("items")
                && t.getAsJsonObject("items").get("type").getAsString().equals("object")).findFirst().orElse(null);
            List<JsonObject> scalars = types.stream().filter(t -> isScalar(t.get("type").getAsString())).toList();
            if (array != null && fields.stream().anyMatch(f -> ModelFields.parent(f.path).equals(d.path + "[]"))) {
                // [{...}] next to shorthand forms of one entry: the list is the type
                return single(c, d, array, fields);
            }
            if (object != null && hasChildren && types.stream().allMatch(x -> isScalar(x.get("type").getAsString())
                || x.get("type").getAsString().equals("object") || x.get("type").getAsString().equals("map"))) {
                ObjRef ref = (ObjRef) single(c, d, object, fields);
                if (!scalars.isEmpty()) {
                    // The scalar alternative is a shorthand of one child
                    ref.type().shorthand = unions.unionShorthand(c, d, ref.type(), union);
                    ref.type().shorthandType = scalars.getFirst().get("type").getAsString();
                }
                return ref;
            }
            JsonObject map = types.stream().filter(t -> t.get("type").getAsString().equals("map")).findFirst().orElse(null);
            if (map != null && !hasChildren && kinds.stream().allMatch(k -> k.equals("object") || k.startsWith("map<"))) {
                // An object without documented fields next to a typed map: the map
                return single(c, d, map, fields);
            }
            if (kinds.contains("string") && kinds.stream().allMatch(k -> k.equals("string") || k.startsWith("map<string"))) {
                return scalarType(c, "string", JavaNames.camel(ModelFields.token(d.path)), d.enumValues);
            }
            throw new GenerationException("ambiguous union: " + c.id + " " + d.path + " is " + String.join(" | ", kinds)
                + "; settle it in api.json unions");
        }
        return single(c, d, types.getFirst(), fields);
    }

    static boolean isScalar(String type) {
        return switch (type) {
            case "string", "boolean", "integer", "number" -> true;
            default -> false;
        };
    }

    Type single(Comp c, DocField d, JsonObject t, List<DocField> fields) {
        String type = t.get("type").getAsString();
        String name = JavaNames.camel(ModelFields.token(d.path));
        return switch (type) {
            case "boolean", "integer", "number", "string" -> scalarType(c, type, name, enumValuesOf(c, d));
            case "vec3" -> {
                c.usesVec3 = true;
                yield new Vec3();
            }
            case "object" -> {
                boolean children = fields.stream().anyMatch(f -> ModelFields.parent(f.path).equals(d.path));
                if (!children) {
                    throw new GenerationException(c.id + " " + d.path + " is an object without documented fields; type it in api.json types");
                }
                ObjType o = new ObjType();
                o.path = d.path;
                o.name = typeName(c, d.path, t.has("ref") ? t.get("ref").getAsString() : null);
                o.doc = d.doc;
                o.fields.addAll(children(c, d.path, fields, o));
                o.blockDescriptor = blockDescriptor(c.id + ":" + d.path);
                register(c, o);
                yield new ObjRef(o);
            }
            case "array" -> {
                JsonObject items = t.getAsJsonObject("items");
                String itemType = items.get("type").getAsString();
                if (itemType.equals("object")) {
                    ObjType o = new ObjType();
                    o.path = d.path + "[]";
                    o.name = typeName(c, d.path + "[]", items.has("ref") ? items.get("ref").getAsString() : null);
                    o.doc = d.doc;
                    o.fields.addAll(children(c, d.path + "[]", fields, o));
                    if (o.fields.isEmpty()) {
                        throw new GenerationException(c.id + " " + d.path + " is a list of objects without documented fields; type it in api.json types");
                    }
                    o.blockDescriptor = blockDescriptor(c.id + ":" + d.path + "[]");
                    register(c, o);
                    yield new ListOf(new ObjRef(o));
                }
                if (itemType.equals("any") || itemType.equals("array")) {
                    throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + ModelFields.typeString(t) + "; exclude or type it in api.json");
                }
                yield new ListOf(scalarType(c, itemType, name, enumValuesOf(c, d)));
            }
            case "map" -> {
                JsonObject items = t.getAsJsonObject("items");
                String itemType = items.get("type").getAsString();
                if (!isScalar(itemType)) {
                    throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + ModelFields.typeString(t) + "; exclude or type it in api.json");
                }
                yield new MapOf(scalarType(c, itemType, name, List.of()));
            }
            default -> throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + ModelFields.typeString(t) + "; exclude or type it in api.json");
        };
    }

    boolean blockDescriptor(String key) {
        return hand.listed("blockDescriptors", key);
    }

    /**
     * The values a field's enum is made of: none for an open enum, and none where api.json {@code types} calls the field a
     * {@code string} (the Java type is the one named there, a String; {@code *.tint_method} has the docs' values in the
     * model, the same field of the single faces does not).
     */
    List<JsonElement> enumValuesOf(Comp c, DocField d) {
        return isOpenEnum(c, d) || "string".equals(hand.apiString("types", c.id + ":" + d.path)) ? List.of() : d.enumValues;
    }

    /** api.json {@code openEnums}: BDS takes (and answers) strings the docs do not list, so the field is a string. */
    boolean isOpenEnum(Comp c, DocField d) {
        return hand.listed("openEnums", c.id + ":" + d.path);
    }

    Type scalarType(Comp c, String type, String fieldName, List<JsonElement> enumValues) {
        return switch (type) {
            case "boolean" -> new Scalar(ScalarKind.BOOL);
            case "integer" -> new Scalar(ScalarKind.INT);
            case "number" -> new Scalar(ScalarKind.FLOAT);
            case "string" -> {
                if (enumValues.isEmpty()) {
                    yield new Scalar(ScalarKind.STRING);
                }
                yield new EnumRef(enumType(c, JavaNames.pascal(fieldName), enumValues));
            }
            default -> throw new GenerationException("unsupported type: " + c.id + " " + fieldName + " is " + type);
        };
    }

    EnumType enumType(Comp c, String name, List<JsonElement> values) {
        String renamed = hand.apiString("enums", c.id + ":" + name);
        if (renamed != null) {
            name = renamed;
        }
        List<String> strings = values.stream().map(JsonElement::getAsString).toList();
        EnumType existing = c.enums.get(name);
        if (existing != null) {
            if (existing.values.equals(strings)) {
                return existing;
            }
            throw new GenerationException("name collision: " + c.id + " has two enums named " + name + " with different values; rename one in api.json enums");
        }
        EnumType e = new EnumType();
        e.name = name;
        Set<String> constants = new LinkedHashSet<>();
        for (String value : strings) {
            String constant = JavaNames.constant(value);
            if (!constants.add(constant)) {
                throw new GenerationException("name collision: " + c.id + " enum " + name + " has two values named " + constant);
            }
            e.values.add(value);
            e.constants.add(constant);
        }
        c.enums.put(name, e);
        return e;
    }

    String typeName(Comp c, String path, String ref) {
        String renamed = hand.apiString("types", c.id + ":" + path + "#name");
        if (renamed == null && hand.in.api().has("typeNames") && hand.in.api().getAsJsonObject("typeNames").has(c.id + ":" + path)) {
            renamed = hand.in.api().getAsJsonObject("typeNames").get(c.id + ":" + path).getAsString();
        }
        if (renamed != null) {
            return renamed;
        }
        if (ref != null) {
            String cleaned = ref.replaceAll("\\s*\\(.*\\)", "").trim();
            if (!cleaned.isEmpty() && cleaned.matches("[A-Za-z][A-Za-z0-9 ]*")) {
                return JavaNames.pascal(cleaned.replace(' ', '_'));
            }
        }
        String t = path.endsWith("[]") ? ModelFields.token(path.substring(0, path.length() - 2)) : ModelFields.token(path);
        if (t.isEmpty() || t.equals("[]")) {
            t = "entry";
        }
        if (!t.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new GenerationException(c.id + " " + path + " has no usable type name; set one in api.json typeNames");
        }
        return JavaNames.pascal(t);
    }

    void register(Comp c, ObjType o) {
        // Same name: allowed only for the same structure (merged later)
        c.allTypes.add(o);
        limits.orders(c, o);
        limits.conflicts(c, o);
    }
}
