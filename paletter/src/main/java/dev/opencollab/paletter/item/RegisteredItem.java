package dev.opencollab.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import dev.opencollab.paletter.Origin;
import dev.opencollab.paletter.internal.property.PropertySplit;
import dev.opencollab.paletter.item.component.ItemComponent;
import dev.opencollab.paletter.property.Properties;
import dev.opencollab.paletter.vanilla.BedrockItemType;

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

    /**
     * @param key the type a custom item or block owns, or null for the type of the identifier
     */
    RegisteredItem(String identifier, int runtimeId, ItemVersion version, boolean componentBased, @Nullable NbtMap componentData,
                   Origin origin, List<ItemComponent> components, @Nullable CustomItem customItem, @Nullable BedrockItemType key) {
        this.identifier = identifier.intern();
        this.runtimeId = runtimeId;
        this.version = version;
        this.componentBased = componentBased;
        this.componentData = componentData;
        this.origin = origin;
        this.components = List.copyOf(components);
        this.customItem = customItem;
        this.key = key;
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
     * Tells where the item comes from.
     *
     * @return where this item comes from: vanilla, or the id of the layer that added it (an override keeps the origin
     * of the item it changes)
     */
    public @NonNull Origin origin() {
        return origin;
    }

    /**
     * Returns the type of the item: the key of its base variant. A definition is the item, whatever variant a stack of
     * it has, so it has no variant of its own; {@link dev.opencollab.paletter.Palette#resolve} gives the variant sent.
     *
     * @return the type of this item: the vanilla constant, or for a custom item or block item the type the
     *         {@link CustomItem} or {@link dev.opencollab.paletter.block.CustomBlock} owns ({@link CustomItem#type()},
     *         {@link dev.opencollab.paletter.block.CustomBlock#itemType()})
     */
    public @NonNull BedrockItemType type() {
        BedrockItemType result = key;
        if (result == null) {
            result = BedrockItemType.fromIdentifier(identifier);
            key = result;
        }
        return result;
    }

    /**
     * Returns the components layers gave the item.
     *
     * @return the components this item was given by a layer: those of a custom item, or the ones overrides set on top.
     * Empty for vanilla items that nothing overrides
     */
    public @NonNull List<ItemComponent> components() {
        return components;
    }

    /**
     * Returns the properties of the item.
     *
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
     * Returns the custom item the definition was registered from.
     *
     * @return the custom item this was registered from, or null for vanilla items and items of custom blocks
     */
    public @Nullable CustomItem customItem() {
        return customItem;
    }

    /**
     * Tells whether a layer added the item.
     *
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
