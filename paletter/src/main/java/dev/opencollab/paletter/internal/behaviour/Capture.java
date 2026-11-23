package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.data.DataVersion;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * What one source said about one data version, as far as the bundled behaviour values need it: which source, what exactly
 * was read and for which version. The generated data has one constant per capture; captures are told apart by identity.
 */
@Internal
public final class Capture {
    private final Provenance.Source source;
    private final String reference;
    private final DataVersion version;

    public Capture(Provenance.@NonNull Source source, @NonNull String reference, @NonNull DataVersion version) {
        this.source = Objects.requireNonNull(source);
        this.reference = Objects.requireNonNull(reference);
        this.version = Objects.requireNonNull(version);
    }

    public Provenance.@NonNull Source source() {
        return source;
    }

    /** @return what was read, e.g. {@code CloudburstMC/Data@0021592...} */
    public @NonNull String reference() {
        return reference;
    }

    /** @return the data version the source was captured for */
    public @NonNull DataVersion version() {
        return version;
    }

    @Override
    public String toString() {
        return source + " " + version.name() + " " + reference;
    }
}
