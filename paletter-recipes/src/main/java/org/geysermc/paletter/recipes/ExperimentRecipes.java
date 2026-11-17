package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The recipes an experiment toggle adds, removes or changes against the vanilla ones, as the recipe diff of BDS captures
 * with and without the toggle names them: {@code <kind> <id>} ({@code shaped minecraft:barrel}), mixes by their items.
 * The recipes in {@code added} are not part of {@link VanillaRecipes}, which is captured without experiments.
 *
 * @param added recipes only a world with the toggle has
 * @param removed vanilla recipes the toggle removes
 * @param changed vanilla recipes the toggle changes, each followed by the changed fields: {@code shaped minecraft:barrel: results}
 * @since 1.0.0
 */
public record ExperimentRecipes(@NonNull List<String> added, @NonNull List<String> removed, @NonNull List<String> changed) {
    public ExperimentRecipes {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }
}
