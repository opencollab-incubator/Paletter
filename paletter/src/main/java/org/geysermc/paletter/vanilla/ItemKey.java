package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What names an item for the methods that take a variant: a {@link BedrockItemVariant}, or a {@link BedrockItemType},
 * which stands for its base variant (aux 0). So a type can be passed where a variant is wanted:
 * <pre>{@code
 * ResolvedItem apple = palette.resolve(BedrockItems.APPLE);
 * ResolvedItem potion = palette.resolve(BedrockItems.POTION.variant(5));
 * }</pre>
 * The methods that take an {@code ItemKey} read a type as its base variant, aux 0. Where a type means every aux instead,
 * the method says so in its name ({@code ItemFallback.keepingAux}, {@code ItemMatcher.anyVariantOf},
 * {@code CreativePlacement.afterAnyOf}), so dropping a {@code .variant()} never changes what a call means.
 * <p>
 * A key is a plain value, a type and an aux: creating or referencing one loads no data, so the constants of
 * {@link BedrockItems} and {@link BedrockItemVariants} can be used in static initializers.
 *
 * @since 0.1.0
 */
public sealed interface ItemKey permits BedrockItemType, BedrockItemVariant {
    /**
     * @return the variant this key names: itself for a variant, the base variant (aux 0) for a type. Loads no data
     */
    @NonNull BedrockItemVariant variant();

    /**
     * @return the type of this key: itself for a type
     */
    @NonNull BedrockItemType type();
}
