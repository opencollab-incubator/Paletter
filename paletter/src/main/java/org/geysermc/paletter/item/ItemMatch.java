package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.attach.AttachmentKey;
import org.geysermc.paletter.attach.Attachments;

/**
 * The result of matching an item stack sent by the client.
 *
 * @param definition the item definition
 * @param variant the variant as sent: identifier, damage and block state (if a block item)
 * @param attached the variant the attachments were found on; equal to {@code variant} or less specific, e.g. without damage
 * @param attachments the attachments of {@code attached}
 */
public record ItemMatch(@NonNull PaletteItem definition, @NonNull ItemVariant variant, @NonNull ItemVariant attached,
                        @NonNull Attachments attachments) {

    public <T> @Nullable T get(@NonNull AttachmentKey<T> key) {
        return attachments.get(key);
    }
}
