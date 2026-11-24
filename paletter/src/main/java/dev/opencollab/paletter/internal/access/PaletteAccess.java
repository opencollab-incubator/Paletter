package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.PaletteReport;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.behaviour.Behaviour;
import dev.opencollab.paletter.internal.block.BlockInterner;
import dev.opencollab.paletter.internal.block.BlockRegistry;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.item.ItemRegistry;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/** The package-private members of {@code dev.opencollab.paletter} that other packages use. Not API. */
public interface PaletteAccess {
    /** {@code Palette#behaviour()}: the behaviour values of a palette, built on first use. */
    @NonNull Behaviour behaviour(@NonNull Palette palette);

    /** {@code Palette#vanillaData()}: the bundled vanilla data of a palette's version. */
    @NonNull VanillaData vanillaData(@NonNull Palette palette);

    /** {@code Palette#blocks()}: the block registry of a palette. */
    @NonNull BlockRegistry blocks(@NonNull Palette palette);

    /** {@code Palette#items()}: the item registry of a palette. */
    @NonNull ItemRegistry items(@NonNull Palette palette);

    /** {@code Paletter#vanillaData(DataVersion)}: the vanilla data of a version, loaded on first use. */
    @NonNull VanillaData vanillaData(@NonNull Paletter paletter, @NonNull DataVersion version);

    /** {@code Paletter#canonicalBlocks()}: the canonical block ids of a Paletter. */
    @NonNull CanonicalBlocks canonicalBlocks(@NonNull Paletter paletter);

    /** {@code Paletter#canonicalItems()}: the canonical item ids of a Paletter. */
    @NonNull CanonicalItems canonicalItems(@NonNull Paletter paletter);

    /** {@code Paletter#blockInterner()}: the interner of the vanilla blocks of a Paletter. */
    @NonNull BlockInterner blockInterner(@NonNull Paletter paletter);

    /** {@code Paletter.bundledLoader()}: the Paletter that loads the bundled data for the keys. */
    @NonNull Paletter bundledLoader();

    /** The constructor of {@link PaletteReport}. */
    @NonNull PaletteReport report(@NonNull DataVersion version, @NonNull List<PaletteReport.Entry> entries,
                                  @NonNull Set<BedrockBlockType> unknownBlocks, @NonNull Set<BedrockItemVariant> unknownItems);

    /** {@code PaletteReport.Entry.of}. */
    PaletteReport.@NonNull Entry entry(PaletteReport.@NonNull Level level, @NonNull String source, @NonNull String code,
                                       @Nullable String subject, @NonNull String message);

    /** {@code PaletteLayer#variants()}: the item variants a layer gives canonical ids to. */
    @NonNull List<ItemVariant> variants(@NonNull PaletteLayer layer);

    /** {@code PaletteLayer.Builder#variants(Collection)}: gives item variants canonical ids. */
    void addVariants(PaletteLayer.@NonNull Builder builder, @NonNull List<ItemVariant> variants);
}
