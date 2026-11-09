package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;

/**
 * An item definition in a compiled palette.
 * <p>
 * Instances are compared by identity, and are shared between a palette and every palette extending it, unless a layer
 * overrides that item. So {@code ==} between a definition from a shared palette and one from a session's palette is safe.
 *
 * @since 1.0.0
 */
public final class RegisteredItem implements ItemDefinition {
    private final String identifier;
    private final int runtimeId;
    private final ItemVersion version;
    private final boolean componentBased;
    private final @Nullable NbtMap componentData;
    private final boolean custom;

    RegisteredItem(String identifier, int runtimeId, ItemVersion version, boolean componentBased, @Nullable NbtMap componentData, boolean custom) {
        this.identifier = identifier.intern();
        this.runtimeId = runtimeId;
        this.version = version;
        this.componentBased = componentBased;
        this.componentData = componentData;
        this.custom = custom;
    }

    @Override
    public @NonNull String getIdentifier() {
        return identifier;
    }

    @Override
    public int getRuntimeId() {
        return runtimeId;
    }

    @Override
    public @NonNull ItemVersion getVersion() {
        return version;
    }

    @Override
    public boolean isComponentBased() {
        return componentBased;
    }

    @Override
    public @Nullable NbtMap getComponentData() {
        return componentData;
    }

    /**
     * @return true if this item was added by a layer rather than being vanilla
     */
    public boolean isCustom() {
        return custom;
    }

    @Override
    public String toString() {
        return "RegisteredItem{" + identifier + " " + runtimeId + (custom ? " custom" : "") + "}";
    }
}
