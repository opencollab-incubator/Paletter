package dev.opencollab.paletter.data;

import dev.opencollab.paletter.internal.data.DataLoader;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Objects;

/** A source over a {@link DataLoader} of Paletter's own, e.g. a test's, made through the accessor of this package. */
final class LoaderDataSource implements PaletteDataSource {
    final DataLoader loader;

    LoaderDataSource(DataLoader loader) {
        this.loader = Objects.requireNonNull(loader);
    }

    @Override
    public @NonNull List<DataVersion> versions() {
        return loader.versions();
    }

    @Override
    public @NonNull List<VerifiedBuild> verifiedBuilds(@NonNull DataVersion version) {
        return loader.verifiedBuilds(version);
    }
}
