package org.geysermc.paletter.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects what happens while a palette compiles; {@link #build} makes the immutable {@link PaletteReport}. Used by one
 * thread at a time.
 */
public final class PaletteReportBuilder {
    private final List<PaletteReport.Entry> entries = new ArrayList<>();
    private final Set<BedrockBlockType> unknownBlocks = new LinkedHashSet<>();
    private final Set<BedrockItemVariant> unknownItems = new LinkedHashSet<>();

    public void info(@NonNull String source, @NonNull String message) {
        entries.add(new PaletteReport.Entry(PaletteReport.Level.INFO, source, message));
    }

    public void warn(@NonNull String source, @NonNull String message) {
        entries.add(new PaletteReport.Entry(PaletteReport.Level.WARNING, source, message));
    }

    public void unknownBlocks(@NonNull Collection<BedrockBlockType> types) {
        unknownBlocks.addAll(types);
    }

    public void unknownItems(@NonNull Collection<BedrockItemVariant> variants) {
        unknownItems.addAll(variants);
    }

    public @NonNull List<PaletteReport.Entry> entries() {
        return List.copyOf(entries);
    }

    public @NonNull List<PaletteReport.Entry> warnings() {
        return entries.stream().filter(entry -> entry.level() == PaletteReport.Level.WARNING).toList();
    }

    /** Adds everything of an earlier report, e.g. the one of the palette being extended. */
    public void addAll(@NonNull PaletteReport other) {
        entries.addAll(other.entries());
        unknownBlocks.addAll(other.unknownBlocks());
        unknownItems.addAll(other.unknownItems());
    }

    public @NonNull PaletteReport build(@NonNull DataVersion version) {
        return new PaletteReport(version, entries, unknownBlocks, unknownItems);
    }
}
