package dev.opencollab.paletter.recipes;

/**
 * Never instantiated: it makes a switch over {@code Recipe} need a {@code default}, as Bedrock adds kinds of recipes in
 * minor releases (docs/api.md, "Evolution").
 */
final class FutureRecipe implements Recipe {
    private FutureRecipe() {
    }
}
