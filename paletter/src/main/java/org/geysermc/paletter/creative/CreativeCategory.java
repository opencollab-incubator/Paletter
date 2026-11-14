package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemCategory;

import java.util.Locale;

/**
 * The four tabs of the creative inventory, in the order the client expects them.
 * <p>
 * Bedrock refers to a category in different ways, which is easy to mix up: the protocol enum
 * ({@link CreativeItemCategory}, whose ordinal is offset by {@code ALL}) and the name used in add-on JSON and a block's
 * {@code menu_category}. The numeric id of the {@code creative_category} item property is the generated encoder's,
 * inferred from the spec captures.
 *
 * @since 0.1.0
 */
public enum CreativeCategory {
    CONSTRUCTION("construction", CreativeItemCategory.CONSTRUCTION),
    NATURE("nature", CreativeItemCategory.NATURE),
    EQUIPMENT("equipment", CreativeItemCategory.EQUIPMENT),
    ITEMS("items", CreativeItemCategory.ITEMS);

    /**
     * Name to use in {@code menu_category} for blocks that are not in the creative inventory.
     */
    public static final String NONE_NAME = "none";

    private static final CreativeCategory[] VALUES = values();

    private final String bedrockName;
    private final CreativeItemCategory protocol;

    CreativeCategory(String bedrockName, CreativeItemCategory protocol) {
        this.bedrockName = bedrockName;
        this.protocol = protocol;
    }

    /**
     * @return the name used in data, e.g. {@code construction}, and in block {@code menu_category} NBT
     */
    public @NonNull String bedrockName() {
        return bedrockName;
    }

    /**
     * @return the protocol representation, used in creative groups
     */
    public @NonNull CreativeItemCategory protocol() {
        return protocol;
    }

    public static @NonNull CreativeCategory fromBedrockName(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (CreativeCategory category : VALUES) {
            if (category.bedrockName.equals(lower)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown creative category: " + name);
    }

    public static @NonNull CreativeCategory fromProtocol(@NonNull CreativeItemCategory category) {
        for (CreativeCategory value : VALUES) {
            if (value.protocol == category) {
                return value;
            }
        }
        throw new IllegalArgumentException("Creative category " + category + " has no creative inventory tab");
    }
}
