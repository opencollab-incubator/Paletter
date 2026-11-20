package dev.opencollab.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * {@code minecraft:custom_components}: which script callbacks the block's custom components register. The client is only
 * told whether any component handles the interaction and the placement; component names and parameters never reach it.
 * Always sent in the form of block format 1.21.90 and later (not as the old list of component names).
 *
 * @since 1.0.0
 */
public final class ScriptHooks implements WireOnlyBlockComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:custom_components";

    private final boolean playerInteract;
    private final boolean playerPlacing;

    private ScriptHooks(boolean playerInteract, boolean playerPlacing) {
        this.playerInteract = playerInteract;
        this.playerPlacing = playerPlacing;
    }

    /**
     * Returns whether a custom component handles the interaction.
     *
     * @return whether any component registers {@code onPlayerInteract}
     */
    public boolean playerInteract() {
        return playerInteract;
    }

    /**
     * Returns whether a custom component handles the placement.
     *
     * @return whether any component registers {@code beforeOnPlayerPlace}
     */
    public boolean playerPlacing() {
        return playerPlacing;
    }

    /**
     * Creates the component.
     *
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

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ScriptHooks other && playerInteract == other.playerInteract && playerPlacing == other.playerPlacing;
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(playerInteract) + Boolean.hashCode(playerPlacing);
    }

    @Override
    public String toString() {
        return "ScriptHooks[playerInteract=" + playerInteract + ", playerPlacing=" + playerPlacing + "]";
    }
}
