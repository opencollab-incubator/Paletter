package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.data.DataVersion;

import java.util.Objects;

/**
 * How strictly {@link Paletter#block(org.cloudburstmc.nbt.NbtMap, Lookup) block}, {@link Paletter#item(String, int, Lookup) item}
 * and the type lookups of {@link Paletter} match content against the bundled data: the content must exist as is in the
 * data of {@link #version()}, unless {@link #followRenames()} also accepts what the data of the newest version has under
 * a new name (renamed and split blocks and items, added or removed state keys). Content that the newest version has
 * unchanged but {@link #version()} lacks is never accepted: the version gates it, also with renames.
 * <p>
 * The overloads without a {@code Lookup} ({@link Paletter#block(org.cloudburstmc.nbt.NbtMap)},
 * {@link Paletter#item(String, int)}) are not {@link #STRICT}: they accept content of any bundled version, mapped
 * forward, which is {@code STRICT.withRenames()}.
 * <pre>{@code
 * paletter.block(state, Lookup.STRICT);                                  // exactly as the newest version has it
 * paletter.block(state, Lookup.of(version26_50));                        // exactly as 26.50 has it
 * paletter.block(state, Lookup.STRICT.withRenames());                    // as the newest version has it, or as a bundled version had it
 * }</pre>
 * What is returned is always the newest equivalent: handles are those of the newest data.
 *
 * @param version the data version the content must exist in, or null for the newest version the {@link Paletter} serves
 *                (a preview version is only served with {@code -Dpaletter.previews=true})
 * @param followRenames whether content that is not in {@code version} may be mapped forward from any bundled version,
 *                      if the newest version has it changed or under a new name (not if it has it as is)
 * @since 0.1.0
 */
public record Lookup(@Nullable DataVersion version, boolean followRenames) {
    /**
     * Exactly as the newest version has it: no renames, no forwarding. Not what the overloads without a lookup do, see above.
     */
    public static final Lookup STRICT = new Lookup(null, false);

    /**
     * @return a lookup that matches exactly against the data of a version
     */
    public static @NonNull Lookup of(@NonNull DataVersion version) {
        return new Lookup(Objects.requireNonNull(version), false);
    }

    /**
     * @return this lookup, also accepting content of older versions that the newest version renamed or changed
     */
    public @NonNull Lookup withRenames() {
        return new Lookup(version, true);
    }

    @Override
    public String toString() {
        return (version == null ? "the newest version" : version.toString()) + (followRenames ? ", following renames" : ", exact");
    }
}
