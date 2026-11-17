package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A Bedrock Dedicated Server release build that was run and found to send the data of a {@link DataVersion}: the build
 * the data was captured from, or one whose captures were identical to it. See
 * {@link PaletteDataSource#verifiedBuilds(DataVersion)}.
 *
 * @param bdsVersion the BDS build number, e.g. {@code 1.26.63.1}
 * @param protocolVersion the protocol version the build speaks
 * @since 1.0.0
 */
public record VerifiedBuild(@NonNull String bdsVersion, int protocolVersion) {

    @Override
    public String toString() {
        return bdsVersion + " (" + protocolVersion + ")";
    }
}
