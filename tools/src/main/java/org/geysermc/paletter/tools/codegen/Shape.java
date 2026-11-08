package org.geysermc.paletter.tools.codegen;

import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The union of all NBT values observed at one place of a component.
 */
public final class Shape {
    enum Kind {
        BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY, COMPOUND, LIST, ANY;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Kind of(Object value) {
            if (value instanceof Byte || value instanceof Boolean) {
                return BYTE;
            } else if (value instanceof Short) {
                return SHORT;
            } else if (value instanceof Integer) {
                return INT;
            } else if (value instanceof Long) {
                return LONG;
            } else if (value instanceof Float) {
                return FLOAT;
            } else if (value instanceof Double) {
                return DOUBLE;
            } else if (value instanceof String) {
                return STRING;
            } else if (value instanceof byte[]) {
                return BYTE_ARRAY;
            } else if (value instanceof int[]) {
                return INT_ARRAY;
            } else if (value instanceof long[]) {
                return LONG_ARRAY;
            } else if (value instanceof NbtMap) {
                return COMPOUND;
            } else if (value instanceof List<?>) {
                return LIST;
            }
            throw new IllegalArgumentException("Not an NBT value: " + value);
        }
    }

    Kind kind;
    /** Times this place held a value. */
    int count;
    /** For bytes: whether every observed value was 0 or 1. */
    boolean bool = true;
    /** For compounds: the union of their fields. */
    final Map<String, Shape> fields = new TreeMap<>();
    /** For lists: the union of their elements, null if every observed list was empty. */
    Shape element;
    /** Set when the parent sometimes lacked this field, or Geyser omits it. Not serialised by count. */
    boolean optional;
    /** Why the field is optional, if not because vanilla sometimes omits it. */
    String optionalReason;

    void observe(Object value) {
        Kind kind = Kind.of(value);
        if (this.kind == null) {
            this.kind = kind;
        } else if (this.kind != kind) {
            this.kind = Kind.ANY;
        }
        count++;
        switch (kind) {
            case BYTE -> {
                int number = value instanceof Boolean b ? (b ? 1 : 0) : (Byte) value;
                bool &= number == 0 || number == 1;
            }
            case COMPOUND -> ((NbtMap) value).forEach((key, child) -> fields.computeIfAbsent(key, k -> new Shape()).observe(child));
            case LIST -> {
                for (Object child : (List<?>) value) {
                    if (element == null) {
                        element = new Shape();
                    }
                    element.observe(child);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Marks fields that were not present in every observation as optional, recursively.
     */
    void finish() {
        for (Shape field : fields.values()) {
            if (field.count < count) {
                field.optional = true;
            }
            field.finish();
        }
        if (element != null) {
            element.finish();
        }
    }

    /**
     * @return a description of the first difference if {@code value} does not fit this shape, else null
     */
    String incompatibility(Object value, String path) {
        Kind kind = Kind.of(value);
        if (this.kind != Kind.ANY && this.kind != kind) {
            return path + ": wire " + this.kind.id() + ", sample " + kind.id();
        }
        if (kind == Kind.COMPOUND && this.kind == Kind.COMPOUND) {
            for (Map.Entry<String, Object> entry : ((NbtMap) value).entrySet()) {
                Shape field = fields.get(entry.getKey());
                if (field == null) {
                    return path + "." + entry.getKey() + ": not on the wire";
                }
                String problem = field.incompatibility(entry.getValue(), path + "." + entry.getKey());
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (kind == Kind.LIST && this.kind == Kind.LIST) {
            for (Object child : (List<?>) value) {
                if (element == null) {
                    return path + "[]: lists on the wire are always empty";
                }
                String problem = element.incompatibility(child, path + "[]");
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /**
     * Marks the fields a compatible sample omits as optional.
     */
    void relaxFor(Object value, String reason) {
        if (kind == Kind.COMPOUND && value instanceof NbtMap map) {
            fields.forEach((key, field) -> {
                Object child = map.get(key);
                if (child == null) {
                    if (!field.optional) {
                        field.optional = true;
                        field.optionalReason = reason;
                    }
                } else {
                    field.relaxFor(child, reason);
                }
            });
        } else if (kind == Kind.LIST && element != null && value instanceof List<?> list) {
            for (Object child : list) {
                element.relaxFor(child, reason);
            }
        }
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", kind.id());
        if (kind == Kind.BYTE && bool) {
            json.addProperty("bool", true);
        }
        if (optional) {
            json.addProperty("optional", true);
            if (optionalReason != null) {
                json.addProperty("optionalReason", optionalReason);
            }
        }
        if (kind == Kind.COMPOUND) {
            JsonObject children = new JsonObject();
            fields.forEach((key, field) -> children.add(key, field.toJson()));
            json.add("fields", children);
        }
        if (kind == Kind.LIST && element != null) {
            json.add("element", element.toJson());
        }
        return json;
    }

    static Shape fromJson(JsonObject json) {
        Shape shape = new Shape();
        shape.kind = Kind.valueOf(json.get("type").getAsString().toUpperCase(java.util.Locale.ROOT));
        shape.bool = json.has("bool") && json.get("bool").getAsBoolean();
        shape.optional = json.has("optional") && json.get("optional").getAsBoolean();
        if (json.has("optionalReason")) {
            shape.optionalReason = json.get("optionalReason").getAsString();
        }
        if (json.has("fields")) {
            json.getAsJsonObject("fields").entrySet().forEach(entry ->
                shape.fields.put(entry.getKey(), fromJson(entry.getValue().getAsJsonObject())));
        }
        if (json.has("element")) {
            shape.element = fromJson(json.getAsJsonObject("element"));
        }
        return shape;
    }

    /**
     * @return the union of two shapes, e.g. of the values of a compound with arbitrary keys
     */
    static Shape union(Shape a, Shape b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Shape shape = new Shape();
        shape.kind = a.kind == b.kind ? a.kind : Kind.ANY;
        shape.bool = a.bool && b.bool;
        shape.optional = a.optional || b.optional;
        shape.optionalReason = a.optionalReason != null ? a.optionalReason : b.optionalReason;
        for (String key : a.fields.keySet()) {
            shape.fields.put(key, union(a.fields.get(key), b.fields.get(key)));
        }
        for (String key : b.fields.keySet()) {
            shape.fields.computeIfAbsent(key, k -> b.fields.get(k));
        }
        // A field missing on either side is optional in the union
        shape.fields.forEach((key, field) -> {
            if (!a.fields.containsKey(key) || !b.fields.containsKey(key)) {
                field.optional = true;
            }
        });
        shape.element = union(a.element, b.element);
        return shape;
    }

    static boolean isList(Object value) {
        return value instanceof NbtList<?> || value instanceof List<?>;
    }
}
