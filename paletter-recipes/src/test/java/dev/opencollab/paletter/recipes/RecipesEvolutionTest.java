package dev.opencollab.paletter.recipes;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What Bedrock defines stays open (docs/api.md, "Evolution"): {@link Recipe} and {@link Ingredient} each permit a sentinel,
 * and the only records are the shapes fixed by definition.
 */
class RecipesEvolutionTest {
    /** The records of the module: the ingredient forms and the brewing mixes, whose shapes have been stable for years. */
    private static final Set<Class<?>> ALLOWED_RECORDS = Set.of(Ingredient.Item.class, Ingredient.AnyVariant.class, Ingredient.Tag.class,
        Ingredient.Alias.class, Ingredient.Empty.class, PotionMix.class, ContainerMix.class);

    @Test
    void recipesAndIngredientsHaveTheirSentinel() {
        for (Class<?> sealed : List.of(Recipe.class, Ingredient.class)) {
            List<Class<?>> sentinels = Arrays.stream(sealed.getPermittedSubclasses())
                .filter(p -> !Modifier.isPublic(p.getModifiers()) && Modifier.isFinal(p.getModifiers())
                    && p.getSimpleName().equals("Future" + sealed.getSimpleName())
                    && Arrays.stream(p.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())))
                .toList();
            assertEquals(1, sentinels.size(), sealed.getName());
        }
    }

    @Test
    void theOnlyRecordsAreTheAllowedShapes() throws Exception {
        List<String> records = new ArrayList<>();
        for (Class<?> type : RecipesApiBoundaryTest.apiTypes()) {
            if (type.isRecord() && !ALLOWED_RECORDS.contains(type)) {
                records.add(type.getName());
            }
        }
        assertEquals(List.of(), records, "a recipe type is a final class with a builder, not a record");
    }
}
