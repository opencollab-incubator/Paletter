package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Supplies vanilla data. The default implementation, {@link #bundled()}, reads the data shipped inside the library jar,
 * so no network access or external files are needed at runtime.
 *
 * @since 1.0.0
 */
public interface PaletteDataSource {

    /**
     * @return all available versions, oldest first
     */
    @NonNull List<DataVersion> versions();

    /**
     * Loads the data of the given version. Called at most once per version by {@link org.geysermc.paletter.Paletter}.
     */
    @NonNull VanillaData load(@NonNull DataVersion version) throws IOException;

    /**
     * @return a source reading the data bundled in the library jar
     */
    static @NonNull PaletteDataSource bundled() {
        return BundledDataSource.INSTANCE;
    }
}
