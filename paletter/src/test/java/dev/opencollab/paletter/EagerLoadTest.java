package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.Lazy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Paletter.Builder#build()} leaves nothing of its palettes to load on first use: every {@link Lazy} reachable from
 * a compiled palette holds its value, and the other first-use caches ({@link Palette#loadedEagerly}) are built.
 */
class EagerLoadTest {
    /**
     * Lazies that are dropped on purpose once every served version is compiled (see {@code Paletter#releaseCompileData}):
     * only compiling uses them, and a version compiled later reads them again.
     */
    private static final Set<String> RELEASED = Set.of("FileDataSource.properties", "CanonicalBlocks.schemas");

    @Test
    void nothingIsLeftToLoadAfterTheBuild() throws IllegalAccessException {
        assertNothingUnloaded(Paletter.builder().layer(PaletteLayer.builder("server").build()).build(), true);
    }

    @Test
    void withoutBehaviourOnlyTheBehaviourColumnsAreLeft() throws IllegalAccessException {
        assertNothingUnloaded(Paletter.builder().behaviour(false).build(), false);
    }

    private static void assertNothingUnloaded(Paletter paletter, boolean behaviour) throws IllegalAccessException {
        List<String> unloaded = new ArrayList<>();
        int lazies = 0;
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            assertTrue(palette.loadedEagerly(behaviour), version.toString());
            for (Field field : lazyFields(palette)) {
                lazies++;
                unloaded.add(field.getDeclaringClass().getSimpleName() + "." + field.getName());
            }
        }
        unloaded.removeAll(RELEASED);
        assertEquals(List.of(), unloaded);
        assertTrue(lazies > 0);
    }

    /** The unloaded {@link Lazy} fields of the Paletter objects reachable from {@code root}, not following arrays and collections. */
    private static List<Field> lazyFields(Object root) throws IllegalAccessException {
        List<Field> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Object object = pending.pop();
            if (!seen.add(object)) {
                continue;
            }
            for (Class<?> type = object.getClass(); type != null && type.getName().startsWith("dev.opencollab.paletter."); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || field.getType().isArray()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Lazy<?> lazy) {
                        if (lazy.peek() == null) {
                            found.add(field);
                        }
                    } else if (value != null && value.getClass().getName().startsWith("dev.opencollab.paletter.")) {
                        pending.push(value);
                    }
                }
            }
        }
        return found;
    }
}
