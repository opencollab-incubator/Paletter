package org.geysermc.paletter;

import org.geysermc.paletter.annotation.BedrockExperiment;
import org.geysermc.paletter.vanilla.BedrockExperiments;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple names are unique across the public API, generated and hand-written types alike. A name that two types would share
 * carries a {@code Block} or {@code Item} prefix (the generator computes which); a nested type must not have the name of a top
 * level one (it is named in api.json). Nested types among themselves ({@code Builder}, ...) are scoped by their owner.
 */
class ApiNamesTest {
    @Test
    void simpleNamesOfPublicTypesAreUniqueAndNoNestedTypeShadowsOne() throws Exception {
        Map<String, List<String>> topLevel = new TreeMap<>();
        List<Class<?>> nested = new ArrayList<>();
        for (Class<?> type : ApiBoundaryTest.apiTypes()) {
            if (type.getDeclaringClass() == null) {
                topLevel.computeIfAbsent(type.getSimpleName(), n -> new ArrayList<>()).add(type.getName());
            } else {
                nested.add(type);
            }
        }
        assertTrue(topLevel.size() > 80, "found only " + topLevel.size() + " API types");
        List<String> problems = new ArrayList<>();
        topLevel.forEach((name, types) -> {
            if (types.size() > 1) {
                problems.add("shared by " + types);
            }
        });
        nested.stream().filter(type -> topLevel.containsKey(type.getSimpleName())).forEach(type -> problems.add("nested type " + type.getName()
            + " shadows " + topLevel.get(type.getSimpleName())));
        assertTrue(problems.isEmpty(), "Public simple names must be unique (generated types get a Block/Item prefix, name nested ones in api.json,"
            + " hand-written ones need another name):\n  " + String.join("\n  ", problems));
    }

    @Test
    void everyBedrockExperimentNamesAConstantOfBedrockExperiments() throws Exception {
        Set<String> toggles = new HashSet<>();
        for (Field field : BedrockExperiments.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                String toggle = (String) field.get(null);
                assertEquals(field.getName(), toggle.toUpperCase(Locale.ROOT), "the constant is named after the toggle");
                toggles.add(toggle);
            }
        }
        int annotated = 0;
        for (Class<?> type : ApiBoundaryTest.apiTypes()) {
            BedrockExperiment experiment = type.getAnnotation(BedrockExperiment.class);
            if (experiment != null) {
                annotated++;
                assertTrue(toggles.contains(experiment.value()), type.getName() + " names the toggle " + experiment.value() + ", which BedrockExperiments does not have");
            }
        }
        assertTrue(annotated > 0, "no component is marked @BedrockExperiment");
    }
}
