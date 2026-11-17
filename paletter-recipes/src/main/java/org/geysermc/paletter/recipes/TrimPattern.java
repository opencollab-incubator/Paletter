package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.vanilla.BedrockItemType;

/**
 * An armor trim pattern, as BDS's trim data names it.
 *
 * @param id the pattern id, e.g. {@code ward}
 * @param item the smithing template item of the pattern
 * @since 1.0.0
 */
public record TrimPattern(@NonNull String id, @NonNull BedrockItemType item) {
}
