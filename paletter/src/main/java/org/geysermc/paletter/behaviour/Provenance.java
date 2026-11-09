package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Where a behaviour value comes from.
 *
 * @param source the kind of source
 * @param reference what exactly was read, e.g. {@code CloudburstMC/Data@0021592...} or the layer of a custom block
 * @param captured the data version the source was captured for
 * @param version the data version the value was asked for
 * @since 0.1.0
 */
public record Provenance(@NonNull Source source, @NonNull String reference, @NonNull String captured, @NonNull String version) {

    /**
     * @return true if no source was captured for {@link #version()} itself: the value is the one of the older version
     * {@link #captured()}, inherited because nothing newer said otherwise
     */
    public boolean inherited() {
        return !captured.equals(version);
    }

    /**
     * The kinds of sources, strongest first: when several have a value for the same version, the first one is used and
     * the others only cross-check it.
     *
     * @since 0.1.0
     */
    public enum Source {
        /** BDS internals, read by a native mod (LeviLamina). */
        NATIVE,
        /** BDS Script API, captured by Paletter's tools. */
        SCRIPT_API,
        /** CloudburstMC/Data (Apache-2.0), a native BDS dump. */
        CLOUDBURST_DATA,
        /** AllayMC/Allay {@code data/} (MIT), a native BDS dump (Endstone). */
        ALLAY_DATA,
        /** What BDS sends clients: the component data of vanilla items and data driven blocks. */
        WIRE,
        /** The components of custom content a layer added. */
        COMPONENT
    }
}
