package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.vanilla.BedrockItemType;

/**
 * An armor trim material, as BDS's trim data names it.
 *
 * @param id the material id, e.g. {@code quartz}
 * @param color the color code of the material's name
 * @param item the item of the material
 * @since 1.0.0
 */
public record TrimMaterial(@NonNull String id, @NonNull String color, @NonNull BedrockItemType item) {
}
