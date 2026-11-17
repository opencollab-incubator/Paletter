package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.behaviour.BlockValues;

/**
 * A block state resolved against one palette: what it sends for it and what that does, see
 * {@link org.geysermc.paletter.Palette#resolve(org.geysermc.paletter.vanilla.BedrockBlockState)}. A record is made on
 * each call (the {@link BlockValues} handle with it); inlined it costs nothing, and it is not cached because
 * the lookup is an array read. Use {@link org.geysermc.paletter.Palette#runtimeId(org.geysermc.paletter.vanilla.BedrockBlockState)}
 * where only the id is needed.
 *
 * @param definition the block to send, with its fallbacks applied, see {@link org.geysermc.paletter.Palette#blockDefinition(org.geysermc.paletter.vanilla.BedrockBlockState)}
 * @param fallback true if the state is sent as another state: it has no block of its own in this version, or one with
 *                 other state values, and a replacement (a {@code BlockFallback} or the default one) was used. False
 *                 for a block asked for by runtime id
 * @param behaviour the behaviour values (friction, destroy time, collision shape, ...) of {@link #definition()} in the palette that resolved it
 * @since 0.1.0
 */
public record ResolvedBlock(@NonNull RegisteredBlockState definition, boolean fallback, @NonNull BlockValues behaviour) {
}
