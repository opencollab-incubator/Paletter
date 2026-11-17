package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * {@code minecraft:custom_components}: which script callbacks the block's custom components register. The client is only
 * told whether any component handles the interaction and the placement; component names and parameters never reach it.
 * Always sent in the form of block format 1.21.90 and later (not as the old list of component names).
 *
 * @param playerInteract whether any component registers {@code onPlayerInteract}
 * @param playerPlacing  whether any component registers {@code beforeOnPlayerPlace}
 * @since 1.0.0
 */
public record ScriptHooks(boolean playerInteract, boolean playerPlacing) implements WireOnlyBlockComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:custom_components";

    /**
     * @param playerInteract whether any component registers {@code onPlayerInteract}
     * @param playerPlacing  whether any component registers {@code beforeOnPlayerPlace}
     * @return the component
     */
    public static @NonNull ScriptHooks of(boolean playerInteract, boolean playerPlacing) {
        return new ScriptHooks(playerInteract, playerPlacing);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }
}
