package org.geysermc.paletter;

import org.geysermc.paletter.annotation.BedrockExperiment;
import org.geysermc.paletter.annotation.Since;
import org.geysermc.paletter.vanilla.BedrockExperiments;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple names are unique across the public API, generated and hand-written types alike. A name that two types would share
 * carries a {@code Block} or {@code Item} prefix (the generators compute which); nested types are scoped by their owner but
 * must not have the name of a top level type.
 */
class ApiNamesTest {
    @Test
    void everyTopLevelPublicTypeHasAUniqueSimpleName() throws Exception {
        Map<String, List<String>> bySimpleName = new TreeMap<>();
        for (Class<?> type : ApiBoundaryTest.apiTypes()) {
            if (type.getDeclaringClass() == null) {
                bySimpleName.computeIfAbsent(type.getSimpleName(), n -> new ArrayList<>()).add(type.getName());
            }
        }
        List<String> duplicates = new ArrayList<>();
        bySimpleName.forEach((name, types) -> {
            if (types.size() > 1) {
                duplicates.add(name + ": " + types);
            }
        });
        assertTrue(bySimpleName.size() > 80, "found only " + bySimpleName.size() + " API types");
        assertTrue(duplicates.isEmpty(), "Public types share a simple name; the generators prefix Block or Item on a collision, "
            + "hand-written types need another name:\n  " + String.join("\n  ", duplicates));
    }

    @Test
    void noNestedPublicTypeHasTheNameOfATopLevelOne() throws Exception {
        Set<String> top = new HashSet<>();
        List<Class<?>> nested = new ArrayList<>();
        for (Class<?> type : ApiBoundaryTest.apiTypes()) {
            if (type.getDeclaringClass() == null) {
                top.add(type.getSimpleName());
            } else {
                nested.add(type);
            }
        }
        List<String> shadows = nested.stream().filter(type -> top.contains(type.getSimpleName())).map(Class::getName).toList();
        assertTrue(shadows.isEmpty(), "Nested types shadow a top level type of the same name (name them in api.json):\n  " + String.join("\n  ", shadows));
    }

    @Test
    void everyBedrockExperimentNamesAConstantOfBedrockExperiments() throws Exception {
        Set<String> toggles = new HashSet<>();
        for (Field field : BedrockExperiments.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                toggles.add((String) field.get(null));
                assertTrue(field.isAnnotationPresent(Since.class), field.getName() + " has no @Since");
            }
        }
        int annotated = 0;
        for (Class<?> type : ApiBoundaryTest.apiTypes()) {
            for (AnnotatedElement element : List.<AnnotatedElement>of(type)) {
                BedrockExperiment experiment = element.getAnnotation(BedrockExperiment.class);
                if (experiment != null) {
                    annotated++;
                    assertTrue(toggles.contains(experiment.value()), type.getName() + " names the toggle " + experiment.value()
                        + ", which BedrockExperiments does not have");
                }
            }
        }
        assertTrue(annotated > 0, "no component is marked @BedrockExperiment");
    }

    @Test
    void theConstantNameIsTheToggleId() throws Exception {
        for (Field field : BedrockExperiments.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                assertEquals(field.getName(), ((String) field.get(null)).toUpperCase(java.util.Locale.ROOT));
            }
        }
    }
}
