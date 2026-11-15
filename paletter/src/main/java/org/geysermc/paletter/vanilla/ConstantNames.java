package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The names of the generated constant fields, found by reflection over the constants classes on first use. Loads no data,
 * as the constants classes only create the types.
 */
final class ConstantNames {
    private static final String[] CLASSES = {"BedrockBlocks", "BedrockItems"};

    private ConstantNames() {
    }

    /**
     * @return the name of the field holding a vanilla type, preferring one that is not deprecated, or null if there is none
     */
    static @Nullable String of(Object type) {
        return Holder.NAMES.get(type);
    }

    private static final class Holder {
        static final Map<Object, String> NAMES = scan();

        private static Map<Object, String> scan() {
            Map<Object, String> names = new IdentityHashMap<>();
            Map<Object, Boolean> deprecated = new IdentityHashMap<>();
            for (String name : CLASSES) {
                Class<?> constants;
                try {
                    constants = Class.forName(ConstantNames.class.getPackageName() + "." + name);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
                for (Field field : constants.getFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        Object type = field.get(null);
                        boolean isDeprecated = field.isAnnotationPresent(Deprecated.class);
                        if (!names.containsKey(type) || deprecated.get(type) && !isDeprecated) {
                            names.put(type, field.getName());
                            deprecated.put(type, isDeprecated);
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return names;
        }
    }
}
