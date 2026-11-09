package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A named group of the bundled creative data.
 *
 * @param name the group name
 * @param icon the icon stack, or null for the first stack
 * @param stacks the stacks in the group, in order
 */
public record RawGroup(@NonNull String name, @Nullable RawStack icon, @NonNull List<RawStack> stacks) implements RawNode {

    public RawGroup {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Group names cannot be empty; use loose stacks instead");
        }
        stacks = List.copyOf(stacks);
    }
}
