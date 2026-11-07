package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A named, collapsible group in a creative category. Immutable; edit through {@link CreativeEditor}.
 *
 * @param name the group name, usually a translation key like {@code itemGroup.name.minecart}. Unique across the layout
 * @param icon the stack shown on the collapsed group, or null to use the first stack that exists in the client's version
 * @param stacks the stacks in the group, in order
 */
public record CreativeGroup(@NonNull String name, @Nullable CreativeStack icon, @NonNull List<CreativeStack> stacks) implements CreativeNode {

    public CreativeGroup {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Group names cannot be empty; use loose stacks instead");
        }
        stacks = List.copyOf(stacks);
    }
}
