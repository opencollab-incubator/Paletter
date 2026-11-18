/**
 * Paletter Recipes: the vanilla crafting recipes of each data version, on top of {@code org.geysermc.paletter}. The
 * exported packages are the API; like Paletter's, this descriptor is compiled to check the exports but not packaged
 * (the jar declares {@code Automatic-Module-Name: org.geysermc.paletter.recipes}). {@code org.geysermc.paletter} never
 * depends on this module.
 * <p>
 * Exports the one package of the records and the loader.
 */
module org.geysermc.paletter.recipes {
    requires transitive org.geysermc.paletter;
    requires static org.checkerframework.checker.qual;

    exports org.geysermc.paletter.recipes;
}
