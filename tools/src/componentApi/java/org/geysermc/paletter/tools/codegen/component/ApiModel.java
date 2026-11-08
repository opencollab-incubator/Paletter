package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resolved model the sources are written from: components with their Java types, defaults, validation and wire rules.
 */
final class ApiModel {
    enum Kind {
        ITEM, BLOCK, TRAIT;

        String pkg() {
            return this == ITEM ? "org.geysermc.paletter.item.component" : "org.geysermc.paletter.block.component";
        }

        String iface() {
            return switch (this) {
                case ITEM -> "ItemComponent";
                case BLOCK -> "BlockComponent";
                case TRAIT -> "BlockTrait";
            };
        }
    }

    final List<Comp> components = new ArrayList<>();
    /** Components not generated, by reason (server only, rejected, ...): listed in the docs of the interfaces. */
    final Map<String, String> omitted = new LinkedHashMap<>();

    static final class Comp {
        String id;
        String name;
        Kind kind;
        String className;
        String doc;
        String page;
        String status;
        String minFormat;
        List<String> experiments = List.of();
        ObjType root;
        /** Non-object value forms: JSON type -> field they set ({@code of(...)} factories). */
        final List<Shorthand> shorthands = new ArrayList<>();
        /** Component level transform (transforms.json path ""), or null. */
        String transform;
        final List<Window> windows = new ArrayList<>();
        /** Enums declared in the component, by name. */
        final Map<String, EnumType> enums = new LinkedHashMap<>();
        /** Nested record types, by name (root excluded). */
        final Map<String, ObjType> types = new LinkedHashMap<>();
        /** Vec3 used: the component declares a nested Vec3 record. */
        boolean usesVec3;
        /** Not a component: a value type of the definition core (e.g. the menu category of the description). */
        boolean standalone;
        /** Scalar forms while building: JSON type -> api.json declaration (null: automatic). */
        final Map<String, JsonObject> pendingForms = new LinkedHashMap<>();
        final List<String> autoShorthandForms = new ArrayList<>();
        /** Every object type created, before merging the identical ones. */
        final List<ObjType> allTypes = new ArrayList<>();

        boolean deprecated() {
            return "deprecated".equals(status);
        }
    }

    /** A protocol window: the mapping observed for [since, until). */
    record Window(int since, int until, JsonObject mapping) {
        boolean bounded() {
            return since != Integer.MIN_VALUE || until != Integer.MAX_VALUE;
        }

        Map<String, String> constants() {
            Map<String, String> out = new java.util.TreeMap<>();
            if (mapping.has("constants")) {
                mapping.getAsJsonObject("constants").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
            }
            return out;
        }

        JsonObject field(String path) {
            JsonObject fields = mapping.has("fields") ? mapping.getAsJsonObject("fields") : new JsonObject();
            return fields.has(path) ? fields.getAsJsonObject(path) : null;
        }

        JsonObject form(String type) {
            JsonObject forms = mapping.has("forms") ? mapping.getAsJsonObject("forms") : new JsonObject();
            return forms.has(type) ? forms.getAsJsonObject(type) : null;
        }
    }

    record Shorthand(String jsonType, Field field) {
    }

    static final class ObjType {
        String name;
        String doc;
        /** JSON path of the object (prefix of its fields), e.g. {@code damage_chance} or {@code destroy_speeds[]}. */
        String path;
        final List<Field> fields = new ArrayList<>();
        /** A block descriptor ({name, states, tags}): gets constant factories. */
        boolean blockDescriptor;
        /** Shorthand of the object (a scalar alternative of the field that holds it): child it sets. */
        Field shorthand;
        String shorthandType;
    }

    sealed interface Type permits Scalar, EnumRef, ObjRef, Vec3, ListOf, MapOf, States, ComponentRef {
    }

    enum ScalarKind {
        BOOL("boolean", "Boolean"), INT("int", "Integer"), FLOAT("float", "Float"), STRING("String", "String");

        final String primitive;
        final String boxed;

        ScalarKind(String primitive, String boxed) {
            this.primitive = primitive;
            this.boxed = boxed;
        }
    }

    record Scalar(ScalarKind kind) implements Type {
    }

    record EnumRef(EnumType type) implements Type {
    }

    /**
     * @param type the record type (shared by every place with the same structure)
     * @param wire the object as it occurs here, whose fields carry this place's JSON paths and wire rules
     */
    record ObjRef(ObjType type, ObjType wire) implements Type {
        ObjRef(ObjType type) {
            this(type, type);
        }
    }

    /** A JSON {@code [x, y, z]} (or {@code {x, y, z}} object): the component's Vec3 record. */
    record Vec3() implements Type {
    }

    record ListOf(Type element) implements Type {
    }

    record MapOf(Type value) implements Type {
    }

    /** The record of another generated component of the same package (a component nested in another). */
    record ComponentRef(String className) implements Type {
    }

    /** Block descriptor states: state name to an int, string or boolean value. */
    record States() implements Type {
    }

    static final class EnumType {
        String name;
        final List<String> values = new ArrayList<>();
        final List<String> constants = new ArrayList<>();
    }

    static final class Field {
        String path;
        String json;
        String java;
        Type type;
        boolean required;
        /** The default the builder applies, as JSON; null if the field is nullable without one. */
        JsonElement defaultValue;
        JsonElement docsDefault;
        String defaultNote;
        Double min;
        Double max;
        String rangeSource;
        List<Double> allowed;
        String doc = "";
        String minFormat;
        boolean deprecated;
        String transform;
        /** "item" or "block": overloads taking vanilla constants. */
        String descriptor;
        /** Synthetic field from a value form (no JSON key of its own), with that form's mapping. */
        String form;
        /** A union alternative kept as its own field next to the main one: the main field's java name. */
        Field alternativeOf;
        final List<Field> alternatives = new ArrayList<>();
        /** A map of the keys the fixed field {@code keyedExtraOf} does not name (api.json keyedExtras); same wire rules. */
        Field keyedExtraOf;
        /** Paths of the mapping fields whose rules apply to this field (its own path, or a form). */
        String mappingKey;

        boolean nullable() {
            return !required && defaultValue == null;
        }
    }
}
