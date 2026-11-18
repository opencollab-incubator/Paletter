package dev.opencollab.paletter.property;

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
    WIRE("wire:");

    private final String prefix;

    PropertyKind(String prefix) {
        this.prefix = prefix;
    }

    /**
     * The prefix of full names of this kind.
     *
     * @return the prefix of {@link Property#fullName()}, including the colon
     */
    public @NonNull String prefix() {
        return prefix;
    }
}
