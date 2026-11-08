package org.geysermc.paletter.codegen.component;

import com.google.gson.JsonElement;
import org.geysermc.paletter.internal.component.ComponentIndex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The committed generated API against the model it was generated from: every required field is enforced by
 * {@code build()}, every range rejects the value just outside it, and every builder default is the documented default
 * (or the field is unset because BDS sends something else, which its javadoc says).
 */
class GeneratedApiTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static ApiModel model() throws Exception {
        return ComponentGenerator.model(ComponentGenerator.read(ROOT.resolve("codegen/model"), Main.knownTransforms(ROOT), "1.0.0"));
    }

    private static Class<?> recordClass(ApiModel.Comp c, ApiModel.ObjType o) throws ClassNotFoundException {
        String name = c.kind.pkg() + "." + c.className + (o == c.root ? "" : "$" + o.name);
        return Class.forName(name);
    }

    private static List<ApiModel.ObjType> types(ApiModel.Comp c) {
        List<ApiModel.ObjType> types = new ArrayList<>();
        types.add(c.root);
        types.addAll(c.types.values());
        return types;
    }

    private static Object builder(Class<?> type) throws ReflectiveOperationException {
        return type.getMethod("builder").invoke(null);
    }

    @Test
    void requiredFieldsRangesAndDefaults() throws Exception {
        ApiModel model = model();
        int required = 0;
        int ranges = 0;
        int defaults = 0;
        for (ApiModel.Comp c : model.components) {
            assertTrue(ComponentIndex.ITEM.containsValue(recordClass(c, c.root)) || ComponentIndex.BLOCK.containsValue(recordClass(c, c.root))
                || ComponentIndex.TRAIT.containsValue(recordClass(c, c.root)) || ComponentIndex.STANDALONE.containsValue(recordClass(c, c.root)), c.id);
            for (ApiModel.ObjType o : types(c)) {
                Class<?> type = recordClass(c, o);
                List<ApiModel.Field> fields = ApiSource.allFields(o);
                boolean anyRequired = fields.stream().anyMatch(f -> f.required && f.alternatives.isEmpty());
                // An empty builder fails exactly when something is required
                Object empty = builder(type);
                try {
                    empty.getClass().getMethod("build").invoke(empty);
                    assertTrue(!anyRequired, c.id + " " + o.name + " builds without its required fields");
                    // Defaults: what the builder applied equals the documented default
                    Object built = empty.getClass().getMethod("build").invoke(builder(type));
                    for (ApiModel.Field f : fields) {
                        Object value = type.getMethod(f.java).invoke(built);
                        if (f.defaultValue != null) {
                            defaults++;
                            assertEquals(normalize(f.defaultValue), normalize(value), c.id + " " + f.path);
                        } else if (!f.required && f.docsDefault != null && f.defaultNote == null && f.alternativeOf == null) {
                            fail(c.id + " " + f.path + " drops its documented default without saying why");
                        }
                    }
                } catch (InvocationTargetException e) {
                    assertTrue(anyRequired, c.id + " " + o.name + ": " + e.getCause());
                    assertInstanceOf(IllegalStateException.class, e.getCause());
                    assertTrue(e.getCause().getMessage().endsWith(" is required"), e.getCause().getMessage());
                    required++;
                }
                // Ranges: the value just outside is rejected
                if (anyRequired) {
                    continue;
                }
                for (ApiModel.Field f : fields) {
                    if ((f.min == null && f.max == null) || !(f.type instanceof ApiModel.Scalar s)) {
                        continue;
                    }
                    double outside = f.min != null ? f.min - 1 : f.max + 1;
                    Object b = builder(type);
                    Method setter = setter(b, f);
                    setter.invoke(b, s.kind() == ApiModel.ScalarKind.INT ? (Object) (int) outside : (Object) (float) outside);
                    try {
                        b.getClass().getMethod("build").invoke(b);
                        fail(c.id + " " + f.path + " accepted " + outside);
                    } catch (InvocationTargetException e) {
                        assertInstanceOf(IllegalArgumentException.class, e.getCause(), c.id + " " + f.path);
                        ranges++;
                    }
                }
            }
        }
        System.out.println("required checks " + required + ", range checks " + ranges + ", defaults " + defaults);
        assertTrue(required > 0 && ranges > 0 && defaults > 0);
    }

    private static Method setter(Object builder, ApiModel.Field f) throws NoSuchMethodException {
        for (Method m : builder.getClass().getMethods()) {
            if (m.getName().equals(f.java) && m.getParameterCount() == 1 && Set.of(int.class, Integer.class, float.class, Float.class)
                .contains(m.getParameterTypes()[0])) {
                return m;
            }
        }
        throw new NoSuchMethodException(f.java);
    }

    /** Values and JSON defaults in one comparable form: numbers as doubles, enums as their JSON value, records by components. */
    private static Object normalize(Object v) throws ReflectiveOperationException {
        if (v instanceof JsonElement j) {
            if (j.isJsonPrimitive()) {
                return j.getAsJsonPrimitive().isNumber() ? (Object) j.getAsDouble() : j.getAsJsonPrimitive().isBoolean() ? j.getAsBoolean() : j.getAsString();
            }
            if (j.isJsonArray()) {
                List<Object> out = new ArrayList<>();
                for (JsonElement e : j.getAsJsonArray()) {
                    out.add(normalize(e));
                }
                return out;
            }
            java.util.Map<String, Object> out = new java.util.TreeMap<>();
            for (var e : j.getAsJsonObject().entrySet()) {
                out.put(e.getKey(), normalize(e.getValue()));
            }
            return out;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Enum<?> e) {
            return e.getClass().getMethod("value").invoke(e);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object e : l) {
                out.add(normalize(e));
            }
            return out;
        }
        if (v != null && v.getClass().isRecord()) {
            var components = v.getClass().getRecordComponents();
            if (v.getClass().getSimpleName().equals("Vec3")) {
                List<Object> out = new ArrayList<>();
                for (var rc : components) {
                    out.add(normalize(rc.getAccessor().invoke(v)));
                }
                return out;
            }
            java.util.Map<String, Object> out = new java.util.TreeMap<>();
            java.util.Map<String, String> keys = ComponentIndex.JSON_KEYS.get(v.getClass());
            for (var e : keys.entrySet()) {
                Object value = v.getClass().getMethod(e.getValue()).invoke(v);
                if (value != null) {
                    out.put(e.getKey(), normalize(value));
                }
            }
            return out;
        }
        return v;
    }
}
