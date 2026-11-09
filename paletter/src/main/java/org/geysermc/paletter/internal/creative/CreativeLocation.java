package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.creative.CreativeCategory;

/**
 * Where a stack is in a creative layout.
 *
 * @param category the category
 * @param group the group name, or null if the stack is loose
 * @param index the index inside the group, or the node index inside the category if loose
 * @since 1.0.0
 */
public record CreativeLocation(@NonNull CreativeCategory category, @Nullable String group, int index) {

    public boolean isLoose() {
        return group == null;
    }
}
