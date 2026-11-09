package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects which vanilla content exists, configured in {@code Paletter.Builder#vanilla}. A removed item is not
 * registered: it has no definition and no creative entry, and resolves through the {@link Fallbacks}. Blocks are never
 * removed, the client's block palette has to match.
 *
 * @since 1.0.0
 */
public final class VanillaContent {
    private final Set<String> removed = new HashSet<>();
    private @Nullable Set<String> experiments;

    VanillaContent() {
    }

    /**
     * Does not register the item.
     */
    public @NonNull VanillaContent remove(@NonNull BedrockItemType item) {
        removed.add(item.identifier());
        return this;
    }

    /**
     * Sets the enabled experiment toggles (e.g. {@code y_2026_drop_4}). Items gated by any other toggle are removed
     * like {@link #remove}, unless an enabled toggle gates them too. By default all toggles are enabled.
     */
    public @NonNull VanillaContent experiments(@NonNull String... toggles) {
        this.experiments = new HashSet<>(List.of(toggles));
        return this;
    }

    /**
     * @return the data without the removed items, or the same data if nothing is removed
     */
    VanillaData apply(VanillaData data) {
        Set<String> remove = new HashSet<>(removed);
        if (experiments != null) {
            Set<String> kept = new HashSet<>();
            data.experiments().forEach((toggle, content) -> {
                if (experiments.contains(toggle)) {
                    kept.addAll(content.items());
                }
            });
            data.experiments().forEach((toggle, content) -> {
                if (!experiments.contains(toggle)) {
                    content.items().stream().filter(item -> !kept.contains(item)).forEach(remove::add);
                }
            });
        }
        if (remove.isEmpty()) {
            return data;
        }
        List<VanillaItem> items = new ArrayList<>();
        for (VanillaItem item : data.items()) {
            if (!remove.contains(item.identifier())) {
                items.add(item);
            }
        }
        Map<String, List<String>> tags = new LinkedHashMap<>();
        data.itemTags().forEach((tag, ids) -> tags.put(tag, ids.stream().filter(id -> !remove.contains(id)).toList()));
        CreativeEditor creative = data.creative().edit();
        remove.forEach(id -> creative.remove(ItemMatcher.id(id)));
        return new VanillaData(data.version(), data.blockStates(), items, data.dataDrivenBlocks(), creative.build(), tags,
            data.fallbacks(), data.blockDefaults(), data.experiments(), data.blockItems());
    }
}
