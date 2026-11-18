/**
 * Paletter Recipes: the vanilla crafting recipes of each data version, on top of {@code dev.opencollab.paletter}. The
 * exported packages are the API; like Paletter's, this descriptor is compiled to check the exports but not packaged
 * (the jar declares {@code Automatic-Module-Name: dev.opencollab.paletter.recipes}). {@code dev.opencollab.paletter} never
 * depends on this module.
 * <p>
 * Exports the one package of the records and the loader.
 */
module dev.opencollab.paletter.recipes {
    requires transitive dev.opencollab.paletter;
    requires static org.checkerframework.checker.qual;

    exports dev.opencollab.paletter.recipes;
}
