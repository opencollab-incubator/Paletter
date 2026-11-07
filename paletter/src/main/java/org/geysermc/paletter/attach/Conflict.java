package org.geysermc.paletter.attach;

/**
 * What happens when a value is attached to a target that already has a value for the same key.
 * <p>
 * This only applies to direct attachments. A value that reached its target through a version fallback
 * (e.g. data attached to a block that does not exist in an old version, landing on its replacement) never wins over
 * a direct attachment, and never causes an error.
 */
public enum Conflict {
    /**
     * Compilation fails, naming both values. Use for mappings that must be one to one.
     */
    ERROR,
    /**
     * The first attached value is kept.
     */
    KEEP_FIRST,
    /**
     * The last attached value is kept, so later layers (e.g. session layers) override earlier ones.
     */
    REPLACE
}
