package org.geysermc.paletter.component;

/**
 * How well the network shape of a generated component type is backed by evidence.
 */
public enum Confidence {
    /**
     * Inferred from NBT the vanilla server actually sends (bundled vanilla data or a capture).
     */
    HIGH,
    /**
     * Inferred from what Geyser sends in production only: the client accepts it, but no vanilla or captured NBT showed
     * the shape. Such types are annotated {@link Unverified}.
     */
    MEDIUM
}
