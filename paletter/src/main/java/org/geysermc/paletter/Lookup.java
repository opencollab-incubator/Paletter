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
 *
 * @since 0.1.0
 */
public enum Lookup {
    /**
     * The content exists as written in the palette's version.
     */
    EXACT,

    /**
     * Also accepts what the palette's version has under a new name: renamed blocks and items, changed state keys.
     */
    RENAMED
}
