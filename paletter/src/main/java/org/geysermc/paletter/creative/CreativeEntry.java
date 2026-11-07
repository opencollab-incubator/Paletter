package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

/**
 * One compiled creative inventory entry.
 *
 * @param netId the creative network id the client uses to request it
 * @param category the category it is in
 * @param group the group it is in, or null if loose
 * @param stack the stack as defined in the layout
 * @param item the stack as sent to the client
 * @since 2.0.0
 */
public record CreativeEntry(int netId, @NonNull CreativeCategory category, @Nullable String group,
                            @NonNull CreativeStack stack, @NonNull ItemData item) {
}
