package dev.opencollab.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.item.BedrockItemStack;

/**
 * A stack that is not in any group of a creative category.
 *
 * @param stack the stack
 * @since 1.0.0
 */
public record CreativeItem(@NonNull BedrockItemStack stack) implements CreativeNode {
}
