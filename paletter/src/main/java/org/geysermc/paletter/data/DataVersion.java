package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A version of bundled vanilla Bedrock data.
 * <p>
 * One data version usually serves several protocol versions (hotfixes rarely change palettes), so lookups by
 * protocol version resolve to the newest data version whose protocol is not newer than the requested one.
 *
 * @param name the version name, e.g. {@code 26.60}
 * @param protocolVersion the first protocol version this data is valid for
 */
public record DataVersion(@NonNull String name, int protocolVersion) implements Comparable<DataVersion> {

    @Override
    public int compareTo(DataVersion other) {
        return Integer.compare(protocolVersion, other.protocolVersion);
    }

    @Override
    public String toString() {
        return name + " (" + protocolVersion + ")";
    }
}
