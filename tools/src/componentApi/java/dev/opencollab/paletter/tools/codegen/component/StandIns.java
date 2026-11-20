package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ComponentRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.States;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import java.util.Map;
import java.util.Set;

/**
 * The stand-ins of what left the model, rebuilt from {@code api-history.json} ({@link ApiHistory}), so that a Bedrock
 * update costs users deprecation warnings, not compile errors (docs/api.md, "Evolution"):
 * <ul>
 *     <li>a removed field of a live component keeps a {@code @Deprecated(forRemoval = true)} accessor that returns the
 *     field's default and a builder setter that does nothing ({@link ObjType#removedFields});</li>
 *     <li>a removed component keeps a {@code @Deprecated(forRemoval = true)} class with such stand-ins for its fields, which
 *     the encoder leaves out with a report warning ({@link ApiModel#removedComponents}).</li>
 * </ul>
 * The nested types they need are rebuilt too: an open value class from the recorded constants, an object from the recorded
 * fields under its path. Generation reports each stand-in. A stand-in goes only when its history entries are deleted
 * deliberately, in a major release.
 */
final class StandIns {
    private final ApiModel model;
    private final ApiHistory history;

    private StandIns(ApiModel model, ApiHistory history) {
        this.model = model;
        this.history = history;
    }

    /**
     * Adds the stand-ins of the fields {@link ApiHistory#renames} found removed and of the components that left the model.
     *
     * @param reserved the simple names of the hand-written public types: a component that left the model for a
     *                 hand-written class of its name gets no stand-in
     */
    static void apply(ApiModel model, ApiHistory history, Set<String> reserved) {
        StandIns standIns = new StandIns(model, history);
        for (ApiHistory.RemovedField r : history.removedFields) {
            Field f = standIns.field(r.c(), r.key(), r.entry());
            for (Field live : r.owner().fields) {
                if (live.java.equals(f.java)) {
                    throw new GenerationException(r.key() + " left the model, but its name " + f.java + " is a field of "
                        + r.owner().name + " now; name one of them in api.json fields");
                }
            }
            r.owner().removedFields.add(f);
            System.out.println("warning: " + r.key() + " (" + f.java + ") left the model: kept as a @Deprecated(forRemoval = true)"
                + " stand-in that returns its default and ignores its setter, until its entry in data/model/" + ApiHistory.FILE
                + " is deleted in a major release");
        }
        for (String id : history.removedComponents()) {
            String className = history.recordedName("classes", id);
            if (reserved.contains(className)) {
                System.out.println(id + " (" + className + ") left the model for a hand-written class of its name: no stand-in");
                continue;
            }
            Comp c = standIns.component(id, className);
            model.removedComponents.add(c);
            System.out.println("warning: " + id + " (" + className + ") left the model: kept as a @Deprecated(forRemoval = true)"
                + " stand-in that the encoder leaves out with a report warning, until its entries in data/model/" + ApiHistory.FILE
                + " are deleted in a major release");
        }
    }

    /**
     * The model type of a field as {@code api-history.json} records it: {@code boolean}, {@code int}, {@code float},
     * {@code string}, {@code vec3}, {@code states}, {@code enum:<class>}, {@code object:<class>}, {@code union:<type>},
     * {@code component:<class>}, {@code list<...>} or {@code map<...>} (string keys).
     */
    static String describe(Type t) {
        return switch (t) {
            case Scalar s -> switch (s.kind()) {
                case BOOL -> "boolean";
                case INT -> "int";
                case FLOAT -> "float";
                case STRING -> "string";
            };
            case Vec3 v -> "vec3";
            case States s -> "states";
            case EnumRef e -> "enum:" + e.type().name;
            case ObjRef r -> "object:" + r.type().name;
            case UnionRef u -> "union:" + u.union().name;
            case ComponentRef r -> "component:" + r.className();
            case ListOf l -> "list<" + describe(l.element()) + ">";
            case MapOf m -> "map<" + describe(m.value()) + ">";
        };
    }

    /** The stand-in of a removed component: its class, with a stand-in for each of its recorded top-level fields. */
    private Comp component(String id, String className) {
        Comp c = new Comp();
        c.id = id;
        if (id.startsWith("block:trait:")) {
            c.kind = Kind.TRAIT;
            c.name = id.substring("block:trait:".length());
        } else if (id.startsWith("item:")) {
            c.kind = Kind.ITEM;
            c.name = id.substring("item:".length());
        } else if (id.startsWith("block:")) {
            c.kind = Kind.BLOCK;
            c.name = id.substring("block:".length());
        } else {
            throw new GenerationException(id + " left the model, but is no item or block component: delete its entries in data/model/"
                + ApiHistory.FILE + " deliberately, or bring it back");
        }
        if (!c.name.contains(":")) {
            throw new GenerationException(id + " left the model, but is a type of the definition core, not a component: delete its"
                + " entries in data/model/" + ApiHistory.FILE + " deliberately, or bring it back");
        }
        c.className = className;
        c.status = "removed";
        c.doc = "";
        c.page = "";
        c.removed = true;
        for (Comp live : model.components) {
            if (live.kind.pkg().equals(c.kind.pkg()) && live.className.equals(className)) {
                throw new GenerationException(id + " left the model, and " + live.id + " takes its class name " + className
                    + ": name one of them in api.json classes");
            }
        }
        c.root = new ObjType();
        c.root.name = className;
        c.root.path = "";
        c.root.doc = "";
        c.root.removed = true;
        removedFieldsOf(c, c.root);
        return c;
    }

    /** Adds a stand-in for every recorded field directly under an object of a component that left the model. */
    private void removedFieldsOf(Comp c, ObjType o) {
        JsonObject fields = history.recordedFields();
        // In the order of the written file, whatever the order of the file read
        for (String key : new java.util.TreeSet<>(fields.keySet())) {
            if (key.startsWith(c.id + ":") && ModelFields.parent(key.substring(c.id.length() + 1)).equals(o.path)
                && !fields.getAsJsonObject(key).has("renamedTo")) {
                o.removedFields.add(field(c, key, fields.getAsJsonObject(key)));
            }
        }
    }

    /** The stand-in of a recorded field: its name, model type and default. */
    private Field field(Comp c, String key, JsonObject entry) {
        if (!entry.has("type")) {
            throw new GenerationException(key + " left the model, but data/model/" + ApiHistory.FILE + " records no type for its"
                + " stand-in (an entry from before stand-ins): delete the entry deliberately, or bring the field back");
        }
        Field f = new Field();
        f.path = key.substring(c.id.length() + 1);
        f.json = f.path.substring(f.path.lastIndexOf('.') + 1);
        f.java = entry.get("name").getAsString();
        f.type = parse(c, key, entry.get("type").getAsString());
        f.defaultValue = entry.has("default") ? entry.get("default") : null;
        boolean nullable = entry.has("nullable") && entry.get("nullable").getAsBoolean();
        f.required = !nullable && f.defaultValue == null;
        return f;
    }

    private Type parse(Comp c, String key, String d) {
        if (d.startsWith("list<") && d.endsWith(">")) {
            return new ListOf(parse(c, key, d.substring(5, d.length() - 1)));
        }
        if (d.startsWith("map<") && d.endsWith(">")) {
            return new MapOf(parse(c, key, d.substring(4, d.length() - 1)));
        }
        int colon = d.indexOf(':');
        String kind = colon < 0 ? d : d.substring(0, colon);
        String name = colon < 0 ? null : d.substring(colon + 1);
        return switch (kind) {
            case "boolean" -> new Scalar(ScalarKind.BOOL);
            case "int" -> new Scalar(ScalarKind.INT);
            case "float" -> new Scalar(ScalarKind.FLOAT);
            case "string" -> new Scalar(ScalarKind.STRING);
            case "states" -> new States();
            case "vec3" -> {
                c.usesVec3 = true;
                yield new Vec3();
            }
            case "component" -> new ComponentRef(name);
            case "enum" -> new EnumRef(enumType(c, key, name));
            case "object" -> new ObjRef(objectType(c, key, name));
            case "union" -> {
                UnionType u = model.unions.get(name);
                if (u == null) {
                    throw new GenerationException(key + " left the model with its union " + name + ", which a stand-in cannot rebuild:"
                        + " delete the entry deliberately, or bring the field back");
                }
                yield new UnionRef(u, null);
            }
            default -> throw new GenerationException(key + ": data/model/" + ApiHistory.FILE + " records the unknown type " + d);
        };
    }

    /** The open value class of a component: the live one of the name, else rebuilt from the recorded constants. */
    private EnumType enumType(Comp c, String key, String name) {
        EnumType live = c.enums.get(name);
        if (live != null) {
            return live;
        }
        String identity = identity(c, key, "enums", name);
        EnumType e = new EnumType();
        e.name = name;
        for (Map.Entry<String, com.google.gson.JsonElement> constant : new java.util.TreeMap<>(history.recordedSection("constants").asMap()).entrySet()) {
            if (constant.getKey().startsWith(identity + "=")) {
                e.values.add(constant.getKey().substring(identity.length() + 1));
                e.constants.add(constant.getValue().getAsString());
            }
        }
        c.enums.put(name, e);
        return e;
    }

    /** The object type of a component: the live one of the name, else rebuilt with stand-ins for its recorded fields. */
    private ObjType objectType(Comp c, String key, String name) {
        ObjType live = c.types.get(name);
        if (live != null) {
            return live;
        }
        ObjType o = new ObjType();
        o.name = name;
        o.path = identity(c, key, "types", name).substring(c.id.length() + 1);
        o.doc = "";
        o.removed = true;
        c.types.put(name, o);
        removedFieldsOf(c, o);
        return o;
    }

    /** @return the identity ({@code <component id>:<JSON path>}) a section of the history records a nested class name for */
    private String identity(Comp c, String key, String section, String name) {
        for (Map.Entry<String, com.google.gson.JsonElement> e : history.recordedSection(section).entrySet()) {
            if (e.getKey().startsWith(c.id + ":") && e.getValue().isJsonPrimitive() && e.getValue().getAsString().equals(name)) {
                return e.getKey();
            }
        }
        throw new GenerationException(key + " left the model with its type " + name + ", which data/model/" + ApiHistory.FILE
            + " " + section + " does not record: delete the entry deliberately, or bring the field back");
    }
}
