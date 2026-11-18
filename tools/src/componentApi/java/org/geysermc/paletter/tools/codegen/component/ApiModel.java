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
    /** The docs page of every component of the model, by id (also the omitted ones). */
    final Map<String, String> pages = new LinkedHashMap<>();
    /** The docs pages that list the components, by kind ({@code item}, {@code block}, {@code trait}). */
    final Map<String, String> lists = new LinkedHashMap<>();
    /** The replacement of deprecated or rejected components, by component id (generated or not). */
    final Map<String, Replacements.Replacement> replacements = new LinkedHashMap<>();
    /**
     * What BDS sends around the components of every custom item and block ({@code envelope} of {@code mappings.json}), by
     * kind ({@code item}, {@code block}): protocol windows, oldest first, each {@code {protocols, leaves}}.
     */
    final Map<String, com.google.gson.JsonArray> envelope = new LinkedHashMap<>();
    /** Protocol and BDS version of every capture, oldest first (the bounds of the windows are protocols). */
    final java.util.TreeMap<Integer, String> captureVersions = new java.util.TreeMap<>();
    /** The sealed union types by name (api.json {@code unions}), shared by every field of the same shape. */
    final Map<String, UnionType> unions = new LinkedHashMap<>();

    /** @return the generated component with an id, or null */
    Comp component(String id) {
        return components.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /** The first protocol of the window of every capture, by BDS version, oldest first (the bounds hand transforms check). */
    final Map<String, Integer> windowProtocols = new LinkedHashMap<>();
    /** The window protocol of every release capture, and of every preview capture with the preview's version name. */
    final java.util.Set<Integer> releaseProtocols = new java.util.TreeSet<>();
    final java.util.TreeMap<Integer, String> previewProtocols = new java.util.TreeMap<>();
    /** The oldest and the newest protocol a spec capture exists for (the window shapes are observed in between). */
    int oldestCaptured = Integer.MIN_VALUE;
    int newestCaptured = Integer.MAX_VALUE;
    /**
     * How BDS numbers custom blocks, the same in every spec capture ({@code mappings.json} captures): the first
     * {@code vanilla_block_data.block_id}, and the constant a block item's runtime id and its block id add up to. Null
     * without captures.
     */
    Integer firstCustomBlockId;
    Integer blockItemIdPlusBlockId;
    /** {@code mappings.json} {@code traitStates}: trait -> enabled_states value -> the states it adds. */
    JsonObject traitStates = new JsonObject();


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
        /** Object value forms that are one entry of a list field (api.json forms {@code listElementOf}): JSON type -> list field. */
        final Map<String, String> elementForms = new LinkedHashMap<>();
        /** Component level transform (the {@code Transforms} method of {@link HandTransforms} path ""), or null. */
        String transform;
        final List<Window> windows = new ArrayList<>();
        /** The window in which BDS accepts the component but sends nothing to clients, or null. */
        Window serverOnly;
        /** What replaces this component, or null. */
        Replacements.Replacement replacement;
        /** The window in which BDS rejects the component, from the first capture that does until the newest (null: it accepts it). */
        Window rejected;
        /** The bundled preview that alone has the component (every captured release rejects it), or null. */
        String sincePreview;

        /** @return the newest window that sends the component (the API follows its shape), or null */
        Window latest() {
            for (int i = windows.size() - 1; i >= 0; i--) {
                if (windows.get(i).sent()) {
                    return windows.get(i);
                }
            }
            return null;
        }

        /** Enums declared in the component, by name. */
        final Map<String, EnumType> enums = new LinkedHashMap<>();
        /** Nested record types, by name (root excluded). */
        final Map<String, ObjType> types = new LinkedHashMap<>();
        /** Vec3 used: the component declares a nested Vec3 record. */
        boolean usesVec3;
        /** Not a component: a value type of the definition core (e.g. the menu category of the description). */
        boolean standalone;
        /** The object path of a standalone type in the definition ({@code menu_category}), null for a component. */
        String standalonePath;
        /** Scalar forms while building: JSON type -> api.json declaration (null: automatic). */
        final Map<String, JsonObject> pendingForms = new LinkedHashMap<>();
        final List<String> autoShorthandForms = new ArrayList<>();
        /** Every object type created, before merging the identical ones. */
        final List<ObjType> allTypes = new ArrayList<>();

        boolean deprecated() {
            return "deprecated".equals(status);
        }

        /** @return whether no window sends the component (BDS only accepts or rejects it): its encoder writes nothing */
        boolean neverSent() {
            return latest() == null;
        }
    }

    /** A protocol window: the mapping observed for [since, until). */
    record Window(int since, int until, JsonObject mapping) {
        /** @return what BDS did with the component in this window: sent, untestable, server-only, rejected */
        String status() {
            return mapping.get("status").getAsString();
        }

        /** @return whether the component reaches the wire in this window; else its palettes leave it out and report it */
        boolean sent() {
            return status().equals("sent") || status().equals("untestable");
        }

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
        /** Whether {@link #doc} is Javadoc this generator or {@code api.json} wrote, not docs text to escape. */
        boolean javadoc;
        /** JSON path of the object (prefix of its fields), e.g. {@code damage_chance} or {@code destroy_speeds[]}. */
        String path;
        final List<Field> fields = new ArrayList<>();
        /** A block descriptor ({name, states, tags}): gets constant factories. */
        boolean blockDescriptor;
        /** Shorthand of the object (a scalar alternative of the field that holds it): child it sets. */
        Field shorthand;
        String shorthandType;
    }

    sealed interface Type permits Scalar, EnumRef, ObjRef, Vec3, ListOf, MapOf, States, ComponentRef, UnionRef {
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

    /**
     * A field that takes one of several JSON forms: the sealed type of the union, shared by every field of the same shape.
     *
     * @param union the sealed type
     * @param object the object variant as it occurs at this field (its own JSON paths and rules), or null if there is none
     */
    record UnionRef(UnionType union, ObjRef object) implements Type {
    }

    /** One JSON form of a union: its record's name, the JSON type it stands for ({@code array<string>}) and its Java type. */
    static final class Variant {
        final String name;
        final String json;
        Type type;

        Variant(String name, String json, Type type) {
            this.name = name;
            this.json = json;
            this.type = type;
        }
    }

    /**
     * A sealed interface with one record per JSON form (api.json {@code unions}). It is a file of its own in the package of
     * its components, or nested in {@link #owner} when a variant is a type of that component.
     */
    static final class UnionType {
        String name;
        Kind kind;
        /** The component that declares it (nested there), or null for a type of its own. */
        Comp owner;
        final List<Variant> variants = new ArrayList<>();
        /** Where it is used, for its documentation: component id and JSON path. */
        final List<String> users = new ArrayList<>();
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
        /** Where each limit comes from: {@code docs} or {@code BDS <build>}. */
        String minSource;
        String maxSource;
        /** The number field of the same object this one must not be below (BDS: "max value cannot be lower than min value"). */
        Field notBelow;
        String notBelowSource;
        /**
         * The string field of the same object whose value {@link #conflictValue} BDS refuses this map or list with, unless it
         * is empty ("Cannot use bone visibility feature with: minecraft:geometry.full_block").
         */
        Field conflictsWith;
        String conflictValue;
        String conflictSource;
        List<Double> allowed;
        /** The step BDS takes the values in (an inferred rule's {@code multipleOf}: it refused the others); null if none. */
        Double multipleOf;
        String doc = "";
        /** Whether {@link #doc} is Javadoc this generator or {@code api.json} wrote, not docs text to escape. */
        boolean javadoc;
        String minFormat;
        /** The bundled preview that alone has the field (every captured release rejects it), or null. */
        String sincePreview;
        boolean deprecated;
        String transform;
        /** "item" or "block": overloads taking vanilla constants. */
        String descriptor;
        /** The documented values of an open enum (api.json openEnums): the field is a string, the values are only documented. */
        List<String> documentedValues;
        /** Synthetic field from a value form (no JSON key of its own), with that form's mapping. */
        String form;
        /** A map of the keys the fixed field {@code keyedExtraOf} does not name (api.json keyedExtras); same wire rules. */
        Field keyedExtraOf;
        /** Paths of the mapping fields whose rules apply to this field (its own path, or a form). */
        String mappingKey;

        boolean nullable() {
            return !required && defaultValue == null;
        }
    }
}
