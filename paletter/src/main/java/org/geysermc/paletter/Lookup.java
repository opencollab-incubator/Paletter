package org.geysermc.paletter;

/**
 * How strictly {@link Palette#blockState(org.cloudburstmc.nbt.NbtMap, Lookup) blockState},
 * {@link Palette#itemVariant(String, int, Lookup) itemVariant} and {@link Palette#itemType(String, Lookup) itemType}
 * match content against the palette's own version: the palette is the version.
 * <pre>{@code
 * palette.blockState(state, Lookup.EXACT);    // exactly as this palette's version has it
 * palette.blockState(state, Lookup.RENAMED);  // also as an older version had it, if this version renamed or changed it
 * }</pre>
 * Custom content of the palette's layers is always accepted. What is returned is the palette's own handle.
 * <p>
 * A palette sees exactly what it has: a lookup returns null for content newer than the palette's version, content
 * removed with {@code VanillaContent#remove}, and content behind an experiment the palette does not enable. To get keys
 * that resolve through fallbacks on every version, look them up on the newest palette, with the experiments you need
 * ({@code VanillaContent#allExperiments()}), or use the constants.
 *
 * @since 1.0.0
 */
public enum Lookup {
    /**
     * The content exists as written in the palette's version.
     */
    EXACT,

    /**
     * Also accepts content of an older bundled version, mapped forward to what the palette's version has under a new
     * name: renamed blocks and items, changed state keys. Only unchanged content is checked against the palette's version.
     */
    RENAMED
}
