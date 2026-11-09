package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.internal.property.PropertySplit;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.List;

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
    private final Origin origin;
    private final List<ItemComponent> components;
    private final @Nullable CustomItem customItem;
    private volatile @Nullable BedrockItemType key;
    private volatile @Nullable Properties properties;

    RegisteredItem(String identifier, int runtimeId, ItemVersion version, boolean componentBased, @Nullable NbtMap componentData,
                   Origin origin, List<ItemComponent> components, @Nullable CustomItem customItem) {
        this.identifier = identifier.intern();
        this.runtimeId = runtimeId;
        this.version = version;
        this.componentBased = componentBased;
        this.componentData = componentData;
        this.origin = origin;
        this.components = List.copyOf(components);
        this.customItem = customItem;
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
     * @return where this item comes from: vanilla, or the id of the layer that added it (an override keeps the origin
     * of the item it changes)
     */
    public @NonNull Origin origin() {
        return origin;
    }

    /**
     * @return the key of this item: the vanilla constant, or the custom type of its identifier
     */
    public @NonNull BedrockItemType key() {
        BedrockItemType result = key;
        if (result == null) {
            result = BedrockItemType.of(identifier);
            key = result;
        }
        return result;
    }

    /**
     * @return the components this item was given by a layer: those of a custom item, or the ones overrides set on top.
     * Empty for vanilla items that nothing overrides
     */
    public @NonNull List<ItemComponent> components() {
        return components;
    }

    /**
     * @return the properties of this item: its component data split the way the bundled property store names them
     * ({@code component:}, {@code wire:}). For a vanilla item that is what the store holds for the palette's version;
     * for a custom item or an overridden one it reflects the components the layers set
     */
    public @NonNull Properties properties() {
        Properties result = properties;
        if (result == null) {
            properties = result = PropertySplit.split(componentData);
        }
        return result;
    }

    /**
     * @return the custom item this was registered from, or null for vanilla items and items of custom blocks
     */
    public @Nullable CustomItem customItem() {
        return customItem;
    }

    /**
     * @return true if this item was added by a layer rather than being vanilla
     */
    public boolean isCustom() {
        return origin.isCustom();
    }

    @Override
    public String toString() {
        return "RegisteredItem{" + identifier + " " + runtimeId + (origin.isCustom() ? " custom" : "") + "}";
    }
}
