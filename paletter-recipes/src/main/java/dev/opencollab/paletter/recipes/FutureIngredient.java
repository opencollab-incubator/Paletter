package dev.opencollab.paletter.recipes;

/**
 * Never instantiated: it makes a switch over {@code Ingredient} need a {@code default}, as Bedrock adds kinds of
 * ingredients in minor releases (docs/api.md, "Evolution").
 */
final class FutureIngredient implements Ingredient {
    private FutureIngredient() {
    }

    @Override
    public int count() {
        throw new AssertionError();
    }
}
