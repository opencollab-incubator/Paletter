package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.vanilla.BedrockItemType;

/**
 * A brewing stand recipe that changes the bottle of a potion (the potion keeps its effect).
 *
 * @param input the bottle type
 * @param reagent the item in the ingredient slot
 * @param output the bottle type that comes out
 * @since 1.0.0
 */
public record ContainerMix(@NonNull BedrockItemType input, @NonNull BedrockItemType reagent, @NonNull BedrockItemType output) {
}
