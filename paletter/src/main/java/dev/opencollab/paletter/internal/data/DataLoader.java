package dev.opencollab.paletter.internal.data;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.VerifiedBuild;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * The loading contract behind a {@code PaletteDataSource}: what a Paletter reads from its data. The sources of the API
 * implement it; a test or tool makes a source of its own one with {@code Access.data().source(loader)}. Not API.
 */
public interface DataLoader {
    /**
     * @return all available versions, oldest first, as {@code PaletteDataSource#versions()}
     */
    @NonNull List<DataVersion> versions();

    /**
     * @param version a version of this source
     * @return the builds, as {@code PaletteDataSource#verifiedBuilds(DataVersion)}; by default none
     */
    default @NonNull List<VerifiedBuild> verifiedBuilds(@NonNull DataVersion version) {
        return List.of();
    }

    /**
     * Loads the data of the given version. Called at most once per version by {@code Paletter}.
     *
     * @param version a version of this source
     * @return the data of the version
     * @throws IOException if the data cannot be read
     */
    @NonNull VanillaData load(@NonNull DataVersion version) throws IOException;

    /**
     * @return identifiers Bedrock renamed, which lets content of older versions map forward to the newest one
     */
    default @NonNull Renames renames() {
        return Renames.NONE;
    }

    /**
     * @return the versioned properties of items and blocks; the bundled source reads {@code properties.nbt}, the
     *         default has none
     */
    default @NonNull PropertyStore properties() {
        return PropertyStore.empty();
    }

    /**
     * Drops what was read for {@link #properties()}, once the versions in use are loaded. A later call reads it again.
     */
    default void releaseProperties() {
    }

    /**
     * @return the vanilla voxel shapes of the versions; the bundled source builds them from generated classes, the
     *         default has none. A palette of a version without them cannot be built
     */
    default @NonNull VoxelShapeStore voxelShapes() {
        return VoxelShapeStore.empty();
    }
}
