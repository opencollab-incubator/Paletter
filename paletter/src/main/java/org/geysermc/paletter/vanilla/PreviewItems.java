package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.internal.CanonicalItems;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The types of the items a palette's newest version has that the newest release, which the generated constants and
 * {@link VanillaIndex} are linked to, lacks: with previews, those only the preview has. Their constants
 * ({@link org.geysermc.paletter.annotation.SincePreview}) resolve through their fallback, normally to
 * {@code minecraft:unknown}; these types resolve to the item itself, by identifier in the palette's canonical items. They
 * are the handles of the preview's canonical ids and the items of its creative inventory. One type per identifier and
 * root; it equals the constant of the identifier, like every type of that identifier.
 */
final class PreviewItems {
    private static final Map<CanonicalItems, Map<String, BedrockItemType>> BY_ROOT = Collections.synchronizedMap(new WeakHashMap<>());

    private PreviewItems() {
    }

    /**
     * @param root the canonical items of a palette lineage
     * @param identifier an item of its newest version that has no available constant
     * @return the type of the item in this root
     */
    static BedrockItemType type(CanonicalItems root, String identifier) {
        return BY_ROOT.computeIfAbsent(root, key -> new ConcurrentHashMap<>()).computeIfAbsent(identifier, BedrockItemType::preview);
    }
}
