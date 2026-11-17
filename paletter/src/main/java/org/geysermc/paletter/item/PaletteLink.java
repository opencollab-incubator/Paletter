package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.behaviour.Behaviour;

/**
 * Plumbing of {@link ResolvedItem#behaviour()}, not part of the API: the palette that owns an item registry, so that
 * what the registry resolves can reach the values of that palette. A registry is made before its palette; the palette
 * binds itself on creation.
 *
 * @since 0.1.0
 */
public final class PaletteLink {
    private volatile @Nullable Palette palette;

    PaletteLink() {
    }

    /** Binds the palette, unless one is bound: a palette that shares the registry of another (a copy with a report) has the same values. */
    @Internal
    public void bind(@NonNull Palette palette) {
        if (this.palette == null) {
            synchronized (this) {
                if (this.palette == null) {
                    this.palette = palette;
                }
            }
        }
    }

    /** @throws IllegalStateException if no palette owns the registry yet, which a resolution of a palette never sees */
    @Internal
    public @NonNull Behaviour behaviour() {
        Palette owner = palette;
        if (owner == null) {
            throw new IllegalStateException("not resolved by a palette");
        }
        return owner.behaviour();
    }
}
