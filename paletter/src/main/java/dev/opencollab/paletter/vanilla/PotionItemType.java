package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An item whose variants are potion types: the potions and the tipped arrow ({@link BedrockItems#POTION},
 * {@link BedrockItems#SPLASH_POTION}, {@link BedrockItems#LINGERING_POTION}, {@link BedrockItems#ARROW}).
 * <pre>{@code
 * BedrockItemVariant healing = BedrockItems.SPLASH_POTION.withEffect(BedrockPotionTypes.HEALING);
 * }</pre>
 * As a type it is its base variant, aux 0, like any type: the water bottle, or the plain arrow.
 *
 * @since 1.0.0
 */
public final class PotionItemType extends BedrockItemType {
    PotionItemType(@NonNull String constantName, @NonNull String identifier) {
        super(constantName, identifier, VanillaTypes.UNKNOWN, false);
    }

    /**
     * The variant of this item with a potion type.
     *
     * @param potion the potion type
     * @return the variant of this item with that potion type, as BDS numbers it
     * @throws IllegalArgumentException if the item has no variant with that potion type (the tipped arrow has no water
     *                                  arrow, for one)
     * @since 1.0.0
     */
    public @NonNull BedrockItemVariant withEffect(@NonNull BedrockPotionType potion) {
        return potion.variantOf(this);
    }
}
