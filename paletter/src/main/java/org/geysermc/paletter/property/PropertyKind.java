package org.geysermc.paletter.property;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What kind of data a {@link Property} is.
 *
 * @since 1.0.0
 */
public enum PropertyKind {
    /** One entry of the component compound an item or block sends over the network ({@code minecraft:max_stack_size}). */
    COMPONENT("component:"),
    /** Any other top level entry of the network data of an item or block ({@code menu_category}, {@code molangVersion}). */
    WIRE("wire:"),
    /** Behaviour the client does not receive (friction, destroy time), captured from the BDS Script API. Reserved. */
    BEHAVIOUR("behaviour:");

    private final String prefix;

    PropertyKind(String prefix) {
        this.prefix = prefix;
    }

    /**
     * @return the prefix of {@link Property#fullName()}, including the colon
     */
    public @NonNull String prefix() {
        return prefix;
    }
}
