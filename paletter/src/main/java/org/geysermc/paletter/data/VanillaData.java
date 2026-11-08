package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.creative.CreativeLayout;

import java.util.List;
import java.util.Map;

/**
 * All vanilla Bedrock data for one {@link DataVersion}. Immutable.
 *
 * @param version the data version
 * @param blockStates every vanilla block state as {@code {name, states}}, in vanilla runtime id order
 * @param items every vanilla item
 * @param dataDrivenBlocks block property data of vanilla data driven blocks, sent in the start game packet
 * @param creative the vanilla creative inventory layout
 * @param itemTags vanilla item tags: tag to item identifiers
 * @param fallbacks replacements for content that only exists in newer versions
 * @param blockDefaults default state of each block as {@code {name, states}}; may be empty if the dump did not include
 *                      them, in which case a block's first state is its default
 * @param experiments content gated behind an experiment toggle in this version, by toggle id (e.g.
 *                    {@code y_2026_drop_4}); empty if the version was not captured with experiments
 * @param blockItems the block to item relation of the Script API: block identifier to the identifier of the item that
 *                   places it (several blocks can share an item: slabs and double slabs, signs and wall signs). Empty
 *                   for data that predates it; {@code ItemPalette} then guesses
 *
 * @since 1.0.0
 */
public record VanillaData(
    @NonNull DataVersion version,
    @NonNull List<NbtMap> blockStates,
    @NonNull List<VanillaItem> items,
    @NonNull List<BlockPropertyData> dataDrivenBlocks,
    @NonNull CreativeLayout creative,
    @NonNull Map<String, List<String>> itemTags,
    @NonNull VersionFallbacks fallbacks,
    @NonNull Map<String, NbtMap> blockDefaults,
    @NonNull Map<String, ExperimentContent> experiments,
    @NonNull Map<String, String> blockItems
) {
    public VanillaData {
        blockStates = List.copyOf(blockStates);
        items = List.copyOf(items);
        dataDrivenBlocks = List.copyOf(dataDrivenBlocks);
        itemTags = Map.copyOf(itemTags);
        blockDefaults = Map.copyOf(blockDefaults);
        experiments = Map.copyOf(experiments);
        blockItems = Map.copyOf(blockItems);
    }

    /** Data without the block to item relation. */
    public VanillaData(@NonNull DataVersion version, @NonNull List<NbtMap> blockStates, @NonNull List<VanillaItem> items,
                       @NonNull List<BlockPropertyData> dataDrivenBlocks, @NonNull CreativeLayout creative,
                       @NonNull Map<String, List<String>> itemTags, @NonNull VersionFallbacks fallbacks,
                       @NonNull Map<String, NbtMap> blockDefaults, @NonNull Map<String, ExperimentContent> experiments) {
        this(version, blockStates, items, dataDrivenBlocks, creative, itemTags, fallbacks, blockDefaults, experiments, Map.of());
    }

    /** Data without experiment information. */
    public VanillaData(@NonNull DataVersion version, @NonNull List<NbtMap> blockStates, @NonNull List<VanillaItem> items,
                       @NonNull List<BlockPropertyData> dataDrivenBlocks, @NonNull CreativeLayout creative,
                       @NonNull Map<String, List<String>> itemTags, @NonNull VersionFallbacks fallbacks,
                       @NonNull Map<String, NbtMap> blockDefaults) {
        this(version, blockStates, items, dataDrivenBlocks, creative, itemTags, fallbacks, blockDefaults, Map.of(), Map.of());
    }

    /** The same data with other experiment information. */
    public @NonNull VanillaData withExperiments(@NonNull Map<String, ExperimentContent> experiments) {
        return new VanillaData(version, blockStates, items, dataDrivenBlocks, creative, itemTags, fallbacks, blockDefaults, experiments, blockItems);
    }

    /** The same data with another block to item relation. */
    public @NonNull VanillaData withBlockItems(@NonNull Map<String, String> blockItems) {
        return new VanillaData(version, blockStates, items, dataDrivenBlocks, creative, itemTags, fallbacks, blockDefaults, experiments, blockItems);
    }

    /** The toggles that gate a block in this version (usually none). */
    public @NonNull List<String> experimentsOfBlock(@NonNull String identifier) {
        return experiments.entrySet().stream().filter(e -> e.getValue().blocks().contains(identifier)).map(Map.Entry::getKey).sorted().toList();
    }

    /** The toggles that gate an item in this version (usually none). */
    public @NonNull List<String> experimentsOfItem(@NonNull String identifier) {
        return experiments.entrySet().stream().filter(e -> e.getValue().items().contains(identifier)).map(Map.Entry::getKey).sorted().toList();
    }
}
