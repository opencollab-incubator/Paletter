package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Where a behaviour value comes from, and how far to trust it.
 * <p>
 * Values come from BDS itself wherever possible (Paletter's own captures: {@link Source#NATIVE}, {@link Source#SCRIPT_API},
 * {@link Source#WIRE}); third-party dumps of BDS ({@link Source#CLOUDBURST_DATA}, {@link Source#ALLAY_DATA}) rank below
 * them and say so ({@link #thirdParty()}). Where sources disagree and no BDS evidence settles it, the value is
 * {@link #disputed()} and the other sources' values are in {@link #alternatives()}: nothing is picked silently.
 * Custom content says whether a value was set by the user ({@link #userSet()}) or is a BDS default.
 *
 * @param source the kind of source
 * @param reference what exactly was read, e.g. {@code CloudburstMC/Data@0021592...} or the layer of a custom block
 * @param captured the data version the source was captured for
 * @param version the data version the value was asked for
 * @param alternatives the values other sources of the same version gave, when they differ from this one and nothing
 *                     direct from BDS settles it; empty if the value is not disputed
 * @param postProcessing how the source itself post-processed the value (e.g. {@code door_state_correction}: the
 *                       dump's author corrected it after the BDS dump), or null if the source did not say so
 * @param unverified true if the value goes through a conversion no BDS evidence confirmed yet (e.g. the friction of a
 *                   custom block: engine friction is assumed to be 1 minus the component value)
 * @since 0.1.0
 */
public record Provenance(@NonNull Source source, @NonNull String reference, @NonNull String captured, @NonNull String version,
                         @NonNull List<Alternative> alternatives, @Nullable String postProcessing, boolean unverified) {

    public Provenance {
        alternatives = List.copyOf(alternatives);
    }

    /**
     * A plain provenance: not disputed, not post-processed, verified.
     */
    public Provenance(@NonNull Source source, @NonNull String reference, @NonNull String captured, @NonNull String version) {
        this(source, reference, captured, version, List.of(), null, false);
    }

    /**
     * The provenance of a value nothing says: the BDS default of a custom block or item without the component, which
     * has not been captured yet.
     */
    public static @NonNull Provenance unknownDefault(@NonNull String version) {
        return new Provenance(Source.UNKNOWN_DEFAULT, "the BDS default is not captured yet", version, version);
    }

    /**
     * @return true if no source was captured for {@link #version()} itself: the value is the one of the older version
     * {@link #captured()}, inherited because nothing newer said otherwise
     */
    public boolean inherited() {
        return !captured.equals(version);
    }

    /**
     * @return true if the user set the value with a component of their custom content (or of an override of a vanilla
     * item), false if it is a vanilla value or a BDS default
     */
    public boolean userSet() {
        return source == Source.USER;
    }

    /**
     * @return true if the value is a BDS default of a custom block or item that did not set it
     */
    public boolean bdsDefault() {
        return source == Source.BDS_DEFAULT;
    }

    /**
     * @return true if there is no value because the BDS default of this key is not known yet
     */
    public boolean unknownDefault() {
        return source == Source.UNKNOWN_DEFAULT;
    }

    /**
     * @return true if the value is from a third-party dump of BDS rather than from Paletter's own capture
     */
    public boolean thirdParty() {
        return source.thirdParty();
    }

    /**
     * @return true if sources disagree on this value and no BDS evidence settles it: see {@link #alternatives()}
     */
    public boolean disputed() {
        return !alternatives.isEmpty();
    }

    /**
     * @return true if the source post-processed the value, see {@link #postProcessing()}
     */
    public boolean postProcessed() {
        return postProcessing != null;
    }

    /**
     * What another source said about the same value.
     *
     * @param source the kind of source
     * @param reference what exactly was read
     * @param captured the data version it was captured for
     * @param value the value it gave, of the key's type
     * @since 0.1.0
     */
    public record Alternative(@NonNull Source source, @NonNull String reference, @NonNull String captured, @NonNull Object value) {
    }

    /**
     * The kinds of sources, strongest first: when several have a value for the same version, the first one is used and
     * the others only cross-check it. Direct BDS evidence ranks above third-party dumps, and CloudburstMC/Data above
     * Allay; {@link #USER}, {@link #BDS_DEFAULT} and {@link #UNKNOWN_DEFAULT} only occur for custom content.
     *
     * @since 0.1.0
     */
    public enum Source {
        /** BDS internals, read by a native mod (LeviLamina). */
        NATIVE(false),
        /** BDS Script API, captured by Paletter's tools. */
        SCRIPT_API(false),
        /** What BDS sends clients: the component data of vanilla items and data driven blocks. */
        WIRE(false),
        /** CloudburstMC/Data (Apache-2.0), a native BDS dump; third party, and it post-processes some values. */
        CLOUDBURST_DATA(true),
        /** AllayMC/Allay {@code data/} (MIT), the raw Endstone DevTools dump of BDS; third party. */
        ALLAY_DATA(true),
        /** A component the user set on custom content (or on an override of a vanilla item). */
        USER(false),
        /** The value BDS uses for a custom block or item that does not set the component, captured from BDS. */
        BDS_DEFAULT(false),
        /** The BDS default of a custom block or item is not known (not captured yet); there is no value. */
        UNKNOWN_DEFAULT(false);

        private final boolean thirdParty;

        Source(boolean thirdParty) {
            this.thirdParty = thirdParty;
        }

        /**
         * @return true if this is a third-party dump rather than Paletter's own capture of BDS
         */
        public boolean thirdParty() {
            return thirdParty;
        }
    }
}
