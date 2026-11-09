package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Selects which vanilla content exists, configured in {@code Paletter.Builder#vanilla}. A removed item is not
 * registered: it has no definition and no creative entry, and resolves through the {@link Fallbacks}. Blocks are never
 * removed, the client's block palette has to match; a block that is missing resolves through the fallbacks as well.
 * <p>
 * Content a version has only behind an experiment toggle (BDS offers it only to worlds with that experiment, see
 * {@link #experiments}) is missing unless the toggle is enabled, so ordinary (non-forced) fallbacks apply to it.
 *
 * @since 1.0.0
 */
public final class VanillaContent {
    private final Set<String> removed = new HashSet<>();
    private Set<String> experiments = Set.of();

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
     * Sets the enabled experiment toggles (e.g. {@code y_2026_drop_4}). By default none is enabled: the items and blocks
     * a version has only behind a toggle are missing in its palette, so the {@link Fallbacks} apply to them like to
     * content the version lacks altogether (and {@code force} is only for content that is really there). An enabled
     * toggle makes its content present in the versions that gate it; those palettes list the toggle in
     * {@link Palette#experiments()} and send it with {@link Palette#applyTo(org.cloudburstmc.protocol.bedrock.packet.StartGamePacket)}.
     * A palette tells the toggle of a piece of content with {@code Palette#experimentOf}, and its report has a line for
     * every toggle left disabled.
     *
     * @param toggles the toggle ids, as BDS names them
     */
    public @NonNull VanillaContent experiments(@NonNull String... toggles) {
        this.experiments = new HashSet<>(List.of(toggles));
        return this;
    }

    /**
     * @param data the data of a version
     * @return the data without the removed items and the content of disabled experiments, and what that did
     */
    Applied apply(VanillaData data) {
        // Toggles that gate something in this version
        Map<String, ExperimentContent> marks = new TreeMap<>();
        data.experiments().forEach((toggle, content) -> {
            if (!content.isEmpty()) {
                marks.put(toggle, content);
            }
        });
        List<String> enabled = new ArrayList<>();
        Map<String, String> enabledItems = new TreeMap<>();
        Map<String, String> enabledBlocks = new TreeMap<>();
        marks.forEach((toggle, content) -> {
            if (experiments.contains(toggle)) {
                enabled.add(toggle);
                content.items().forEach(item -> enabledItems.putIfAbsent(item, toggle));
                content.blocks().forEach(block -> enabledBlocks.putIfAbsent(block, toggle));
            }
        });

        // A block whose item only exists behind a toggle is gated with it (poplar planks and the poplar boat)
        data.blockItems().forEach((block, item) -> {
            String toggle = enabledItems.get(item);
            if (toggle != null) {
                enabledBlocks.putIfAbsent(block, toggle);
            }
        });
        Set<String> itemsInData = new HashSet<>();
        data.items().forEach(item -> itemsInData.add(item.identifier()));
        Set<String> blocksInData = new HashSet<>();
        data.blockStates().forEach(state -> blocksInData.add(state.getString("name")));
        Set<String> remove = new HashSet<>(removed);
        Set<String> missingBlocks = new TreeSet<>();
        List<String> notes = new ArrayList<>();
        marks.forEach((toggle, content) -> {
            if (experiments.contains(toggle)) {
                return;
            }
            List<String> items = content.items().stream().filter(item -> !enabledItems.containsKey(item) && itemsInData.contains(item)).toList();
            Set<String> gatedItems = new HashSet<>(content.items());
            Set<String> gatedBlocks = new TreeSet<>(content.blocks());
            data.blockItems().forEach((block, item) -> {
                if (gatedItems.contains(item)) {
                    gatedBlocks.add(block);
                }
            });
            List<String> blocks = gatedBlocks.stream().filter(block -> !enabledBlocks.containsKey(block) && blocksInData.contains(block)).toList();
            remove.addAll(items);
            missingBlocks.addAll(blocks);
            if (!items.isEmpty() || !blocks.isEmpty()) {
                notes.add(data.version().name() + ": " + items.size() + " items, " + blocks.size() + " blocks behind `" + toggle
                    + "` use fallbacks; enable with vanilla(v -> v.experiments(\"" + toggle + "\"))");
            }
        });
        ExperimentGating gating = new ExperimentGating(List.copyOf(enabled), Map.copyOf(enabledItems), Map.copyOf(enabledBlocks),
            Set.copyOf(missingBlocks), List.copyOf(notes));
        if (remove.isEmpty() && missingBlocks.isEmpty()) {
            return new Applied(data, gating);
        }
        List<VanillaItem> items = new ArrayList<>();
        for (VanillaItem item : data.items()) {
            if (!remove.contains(item.identifier())) {
                items.add(item);
            }
        }
        Map<String, List<String>> tags = new LinkedHashMap<>();
        data.itemTags().forEach((tag, ids) -> tags.put(tag, ids.stream().filter(id -> !remove.contains(id)).toList()));
        List<BlockPropertyData> dataDriven = data.dataDrivenBlocks().stream().filter(block -> !missingBlocks.contains(block.getName())).toList();
        return new Applied(new VanillaData(data.version(), data.blockStates(), items, dataDriven,
            data.creative().remove(stack -> remove.contains(stack.identifier())), tags,
            data.fallbacks(), data.blockDefaults(), data.experiments(), data.blockItems()), gating);
    }

    /**
     * @param data the data with the content of disabled experiments and removed items taken out
     * @param gating which experiments the palette has and what they gate
     */
    record Applied(VanillaData data, ExperimentGating gating) {
    }

    /**
     * What experiment toggles do to one version's palette.
     *
     * @param enabled the enabled toggles that gate something in this version, sorted
     * @param items item identifier to the enabled toggle that makes it present
     * @param blocks block identifier to the enabled toggle that makes it present
     * @param missingBlocks blocks present in the palette that are missing, as a disabled toggle gates them
     * @param notes one report line per disabled toggle that gates content of the version
     */
    record ExperimentGating(List<String> enabled, Map<String, String> items, Map<String, String> blocks, Set<String> missingBlocks,
                            List<String> notes) {
        static final ExperimentGating NONE = new ExperimentGating(List.of(), Map.of(), Map.of(), Set.of(), List.of());

        @Nullable String ofItem(String identifier) {
            return items.get(identifier);
        }

        @Nullable String ofBlock(String identifier) {
            return blocks.get(identifier);
        }
    }
}
