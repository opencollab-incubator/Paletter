package dev.opencollab.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Supplies vanilla data to a {@link dev.opencollab.paletter.Paletter}. {@link #bundled()} reads the data shipped inside
 * the library jar, so no network access or external files are needed at runtime; {@link #directory(Path)} reads the same
 * format from a directory, e.g. data refreshed for a newer Bedrock version than the library bundles.
 * <p>
 * This is a closed set of sources (a sealed interface): the loading contract is internal.
 *
 * @since 1.0.0
 */
public sealed interface PaletteDataSource permits FileDataSource, LoaderDataSource {

    /**
     * Lists the versions this source has data for. The {@link DataVersion#isPreview() previews} are among them: a
     * {@link dev.opencollab.paletter.Paletter} serves those only if built with
     * {@link dev.opencollab.paletter.Paletter.Builder#previews(boolean) previews(true)}.
     *
     * @return all available versions, oldest first
     */
    @NonNull List<DataVersion> versions();

    /**
     * The Bedrock Dedicated Server release builds that were run and found to send the data of a version: the build its
     * data was captured from and the builds compared with it and found identical, oldest first; the list may hold the
     * capture build alone. What {@link dev.opencollab.paletter.Paletter#verifiedBuilds} returns: a source provides it here.
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
     * @param directory a directory in the bundled format: {@code versions.txt}, one {@code <name>.nbt} per version (with
     *                  its block states, or without them for a version the library has generated block definitions of),
     *                  and optionally {@code voxel_shapes.nbt} with the shapes of every version (without it, the
     *                  library's own; a palette of a version without shapes fails to build), {@code properties.nbt},
     *                  {@code renames.txt} and {@code builds.txt}. Read when first needed
     * @return a source reading that directory
     * @since 1.0.0
     */
    static @NonNull PaletteDataSource directory(@NonNull Path directory) {
        return FileDataSource.directory(directory);
    }
}
