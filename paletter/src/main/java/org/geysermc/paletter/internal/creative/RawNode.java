package org.geysermc.paletter.internal.creative;

/**
 * An element of a raw creative category: a {@link RawGroup} or a loose {@link RawStack}.
 */
public sealed interface RawNode permits RawGroup, RawStack {
}
