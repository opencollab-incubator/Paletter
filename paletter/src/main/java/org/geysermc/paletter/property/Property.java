package org.geysermc.paletter.property;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One named property of an item or block.
 *
 * @param kind the kind of property
 * @param name the name within the kind, e.g. {@code minecraft:max_stack_size} for a {@link PropertyKind#COMPONENT}
 * @param value the value as an NBT tag value: an {@code NbtMap}, {@code NbtList}, string or number
 * @since 1.0.0
 */
public record Property(@NonNull PropertyKind kind, @NonNull String name, @NonNull Object value) {

    /**
     * @return the kind's prefix followed by the name, unique within one item or block
     */
    public @NonNull String fullName() {
        return kind.prefix() + name;
    }
}
