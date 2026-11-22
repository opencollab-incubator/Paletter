package dev.opencollab.paletter;

/**
 * How a {@link Palette} resolved a block state or an item for its version, see {@link BlockExplanation} and
 * {@link ItemExplanation}.
 *
 * @since 1.0.0
 */
public enum ResolutionKind {
    /**
     * It exists exactly.
     */
    EXACT,
    /**
     * The same block with state values added, removed or reset to fit this version, e.g. stairs before
     * {@code minecraft:corner} existed. Does not occur for items.
     */
    ADAPTED,
    /**
     * It does not exist in this version; a fallback was used.
     */
    FALLBACK,
    /**
     * It does not exist in this version and has no fallback of its own; the default fallback was used.
     */
    DEFAULT,
    /**
     * Nothing suitable exists: blocks are sent as air, items have no definition.
     */
    MISSING
}
