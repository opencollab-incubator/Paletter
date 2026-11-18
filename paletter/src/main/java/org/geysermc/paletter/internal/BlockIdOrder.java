package org.geysermc.paletter.internal;

import java.util.Comparator;
import java.util.Locale;

/**
 * The order in which BDS numbers custom blocks: descending by the name of the block's file, and the file listing of Windows
 * BDS sorts names in upper case, so {@code _} comes after every letter ({@code b_x} before {@code block_x} when listed, and
 * after it when descending); digits compare as text ({@code _80} before {@code _8} when descending). Verified on every
 * captured spec capture (1190 blocks and more, no exception; BDS 1.26.36.1 with the schema in the add-on: 1338 blocks).
 * Paletter has no files and uses the identifier, which has the same order for the usual layout of one file per block
 * named after it.
 * <p>
 * The order is per behavior pack: BDS numbers the blocks of a pack after those of the packs before it in the world's pack
 * list (BDS 1.26.36.1: two packs listed after the main pack got the last ids, although by name they come first). Paletter has
 * no packs: its blocks are one pack.
 */
public final class BlockIdOrder {
    /** Custom block identifiers from the lowest block id (10000) up. */
    public static final Comparator<String> DESCENDING = Comparator.comparing((String identifier) -> identifier.toUpperCase(Locale.ROOT))
        .thenComparing(Comparator.<String>naturalOrder()).reversed();

    private BlockIdOrder() {
    }
}
