package org.geysermc.paletter.data;

import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.shape.VoxelShapeStore;
import org.geysermc.paletter.internal.data.Renames;

import org.geysermc.paletter.internal.data.PropertyStore;
import org.geysermc.paletter.internal.data.VanillaData;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies vanilla data to a {@link org.geysermc.paletter.Paletter}. {@link #bundled()} reads the data shipped inside
 * the library jar, so no network access or external files are needed at runtime; {@link #directory(Path)} reads the same
 * format from a directory, e.g. data refreshed for a newer Bedrock version than the library bundles.
 * <p>
 * This is a closed set of sources: the loading contract is internal.
 *
 * @since 1.0.0
 */
public interface PaletteDataSource {

    /**
     * The versions this source has data for.
     *
     * @return all available versions, oldest first
     */
    @NonNull List<DataVersion> versions();

    /**
     * The Bedrock Dedicated Server release builds that were run and found to send the data of a version: the build its
     * data was captured from and the builds compared with it and found identical, oldest first; the list may hold the
     * capture build alone. What {@link org.geysermc.paletter.Paletter#verifiedBuilds} returns: a source provides it here.
     * A version serves every protocol from its first one up to the next version's; builds outside the list are served
     * the same data, unverified. The bundled source reads {@code builds.txt}, generated from the record of every checked
     * release build; the default, and a preview version, has none.
     *
     * @param version a version of this source
     * @return the builds, oldest first; empty when none were recorded
     * @since 1.0.0
     */
    default @NonNull List<VerifiedBuild> verifiedBuilds(@NonNull DataVersion version) {
        return List.of();
    }

    /**
     * Internal: loads the data of the given version. Called at most once per version by
     * {@link org.geysermc.paletter.Paletter}.
     *
     * @param version a version of this source
     * @return the data of the version
     * @throws IOException if the data cannot be read
     */
    @org.geysermc.paletter.annotation.Internal
    @NonNull VanillaData load(@NonNull DataVersion version) throws IOException;

    /**
     * Internal: the identifiers Bedrock renamed.
     *
     * @return identifiers Bedrock renamed, which lets content of older versions map forward to the newest one
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull Renames renames() {
        return Renames.NONE;
    }

    /**
     * Internal: the versioned properties of items and blocks.
     *
     * @return the versioned properties of items and blocks; the bundled source reads {@code properties.nbt}, the
     *         default has none
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull PropertyStore properties() {
        return PropertyStore.empty();
    }

    /**
     * Internal: the versioned behaviour values of vanilla blocks and items.
     *
     * @return the versioned behaviour values of vanilla blocks and items; the bundled source reads
     *         {@code behaviour.nbt}, the default has none
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull BehaviourStore behaviour() {
        return BehaviourStore.empty();
    }

    /**
     * Internal: the vanilla voxel shapes of the versions.
     *
     * @return the vanilla voxel shapes of the versions; the bundled source reads {@code voxel_shapes.nbt}, the default
     *         has none. A palette of a version without them cannot be built
     */
    @org.geysermc.paletter.annotation.Internal
    default @NonNull VoxelShapeStore voxelShapes() {
        return VoxelShapeStore.empty();
    }

    /**
     * Returns the source of the data bundled in the library jar.
     *
     * @return a source reading the data bundled in the library jar
     */
    static @NonNull PaletteDataSource bundled() {
        return FileDataSource.BUNDLED;
    }

    /**
     * Returns a source reading a directory in the bundled format, e.g. data refreshed for a newer Bedrock version.
     *
     * @param directory a directory in the bundled format: {@code versions.txt}, one {@code <name>.nbt} per version,
     *                  {@code voxel_shapes.nbt} with the shapes of every version (a palette without them fails to build),
     *                  and optionally {@code properties.nbt}, {@code behaviour.nbt}, {@code renames.txt} and
     *                  {@code builds.txt}. Read when first needed
     * @return a source reading that directory
     * @since 1.0.0
     */
    static @NonNull PaletteDataSource directory(@NonNull Path directory) {
        return FileDataSource.directory(directory);
    }
}
