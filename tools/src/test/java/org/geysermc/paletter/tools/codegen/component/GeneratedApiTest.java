package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.EmbeddedVisual;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.UvLock;
import org.geysermc.paletter.block.component.ItemVisual;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.internal.component.ComponentIndex;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.KineticWeapon;
import org.geysermc.paletter.item.component.Rarity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return ComponentGenerator.model(ComponentGenerator.read(ROOT.resolve("data/model"), "1.0.0"));
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
                boolean anyRequired = fields.stream().anyMatch(f -> f.required);
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
                        } else if (!f.required && f.docsDefault != null && f.defaultNote == null) {
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

    /** A record refuses a value outside its range in its constructor, not only when a builder makes it ({@code new Friction(5f)}). */
    @Test
    void theRecordConstructorChecksRanges() throws Exception {
        int checked = 0;
        for (ApiModel.Comp c : model().components) {
            for (ApiModel.ObjType o : types(c)) {
                Class<?> type = recordClass(c, o);
                var components = type.getRecordComponents();
                if (!java.util.Arrays.stream(components).allMatch(rc -> rc.getType().isPrimitive())) {
                    continue; // only numbers and booleans: every other argument can be given a valid value here
                }
                var constructor = type.getDeclaredConstructor(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType)
                    .toArray(Class<?>[]::new));
                List<ApiModel.Field> fields = ApiSource.allFields(o);
                for (ApiModel.Field ranged : fields) {
                    if (ranged.min == null && ranged.max == null) {
                        continue;
                    }
                    Object[] arguments = new Object[components.length];
                    for (int i = 0; i < components.length; i++) {
                        String name = components[i].getName();
                        ApiModel.Field f = fields.stream().filter(x -> x.java.equals(name)).findFirst().orElseThrow();
                        double value = f == ranged ? (f.min != null ? f.min - 1 : f.max + 1) : f.min != null ? f.min : f.max != null ? f.max : 0;
                        Class<?> kind = components[i].getType();
                        arguments[i] = kind == boolean.class ? (Object) false : kind == int.class ? (Object) (int) value : (Object) (float) value;
                    }
                    try {
                        constructor.newInstance(arguments);
                        fail(c.id + " " + ranged.java + ": the constructor accepted a value outside the range");
                    } catch (InvocationTargetException e) {
                        assertInstanceOf(IllegalArgumentException.class, e.getCause(), c.id + " " + ranged.java);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0);
        assertThrows(IllegalArgumentException.class, () -> new Friction(5f), "BDS takes 0 to 0.9");
        assertThrows(IllegalArgumentException.class, () -> new LightEmission(16), "BDS takes at most 15");
        // and an order between two fields BDS demands ("max value cannot be lower than min value")
        assertThrows(IllegalArgumentException.class, () -> Durability.DamageChance.builder().min(50).max(10).build());
        assertEquals(10, Durability.DamageChance.builder().min(10).max(10).build().max(), "equal is in order");
        // and a value of another field BDS refuses a map with ("Cannot use bone visibility feature with: minecraft:geometry.full_block")
        Map<String, String> bone = Map.of("arm", "q.block_state('test:lit')");
        assertThrows(IllegalArgumentException.class, () -> ItemVisual.VisualGeometry.builder().identifier("minecraft:geometry.full_block").boneVisibility(bone).build());
        assertThrows(IllegalArgumentException.class, () -> EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.full_block").boneVisibility(bone).build());
        assertThrows(IllegalArgumentException.class, () -> Geometry.builder().identifier("minecraft:geometry.full_block").boneVisibility(bone).build());
        assertEquals(bone, ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").boneVisibility(bone).build().boneVisibility());
        assertEquals(Map.of(), ItemVisual.VisualGeometry.of("minecraft:geometry.full_block").boneVisibility(), "BDS takes the empty map");
    }

    /** The JSON BDS accepts that the docs do not model, as the API expresses it (the conformance suite checks each against BDS's NBT). */
    @Test
    void theFormsBdsAcceptsAreInTheApi() {
        // One collision box as an object is the list of one
        CollisionBox.Entry entry = CollisionBox.Entry.builder().size(1, 2, 3).build();
        assertEquals(CollisionBox.builder().boxes(entry).build(), CollisionBox.of(entry));
        assertEquals("boxes[]", ComponentIndex.FORMS.get(CollisionBox.class).get("object"));
        // An open enum takes any string
        assertEquals("paletter_value", Rarity.of("paletter_value").value());
        // The kinetic conditions are optional: BDS reads a missing speed as 0
        KineticWeapon.Conditions conditions = KineticWeapon.Conditions.builder().build();
        assertEquals(0f, conditions.minSpeed());
        assertEquals(0f, conditions.minRelativeSpeed());
        // A descriptor entry may name no item
        assertNull(DestructibleByMining.ItemSpecificSpeeds.builder().destroySpeed(1f).build().item());
        assertEquals(Map.of("item", "item"), ComponentIndex.DESCRIPTORS.get(DestructibleByMining.ItemSpecificSpeeds.class));
        // uv_lock is a boolean or a list of bones, never both: one value of a sealed type, the last form set wins
        assertEquals(UvLock.bones(List.of("a", "b")), Geometry.builder().identifier("g").uvLock("a", "b").build().uvLock());
        assertEquals(UvLock.flag(true), Geometry.builder().identifier("g").uvLock(true).build().uvLock());
        assertEquals(UvLock.flag(false), EmbeddedVisual.VisualGeometry.builder().identifier("g").build().uvLock(), "the documented default");
        assertEquals(UvLock.bones(List.of("a")), Geometry.builder().identifier("g").uvLock(true).uvLock("a").build().uvLock());
        assertThrows(NullPointerException.class, () -> UvLock.bones(null));
    }

    @Test
    void everyUnionIsASealedTypeOfOneValueRecordsSharedByItsFields() throws Exception {
        ApiModel model = model();
        assertTrue(!model.unions.isEmpty());
        for (ApiModel.UnionType u : model.unions.values()) {
            Class<?> type = Class.forName((u.owner == null ? u.kind.pkg() + "." : u.kind.pkg() + "." + u.owner.className + "$") + u.name);
            assertTrue(type.isInterface() && type.isSealed(), u.name);
            assertEquals(u.variants.size(), type.getPermittedSubclasses().length, u.name);
            for (Class<?> variant : type.getPermittedSubclasses()) {
                assertTrue(variant.isRecord() && variant.getRecordComponents().length == 1
                    && variant.getRecordComponents()[0].getName().equals("value"), u.name + "." + variant.getSimpleName());
                assertTrue(u.variants.stream().anyMatch(v -> v.name.equals(variant.getSimpleName())), variant.getName());
            }
            // A factory named after each form
            for (ApiModel.Variant v : u.variants) {
                assertTrue(java.util.Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(v.factory())), u.name + "." + v.factory());
            }
        }
        // One type for the three geometries that take uv_lock
        assertEquals(UvLock.class, Geometry.class.getRecordComponents()[Geometry.class.getRecordComponents().length - 1].getType());
        assertEquals(UvLock.class, ItemVisual.VisualGeometry.class.getRecordComponents()[ItemVisual.VisualGeometry.class.getRecordComponents().length - 1].getType());
        assertEquals(UvLock.class, EmbeddedVisual.VisualGeometry.class.getRecordComponents()[EmbeddedVisual.VisualGeometry.class.getRecordComponents().length - 1].getType());
        assertEquals(3, model.unions.get("UvLock").users.size());
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
        if (v != null && v.getClass().isRecord() && !ComponentIndex.JSON_KEYS.containsKey(v.getClass())
            && v.getClass().getRecordComponents().length == 1 && v.getClass().getRecordComponents()[0].getName().equals("value")) {
            // A form of a union: its value is what the JSON has
            return normalize(v.getClass().getRecordComponents()[0].getAccessor().invoke(v));
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
