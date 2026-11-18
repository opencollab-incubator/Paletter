package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

/**
 * A brewing stand recipe that changes a potion.
 *
 * @param input the item in the bottle slot
 * @param reagent the item in the ingredient slot
 * @param output the item that comes out
 * @since 1.0.0
 */
public record PotionMix(@NonNull BedrockItemVariant input, @NonNull BedrockItemVariant reagent, @NonNull BedrockItemVariant output) {
}
