package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

/**
 * A vanilla item entry, as sent in the item registry by BDS.
 *
 * @param identifier the item identifier
 * @param runtimeId the network runtime id
 * @param version the item version as sent by BDS, see {@link org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion#from(int)}
 * @param componentBased whether the item is component based
 * @param componentData the vanilla item components, if any. Some non component based items (e.g. food) still have them
 * @since 1.0.0
 */
public record VanillaItem(@NonNull String identifier, int runtimeId, int version, boolean componentBased, @Nullable NbtMap componentData) {
}
