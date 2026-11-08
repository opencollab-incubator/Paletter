package org.geysermc.paletter.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.BlockTrait;
import org.geysermc.paletter.internal.component.ComponentIndex;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.ItemComponent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds add-on JSON through the generated API, the way a consumer would: every JSON object through its record's builder
 * (setters found by the JSON key of {@link ComponentIndex#JSON_KEYS}), value forms through the {@code of} factories or the
 * form fields. Nothing here knows a component; what the API cannot express fails with {@link Unbindable}.
 */
final class ApiBinder {
    static final class Unbindable extends RuntimeException {
        Unbindable(String message) {
            super(message);
        }
    }

    private ApiBinder() {
    }

    static CustomItem item(JsonObject file) {
        JsonObject item = file.getAsJsonObject("minecraft:item");
        JsonObject description = item.getAsJsonObject("description");
        CustomItem.Builder builder = CustomItem.builder(description.get("identifier").getAsString());
        if (description.has("menu_category")) {
            builder.menuCategory((org.geysermc.paletter.item.component.MenuCategory) bind(org.geysermc.paletter.item.component.MenuCategory.class,
                description.get("menu_category")));
        }
        for (Map.Entry<String, JsonElement> e : item.getAsJsonObject("components").entrySet()) {
            Class<?> type = ComponentIndex.ITEM.get(e.getKey());
            if (type == null) {
                throw new Unbindable("no generated item component " + e.getKey());
            }
            builder.component((ItemComponent) bind(type, e.getValue()));
        }
        return builder.build();
    }

    static CustomBlock block(JsonObject file) {
        JsonObject block = file.getAsJsonObject("minecraft:block");
        JsonObject description = block.getAsJsonObject("description");
        CustomBlock.Builder builder = CustomBlock.builder(description.get("identifier").getAsString());
        for (Map.Entry<String, JsonElement> e : description.entrySet()) {
            switch (e.getKey()) {
                case "identifier" -> {
                }
                case "menu_category" -> builder.menuCategory((org.geysermc.paletter.block.component.MenuCategory) bind(
                    org.geysermc.paletter.block.component.MenuCategory.class, e.getValue()));
                case "states" -> e.getValue().getAsJsonObject().entrySet().forEach(s -> builder.property(property(s.getKey(), s.getValue())));
                case "traits" -> {
                    for (Map.Entry<String, JsonElement> t : e.getValue().getAsJsonObject().entrySet()) {
                        Class<?> type = ComponentIndex.TRAIT.get(t.getKey());
                        if (type == null) {
                            throw new Unbindable("no generated trait " + t.getKey());
                        }
                        builder.trait((BlockTrait) bind(type, t.getValue()));
                    }
                }
                default -> throw new Unbindable("description key " + e.getKey());
            }
        }
        if (block.has("components")) {
            builder.components(blockComponents(block.getAsJsonObject("components")).toArray(BlockComponent[]::new));
        }
        if (block.has("permutations")) {
            for (JsonElement p : block.getAsJsonArray("permutations")) {
                JsonObject permutation = p.getAsJsonObject();
                for (String key : permutation.keySet()) {
                    if (!key.equals("condition") && !key.equals("components")) {
                        throw new Unbindable("permutation key " + key);
                    }
                }
                List<BlockComponent> components = permutation.has("components")
                    ? blockComponents(permutation.getAsJsonObject("components")) : List.of();
                builder.permutation(new CustomBlock.Permutation(permutation.get("condition").getAsString(), components));
            }
        }
        return builder.build();
    }

    private static List<BlockComponent> blockComponents(JsonObject components) {
        List<BlockComponent> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : components.entrySet()) {
            Class<?> type = ComponentIndex.BLOCK.get(e.getKey());
            if (type == null) {
                throw new Unbindable("no generated block component " + e.getKey());
            }
            out.add((BlockComponent) bind(type, e.getValue()));
        }
        return out;
    }

    private static BlockProperty<?> property(String name, JsonElement values) {
        if (values.isJsonObject()) {
            JsonObject range = values.getAsJsonObject().getAsJsonObject("values");
            return BlockProperty.range(name, range.get("min").getAsInt(), range.get("max").getAsInt());
        }
        JsonArray array = values.getAsJsonArray();
        if (array.isEmpty()) {
            throw new Unbindable("empty state " + name);
        }
        JsonPrimitive first = array.get(0).getAsJsonPrimitive();
        if (first.isBoolean()) {
            boolean[] bools = new boolean[array.size()];
            for (int i = 0; i < bools.length; i++) {
                bools[i] = array.get(i).getAsBoolean();
            }
            return BlockProperty.bools(name, bools);
        }
        if (first.isNumber()) {
            return BlockProperty.ints(name, array.asList().stream().map(JsonElement::getAsInt).toArray(Integer[]::new));
        }
        return BlockProperty.strings(name, array.asList().stream().map(JsonElement::getAsString).toArray(String[]::new));
    }

    // ---- Records -------------------------------------------------------------------------------------------------------

    static Object bind(Class<?> type, JsonElement value) {
        try {
            if (value.isJsonObject() && ComponentIndex.JSON_KEYS.containsKey(type)) {
                Object builder = type.getMethod("builder").invoke(null);
                Map<String, String> keys = ComponentIndex.JSON_KEYS.get(type);
                for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                    String java = keys.get(e.getKey());
                    if (java == null) {
                        throw new Unbindable(type.getSimpleName() + " has no field for the JSON key " + e.getKey());
                    }
                    set(builder, java, e.getValue());
                }
                return builder.getClass().getMethod("build").invoke(builder);
            }
            // A value form: a form field, or a shorthand factory
            Map<String, String> forms = ComponentIndex.FORMS.getOrDefault(type, Map.of());
            String form = jsonType(value);
            if (forms.containsKey(form)) {
                Object builder = type.getMethod("builder").invoke(null);
                set(builder, forms.get(form), value);
                return builder.getClass().getMethod("build").invoke(builder);
            }
            for (Method m : type.getMethods()) {
                if (m.getName().equals("of") && m.getParameterCount() == 1 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    Object arg = tryConvert(m.getGenericParameterTypes()[0], value);
                    if (arg != NO) {
                        return m.invoke(null, arg);
                    }
                }
            }
            if (type.isRecord() && value.isJsonArray() && type.getSimpleName().equals("Vec3")) {
                JsonArray a = value.getAsJsonArray();
                return type.getDeclaredConstructors()[0].newInstance(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
            }
            throw new Unbindable(type.getSimpleName() + " has no form for " + form + " " + value);
        } catch (InvocationTargetException e) {
            throw new Unbindable(type.getSimpleName() + ": " + e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonType(JsonElement value) {
        if (value.isJsonArray()) {
            return "array<object>";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return "boolean";
        }
        if (p.isString()) {
            return "string";
        }
        return p.getAsDouble() == Math.rint(p.getAsDouble()) ? "integer" : "number";
    }

    /** Sets a field through the builder: the setter of that name (or of one of its union alternatives) that takes the value. */
    private static void set(Object builder, String java, JsonElement value) throws ReflectiveOperationException {
        List<Method> candidates = new ArrayList<>();
        for (Method m : builder.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && (m.getName().equals(java) || m.getName().startsWith(java) && m.getName().length() > java.length()
                && Character.isUpperCase(m.getName().charAt(java.length())))) {
                candidates.add(m);
            }
        }
        candidates.sort(Comparator.comparing((Method m) -> !m.getName().equals(java)).thenComparing(m -> m.getParameterTypes()[0].isArray()));
        // A vector object with some axes: a consumer takes the default vector and changes those axes
        for (Method m : candidates) {
            Class<?> p = m.getParameterTypes()[0];
            if (p.isRecord() && p.getSimpleName().equals("Vec3") && value.isJsonObject()) {
                java.lang.reflect.Field current = builder.getClass().getDeclaredField(java);
                current.setAccessible(true);
                Object base = current.get(builder);
                float[] xyz = new float[3];
                if (base != null) {
                    for (int i = 0; i < 3; i++) {
                        xyz[i] = (float) p.getRecordComponents()[i].getAccessor().invoke(base);
                    }
                }
                JsonObject o = value.getAsJsonObject();
                String[] axes = {"x", "y", "z"};
                for (int i = 0; i < 3; i++) {
                    if (o.has(axes[i])) {
                        xyz[i] = o.get(axes[i]).getAsFloat();
                    }
                }
                m.invoke(builder, p.getDeclaredConstructor(float.class, float.class, float.class).newInstance(xyz[0], xyz[1], xyz[2]));
                return;
            }
        }
        for (Method m : candidates) {
            Object arg = tryConvert(m.getGenericParameterTypes()[0], value);
            if (arg != NO) {
                try {
                    m.invoke(builder, arg);
                } catch (InvocationTargetException e) {
                    throw new Unbindable(java + ": " + e.getCause());
                }
                return;
            }
        }
        throw new Unbindable("no setter " + java + " takes " + value);
    }

    private static final Object NO = new Object();

    private static Object tryConvert(Type type, JsonElement value) {
        try {
            return convert(type, value);
        } catch (Unbindable | IllegalStateException | IllegalArgumentException | UnsupportedOperationException | ClassCastException e) {
            return NO;
        }
    }

    private static Object convert(Type type, JsonElement value) {
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (raw == boolean.class || raw == Boolean.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new Unbindable("not a boolean");
            }
            return value.getAsBoolean();
        }
        if (raw == int.class || raw == Integer.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                throw new Unbindable("not an integer");
            }
            return value.getAsInt();
        }
        if (raw == float.class || raw == Float.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new Unbindable("not a number");
            }
            return value.getAsFloat();
        }
        if (raw == String.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new Unbindable("not a string");
            }
            return value.getAsString();
        }
        if (raw.isEnum()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new Unbindable("not an enum value");
            }
            try {
                return raw.getMethod("fromValue", String.class).invoke(null, value.getAsString());
            } catch (InvocationTargetException e) {
                throw new Unbindable(String.valueOf(e.getCause()));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (raw == List.class) {
            if (!value.isJsonArray()) {
                throw new Unbindable("not a list");
            }
            Type element = ((ParameterizedType) type).getActualTypeArguments()[0];
            List<Object> out = new ArrayList<>();
            for (JsonElement e : value.getAsJsonArray()) {
                out.add(convert(element, e));
            }
            return out;
        }
        if (raw == Map.class) {
            if (!value.isJsonObject()) {
                throw new Unbindable("not a map");
            }
            Type v = ((ParameterizedType) type).getActualTypeArguments()[1];
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                out.put(e.getKey(), v == Object.class ? stateValue(e.getValue()) : convert(v, e.getValue()));
            }
            return out;
        }
        if (raw.isRecord()) {
            if (raw.getSimpleName().equals("Vec3")) {
                if (value.isJsonArray() && value.getAsJsonArray().size() == 3) {
                    JsonArray a = value.getAsJsonArray();
                    try {
                        RecordComponent[] c = raw.getRecordComponents();
                        return raw.getDeclaredConstructor(c[0].getType(), c[1].getType(), c[2].getType())
                            .newInstance(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
                if (!value.isJsonObject()) {
                    throw new Unbindable("not a vector");
                }
                JsonObject o = value.getAsJsonObject();
                if (!o.has("x") || !o.has("y") || !o.has("z")) {
                    throw new Unbindable("a vector object without all of x, y, z");
                }
                try {
                    return raw.getDeclaredConstructor(float.class, float.class, float.class).newInstance(
                        o.has("x") ? o.get("x").getAsFloat() : 0f, o.has("y") ? o.get("y").getAsFloat() : 0f, o.has("z") ? o.get("z").getAsFloat() : 0f);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return bind(raw, value);
        }
        throw new Unbindable("unsupported parameter " + type);
    }

    private static Object stateValue(JsonElement value) {
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return p.getAsInt();
        }
        return p.getAsString();
    }
}
