package org.geysermc.paletter.data;

import org.geysermc.paletter.internal.data.Renames;

import org.geysermc.paletter.internal.data.PropertyStore;
import org.geysermc.paletter.internal.data.VanillaData;

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
    @org.geysermc.paletter.annotation.Internal
    @NonNull VanillaData load(@NonNull DataVersion version) throws IOException;

    /**
     * @return identifiers Bedrock renamed, which lets content of older versions map forward to the newest one
     * @since 1.0.0
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull Renames renames() {
        return Renames.NONE;
    }

    /**
     * @return the versioned properties of items and blocks; the bundled source reads {@code properties.nbt}, the
     * default has none (data in format 1 carries its own)
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull PropertyStore properties() {
        return PropertyStore.empty();
    }

    /**
     * @return a source reading the data bundled in the library jar
     */
    static @NonNull PaletteDataSource bundled() {
        return BundledDataSource.INSTANCE;
    }
}
