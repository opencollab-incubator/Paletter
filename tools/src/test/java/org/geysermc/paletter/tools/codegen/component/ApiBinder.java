package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.BlockTrait;
import org.geysermc.paletter.block.component.ScriptHooks;
import org.geysermc.paletter.internal.component.ComponentIndex;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.item.component.UseOnHook;
import org.geysermc.paletter.tools.model.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds add-on JSON through the generated API, the way a consumer would: every JSON object through its record's builder
 * (setters found by the JSON key of {@link ComponentIndex#JSON_KEYS}), value forms through the {@code of} factories or the
 * form fields. Nothing here knows a component; what the API cannot express fails with {@link Unbindable}.
 * <p>
 * Where add-on JSON has several spellings of one value, the binder maps them to the one call the API offers; each mapping
 * is a claim the conformance test checks against what BDS sent for that spelling:
 * <ul>
 * <li>an object value form that is one entry of a list ({@code ComponentIndex.FORMS} {@code "object" -> "list[]"}) is the
 * {@code of(entry)} factory;</li>
 * <li>a descriptor field ({@code ComponentIndex.DESCRIPTORS}) given as an object is its {@code name}, and as an object
 * without one or as an empty list it is not set;</li>
 * <li>an empty list for an object is that object with nothing set;</li>
 * <li>the JSON constants {@code true} and {@code false} where a Molang expression (a string) is taken are the strings
 * {@code "true"} and {@code "false"}.</li>
 * <li>script custom components (a key outside the {@code minecraft:} namespace, registered in the {@code scriptComponents} of
 * {@code fixtures.json}) are not bound one by one: the callbacks of all of them on a block are the {@link ScriptHooks} flags, and
 * {@code onUseOn} on an item is {@link UseOnHook}. The old form (a list of names) is not in the API.</li>
 * </ul>
 */
public final class ApiBinder {
    static final class Unbindable extends RuntimeException {
        Unbindable(String message) {
            super(message);
        }
    }

    private static final JsonObject SCRIPT_COMPONENTS = scriptComponents();

    private ApiBinder() {
    }

    private static JsonObject scriptComponents() {
        try {
            return Fixtures.read(ComponentConformanceTest.MODEL).scriptComponents();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CustomItem item(JsonObject file) {
        JsonObject item = file.getAsJsonObject("minecraft:item");
        JsonObject description = item.getAsJsonObject("description");
        CustomItem.Builder builder = CustomItem.builder(description.get("identifier").getAsString());
        if (description.has("menu_category")) {
            builder.menuCategory((org.geysermc.paletter.item.component.MenuCategory) bind(org.geysermc.paletter.item.component.MenuCategory.class,
                description.get("menu_category")));
        }
        Set<String> callbacks = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : item.getAsJsonObject("components").entrySet()) {
            if (scriptComponent("item", e, callbacks)) {
                continue;
            }
            Class<?> type = ComponentIndex.ITEM.get(e.getKey());
            if (type == null) {
                throw new Unbindable("no generated item component " + e.getKey());
            }
            builder.component((ItemComponent) bind(type, e.getValue()));
        }
        if (callbacks.contains("onUseOn")) {
            builder.component(UseOnHook.of(true));
        }
        return builder.build();
    }

    public static CustomBlock block(JsonObject file) {
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
        Set<String> callbacks = new HashSet<>();
        boolean script = false;
        for (Map.Entry<String, JsonElement> e : components.entrySet()) {
            if (scriptComponent("block", e, callbacks)) {
                script = true;
                continue;
            }
            Class<?> type = ComponentIndex.BLOCK.get(e.getKey());
            if (type == null) {
                throw new Unbindable("no generated block component " + e.getKey());
            }
            out.add((BlockComponent) bind(type, e.getValue()));
        }
        if (script) {
            out.add(ScriptHooks.of(callbacks.contains("onPlayerInteract"), callbacks.contains("beforeOnPlayerPlace")));
        }
        return out;
    }

    /** Adds the callbacks of a script custom component to the set; false if the entry is no such component. */
    private static boolean scriptComponent(String kind, Map.Entry<String, JsonElement> entry, Set<String> callbacks) {
        if (entry.getKey().equals("minecraft:custom_components")) {
            throw new Unbindable("the old form of custom_components (a list of names) is not in the API: Paletter always writes the new form");
        }
        if (entry.getKey().startsWith("minecraft:")) {
            return false;
        }
        JsonObject registered = SCRIPT_COMPONENTS.getAsJsonObject(kind);
        if (!registered.has(entry.getKey())) {
            throw new Unbindable("script component " + entry.getKey() + " is not registered by the add-on script");
        }
        registered.getAsJsonArray(entry.getKey()).forEach(c -> callbacks.add(c.getAsString()));
        return true;
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
            Map<String, String> forms = ComponentIndex.FORMS.getOrDefault(type, Map.of());
            if (value.isJsonObject() && ComponentIndex.JSON_KEYS.containsKey(type) && !forms.containsKey("object")) {
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
            String form = jsonType(value);
            if (forms.containsKey(form) && !forms.get(form).endsWith("[]")) {
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

    /** Sets a field through the builder: the setter of that name that takes the value (a union field has one per form, and one for the union). */
    private static void set(Object builder, String java, JsonElement value) throws ReflectiveOperationException {
        List<Method> candidates = new ArrayList<>();
        for (Method m : builder.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && (m.getName().equals(java) || m.getName().startsWith(java) && m.getName().length() > java.length()
                && Character.isUpperCase(m.getName().charAt(java.length())))) {
                candidates.add(m);
            }
        }
        candidates.sort(Comparator.comparing((Method m) -> !m.getName().equals(java)).thenComparing(m -> m.getParameterTypes()[0].isArray()));
        // A descriptor: its name, or nothing
        Map<String, String> descriptors = ComponentIndex.DESCRIPTORS.getOrDefault(builder.getClass().getEnclosingClass(), Map.of());
        if (descriptors.containsKey(java) && (value.isJsonObject() || value.isJsonArray() && value.getAsJsonArray().isEmpty())) {
            JsonElement name = value.isJsonObject() ? value.getAsJsonObject().get("name") : null;
            value = name == null ? JsonNull.INSTANCE : name;
        }
        if (value.isJsonNull()) {
            // Nothing set: the union type if the field has one, else a string
            for (Method m : candidates) {
                if (m.getParameterTypes()[0].isSealed()) {
                    m.invoke(builder, (Object) null);
                    return;
                }
            }
            for (Method m : candidates) {
                if (m.getParameterTypes()[0] == String.class) {
                    m.invoke(builder, (Object) null);
                    return;
                }
            }
            throw new Unbindable("no setter " + java + " takes nothing");
        }
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
                JsonElement entry = e.getValue();
                if (v == String.class && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isBoolean()) {
                    entry = new JsonPrimitive(entry.getAsString()); // a Molang constant
                }
                out.put(e.getKey(), v == Object.class ? stateValue(entry) : convert(v, entry));
            }
            return out;
        }
        if (raw.isInterface() && raw.isSealed()) {
            // A union: the first form that takes the JSON value
            for (Class<?> form : raw.getPermittedSubclasses()) {
                Type component = form.getRecordComponents()[0].getGenericType();
                try {
                    Object converted = convert(component, value);
                    return form.getDeclaredConstructors()[0].newInstance(converted);
                } catch (Unbindable | IllegalStateException | IllegalArgumentException | UnsupportedOperationException | ClassCastException
                         | ReflectiveOperationException e) {
                    // the next form
                }
            }
            throw new Unbindable(raw.getSimpleName() + " has no form for " + value);
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
            return bind(raw, value.isJsonArray() && value.getAsJsonArray().isEmpty() ? new JsonObject() : value);
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
