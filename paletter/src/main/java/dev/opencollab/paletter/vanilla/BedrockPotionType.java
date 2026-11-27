package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Bedrock potion type, e.g. {@code minecraft:long_nightvision}: the effect of a potion or tipped arrow variant, with its
 * duration and strength. The constants are {@link BedrockPotionTypes}; {@link PotionItemType#withEffect} gives the
 * variant of an item.
 *
 * @since 1.0.0
 */
public final class BedrockPotionType {
    private final String identifier;
    // Filled by the initializer of BedrockPotionTypes, read only after it
    private final Map<BedrockItemType, BedrockItemVariant> variants = new HashMap<>();

    BedrockPotionType(@NonNull String identifier) {
        this.identifier = Objects.requireNonNull(identifier);
    }

    /** Names the variant of an item with this potion type: the generated initializer of {@link BedrockPotionTypes}. */
    @NonNull BedrockPotionType add(@NonNull BedrockItemType type, int aux) {
        variants.put(type, VanillaTypes.variant(type, aux));
        return this;
    }

    @NonNull BedrockItemVariant variantOf(@NonNull PotionItemType type) {
        BedrockItemVariant variant = variants.get(type);
        if (variant == null) {
            throw new IllegalArgumentException(type.identifier() + " has no variant with the potion type " + identifier);
        }
        return variant;
    }

    /**
     * The identifier.
     *
     * @return the identifier, e.g. {@code minecraft:long_nightvision}
     * @since 1.0.0
     */
    public @NonNull String identifier() {
        return identifier;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockPotionType other && identifier.equals(other.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return identifier;
    }
}
