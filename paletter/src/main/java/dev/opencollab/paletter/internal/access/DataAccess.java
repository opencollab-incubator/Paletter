package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.data.DataLoader;
import org.checkerframework.checker.nullness.qual.NonNull;

/** The package-private members of {@code dev.opencollab.paletter.data} that other packages use. Not API. */
public interface DataAccess {
    /** The loading contract of a source: what a Paletter reads from it. */
    @NonNull DataLoader loader(@NonNull PaletteDataSource source);

    /** A source over a loader of its own, e.g. a test's. */
    @NonNull PaletteDataSource source(@NonNull DataLoader loader);
}
