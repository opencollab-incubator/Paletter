package org.geysermc.paletter.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

/**
 * A typed Bedrock data driven component, encoded exactly as the client receives it.
 * <p>
 * The types in {@code org.geysermc.paletter.component.item} and {@code ...component.block} are generated from NBT
 * captured off the wire (see {@code codegen/README.md}); {@link ComponentType#confidence()} says how well a type is backed.
 */
public interface BedrockComponent {
    /**
     * @return the type of this component, carrying its network name
     */
    @NonNull ComponentType<?> componentType();

    /**
     * @return the component value in its network NBT form
     */
    @NonNull NbtMap toNbt();
}
