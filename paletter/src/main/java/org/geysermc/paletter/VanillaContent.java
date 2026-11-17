package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.shape.VoxelShapeStore;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Selects which vanilla items exist, configured on {@code Paletter.Builder}. A removed item is not
 * registered: it has no definition and no creative entry, and resolves through the {@link Fallbacks}. Blocks are never
 * removed, the client's block palette has to match; a block that is missing resolves through the fallbacks as well.
 * <p>
 * Content a version has only behind an experiment toggle (BDS offers it only to worlds with that experiment, see
 * {@code Paletter.Builder#experiments}) is missing unless the toggle is enabled, so ordinary (non-forced) fallbacks apply to it.
 *
 * @since 1.0.0
 */
final class VanillaContent {
    private final Set<String> removed = new HashSet<>();
    private final Set<String> experiments = new HashSet<>();
    private final Map<DataVersion, Set<String>> versionExperiments = new HashMap<>();
    private boolean allExperiments;

    VanillaContent() {
    }

    /**
     * @return an independent copy, so a built Paletter does not share state with its builder
     */
    VanillaContent copy() {
        VanillaContent copy = new VanillaContent();
        copy.removed.addAll(removed);
        copy.experiments.addAll(experiments);
        versionExperiments.forEach((version, toggles) -> copy.versionExperiments.put(version, new HashSet<>(toggles)));
        copy.allExperiments = allExperiments;
        return copy;
    }

    /**
     * Does not register the items.
     */
    void remove(BedrockItemType... types) {
        for (BedrockItemType type : types) {
            removed.add(type.identifier());
        }
    }

    /** Adds toggles enabled in every version, see {@code Paletter.Builder#experiments(String...)}. */
    void experiments(String... toggles) {
        experiments.addAll(List.of(toggles));
    }

    /** Adds toggles enabled in one version, see {@code Paletter.Builder#experiments(DataVersion, String...)}. */
    void experiments(DataVersion version, String... toggles) {
        versionExperiments.computeIfAbsent(version, key -> new HashSet<>()).addAll(List.of(toggles));
    }

    /** @return the versions that have toggles of their own */
    Set<DataVersion> experimentVersions() {
        return versionExperiments.keySet();
    }

    /** See {@code Paletter.Builder#allExperiments()}. */
    void allExperiments() {
        this.allExperiments = true;
    }

    /** @return whether the consumer asked for an experiment toggle in a version */
    boolean requests(DataVersion version, String toggle) {
        return experiments.contains(toggle) || versionExperiments.getOrDefault(version, Set.of()).contains(toggle)
            || allExperiments && !toggle.equals(VoxelShapeStore.EXPERIMENT);
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
            if (requests(data.version(), toggle)) {
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
        Map<String, String> disabledItems = new TreeMap<>();
        Map<String, String> disabledBlocks = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        marks.forEach((toggle, content) -> {
            if (requests(data.version(), toggle)) {
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
            items.forEach(item -> disabledItems.put(item, toggle));
            blocks.forEach(block -> disabledBlocks.put(block, toggle));
            if (!items.isEmpty() || !blocks.isEmpty()) {
                notes.add(data.version().name() + ": " + items.size() + " items, " + blocks.size() + " blocks behind `" + toggle
                    + "` use fallbacks; enable with experiments(\"" + toggle + "\")");
            }
        });
        // A toggle enabled for this version alone that gates nothing in it is most likely a mistake
        new TreeSet<>(versionExperiments.getOrDefault(data.version(), Set.of())).forEach(toggle -> {
            if (!marks.containsKey(toggle) && !toggle.equals(VoxelShapeStore.EXPERIMENT)) {
                notes.add(data.version().name() + ": the experiment `" + toggle + "` is enabled for this version, but gates no content in it");
            }
        });
        ExperimentGating gating = new ExperimentGating(List.copyOf(enabled), Map.copyOf(enabledItems), Map.copyOf(enabledBlocks),
            Set.copyOf(missingBlocks), Map.copyOf(disabledItems), Map.copyOf(disabledBlocks), Set.copyOf(removed), List.copyOf(notes));
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
     * @param disabledItems item identifier to the disabled toggle that removes it
     * @param disabledBlocks block identifier to the disabled toggle that gates it
     * @param removed the identifiers of items taken out with {@link VanillaContent#remove}
     * @param notes one report line per disabled toggle that gates content of the version
     */
    record ExperimentGating(List<String> enabled, Map<String, String> items, Map<String, String> blocks, Set<String> missingBlocks,
                            Map<String, String> disabledItems, Map<String, String> disabledBlocks, Set<String> removed,
                            List<String> notes) {
        static final ExperimentGating NONE = new ExperimentGating(List.of(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Set.of(), List.of());

        @Nullable String ofItem(String identifier) {
            return items.get(identifier);
        }

        @Nullable String ofBlock(String identifier) {
            return blocks.get(identifier);
        }

        /** This gating with one more enabled toggle, one that gates no item or block (the voxel shapes of 26.30). */
        ExperimentGating withEnabled(String toggle) {
            List<String> all = new ArrayList<>(enabled);
            all.add(toggle);
            return new ExperimentGating(all.stream().sorted().distinct().toList(), items, blocks, missingBlocks, disabledItems, disabledBlocks, removed, notes);
        }
    }
}
