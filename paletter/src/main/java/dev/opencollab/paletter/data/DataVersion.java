package dev.opencollab.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * A version of bundled vanilla Bedrock data.
 * <p>
 * One data version usually serves several protocol versions (hotfixes rarely change palettes), so lookups by
 * protocol version resolve to the newest data version whose protocol is not newer than the requested one.
 * <p>
 * A version is identified by its name and protocol: {@code equals}, {@code hashCode} and {@code compareTo} use those two
 * (ordered by protocol, then name), not whether it is a preview or which codec decoded it.
 *
 * @since 1.0.0
 */
public final class DataVersion implements Comparable<DataVersion> {
    private final String name;
    private final int protocolVersion;
    private final boolean preview;
    private final @Nullable Integer codecProtocol;

    private DataVersion(String name, int protocolVersion, boolean preview, @Nullable Integer codecProtocol) {
        this.name = Objects.requireNonNull(name, "name");
        this.protocolVersion = protocolVersion;
        this.preview = preview;
        this.codecProtocol = codecProtocol;
    }

    /**
     * Creates a release version whose data was decoded with its own protocol's codec.
     *
     * @param name the version name, e.g. {@code 26.60}
     * @param protocolVersion the first protocol version this data is valid for
     * @return the version
     * @since 1.0.0
     */
    public static @NonNull DataVersion of(@NonNull String name, int protocolVersion) {
        return new DataVersion(name, protocolVersion, false, null);
    }

    /**
     * Creates the version of a Bedrock preview build, which only a {@link dev.opencollab.paletter.Paletter} built with
     * {@link dev.opencollab.paletter.Paletter.Builder#previews(boolean) previews} serves.
     *
     * @param name the version name, e.g. {@code 26.70}
     * @param protocolVersion the first protocol version this data is valid for
     * @return the version
     * @since 1.0.0
     */
    public static @NonNull DataVersion preview(@NonNull String name, int protocolVersion) {
        return new DataVersion(name, protocolVersion, true, null);
    }

    /**
     * Returns this version with the protocol of the codec that decoded its captures.
     *
     * @param codecProtocol the protocol of the codec, if that was the codec of another protocol (a preview newer than
     *                      every codec of the protocol library), else null
     * @return a version equal to this one, with that codec protocol
     * @since 1.0.0
     */
    public @NonNull DataVersion withCodecProtocol(@Nullable Integer codecProtocol) {
        return new DataVersion(name, protocolVersion, preview, codecProtocol);
    }

    /**
     * The name of the version.
     *
     * @return the version name, e.g. {@code 26.60}
     * @since 1.0.0
     */
    public @NonNull String name() {
        return name;
    }

    /**
     * The first protocol version this data is valid for.
     *
     * @return the protocol version
     * @since 1.0.0
     */
    public int protocolVersion() {
        return protocolVersion;
    }

    /**
     * The protocol of the codec that decoded the captures this data was built from, where that was not the version's
     * own: a preview captured before the protocol library had a codec for it. Such data may lack what the newer
     * protocol added to the packets it was read from.
     *
     * @return the protocol of the codec, or null if the data was decoded with the codec of its own protocol
     * @since 1.0.0
     */
    public @Nullable Integer codecProtocol() {
        return codecProtocol;
    }

    /**
     * Tells whether this is the data of a preview build.
     *
     * @return whether this is the data of a preview build
     * @since 1.0.0
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

    // By protocol, then by name: consistent with equals
    @Override
    public int compareTo(DataVersion other) {
        int byProtocol = Integer.compare(protocolVersion, other.protocolVersion);
        return byProtocol != 0 ? byProtocol : name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name + " (" + protocolVersion + ")";
    }
}
