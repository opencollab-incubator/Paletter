package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.item.ItemFallback;

import java.util.Map;
import java.util.Set;

/**
 * Replacements for blocks and items of the newest bundled version, for an older version that lacks them.
 * <p>
 * Consumers only ever map against the newest Bedrock version; these fallbacks are what lets a single mapping
 * serve every supported version. Added or removed block states need no entries: they are adapted automatically.
 *
 * @param blocks block identifier to its replacement
 * @param items item identifier to its replacement
 * @param unmapped identifiers that are deliberately left without a fallback, e.g. Bedrock-only content no consumer maps
 *                 to. Only used by the generator's coverage check
 *
 * @since 2.0.0
 */
public record VersionFallbacks(@NonNull Map<String, BlockFallback> blocks, @NonNull Map<String, ItemFallback> items,
                               @NonNull Set<String> unmapped) {
    public static final VersionFallbacks NONE = new VersionFallbacks(Map.of(), Map.of(), Set.of());

    public VersionFallbacks {
        blocks = Map.copyOf(blocks);
        items = Map.copyOf(items);
        unmapped = Set.copyOf(unmapped);
    }
}
