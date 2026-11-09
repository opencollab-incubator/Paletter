package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.item.BedrockItemStack;

/**
 * A stack that is not in any group of a creative category.
 *
 * @param stack the stack
 * @since 1.0.0
 */
public record CreativeItem(@NonNull BedrockItemStack stack) implements CreativeNode {
}
