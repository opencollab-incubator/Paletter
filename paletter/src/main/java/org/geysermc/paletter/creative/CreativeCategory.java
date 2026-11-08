package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemCategory;

import java.util.Locale;

/**
 * The four tabs of the creative inventory, in the order the client expects them.
 * <p>
 * Bedrock refers to a category in three different ways, which is easy to mix up: the protocol enum
 * ({@link CreativeItemCategory}, whose ordinal is offset by {@code ALL}), the numeric id used in the
 * {@code creative_category} item property, and the name used in a block's {@code menu_category}.
 * This enum is the only place that knows all three.
 *
 * @since 1.0.0
 */
public enum CreativeCategory {
    CONSTRUCTION("construction", 1, CreativeItemCategory.CONSTRUCTION),
    NATURE("nature", 2, CreativeItemCategory.NATURE),
    EQUIPMENT("equipment", 3, CreativeItemCategory.EQUIPMENT),
    ITEMS("items", 4, CreativeItemCategory.ITEMS);

    /**
     * Name to use in {@code menu_category} for blocks that are not in the creative inventory.
     */
    public static final String NONE_NAME = "none";

    private static final CreativeCategory[] VALUES = values();

    private final String bedrockName;
    private final int itemPropertyId;
    private final CreativeItemCategory protocol;

    CreativeCategory(String bedrockName, int itemPropertyId, CreativeItemCategory protocol) {
        this.bedrockName = bedrockName;
        this.itemPropertyId = itemPropertyId;
        this.protocol = protocol;
    }

    /**
     * @return the name used in data, e.g. {@code construction}, and in block {@code menu_category} NBT
     */
    public @NonNull String bedrockName() {
        return bedrockName;
    }

    /**
     * @return the value of the {@code creative_category} item property of data driven items
     */
    public int itemPropertyId() {
        return itemPropertyId;
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
