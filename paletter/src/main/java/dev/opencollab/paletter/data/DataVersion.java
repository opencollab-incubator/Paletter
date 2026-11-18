package dev.opencollab.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A version of bundled vanilla Bedrock data.
 * <p>
 * One data version usually serves several protocol versions (hotfixes rarely change palettes), so lookups by
 * protocol version resolve to the newest data version whose protocol is not newer than the requested one.
 *
 * @param name the version name, e.g. {@code 26.60}
 * @param protocolVersion the first protocol version this data is valid for
 * @param preview whether this is the data of a Bedrock preview build, which only a {@link dev.opencollab.paletter.Paletter}
 *                built with {@link dev.opencollab.paletter.Paletter.Builder#previews(boolean) previews} serves; it does not
 *                take part in {@code equals}, a version is identified by its name and protocol
 * @since 1.0.0
 */
public record DataVersion(@NonNull String name, int protocolVersion, boolean preview) implements Comparable<DataVersion> {

    /**
     * Creates a release version.
     *
     * @param name the version name, e.g. {@code 26.60}
     * @param protocolVersion the first protocol version this data is valid for
     */
    public DataVersion(@NonNull String name, int protocolVersion) {
        this(name, protocolVersion, false);
    }

    /**
     * Tells whether this is the data of a preview build.
     *
     * @return whether this is the data of a preview build
     */
    public boolean isPreview() {
        return preview;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataVersion version && name.equals(version.name) && protocolVersion == version.protocolVersion;
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + protocolVersion;
    }

    @Override
    public int compareTo(DataVersion other) {
        return Integer.compare(protocolVersion, other.protocolVersion);
    }

    @Override
    public String toString() {
        return name + " (" + protocolVersion + ")";
    }
}
