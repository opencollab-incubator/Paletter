package dev.opencollab.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import dev.opencollab.paletter.annotation.Experimental;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.component.ItemCompiler;
import dev.opencollab.paletter.internal.component.WireContext;
import dev.opencollab.paletter.item.component.ItemComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Changes to a vanilla (or earlier layer's) item definition, applied in a layer. Received in
 * {@link dev.opencollab.paletter.PaletteLayer.Builder#overrideItem}; anything not set keeps its current value.
 * <pre>{@code
 * // Bedrock stacks cakes to 64, Java to 1
 * layer.overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(1)));
 * // Like a BDS add-on for a data driven item: only these components are left (the apple keeps its creative category)
 * layer.overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(3)));
 * // Make the firework rocket data driven and merge onto its current data; whether that stops the client's
 * // own firework boosting is unverified on a client
 * layer.overrideItem("minecraft:firework_rocket", item -> item.version(ItemVersion.DATA_DRIVEN).modifyComponents(MaxStackSize.of(64)));
 * }</pre>
 * <p>
 * <b>Experimental.</b> BDS overrides of a vanilla data driven item (such as {@code minecraft:apple}, format 1.26.60)
 * replace the vanilla components ({@link #replaceComponents}). For hard coded items BDS accepted
 * {@code minecraft:feather} at format 1.10 (it became a legacy item, id kept) and rejected {@code minecraft:stick} at
 * 1.26.60; a legacy {@code minecraft:bread} at 1.16.100 was rejected, asking for 1.20.0 or higher, which was never
 * tried. Paletter does not refuse any override. How the Bedrock client treats Paletter's overrides is <b>unverified on a
 * client</b>: no client test exists. Paletter also keeps every vanilla runtime id fixed, where BDS moves an overridden
 * data driven item. This API may change.
 *
 * @since 1.0.0
 */
@Experimental
public final class ItemOverride {
    private final RegisteredItem original;
    private @Nullable ItemVersion version;
    private @Nullable Boolean componentBased;
    private final List<ItemComponent> components = new ArrayList<>();
    private boolean fresh;
    private boolean blockSet;
    private @Nullable NbtMap block;

    ItemOverride(RegisteredItem original) {
        this.original = original;
    }

    /**
     * Returns the item as it was before this override.
     *
     * @return the definition before this override
     */
    public @NonNull RegisteredItem original() {
        return original;
    }

    /**
     * Sets the item version of the definition, e.g. {@link ItemVersion#DATA_DRIVEN}.
     *
     * @param version the version to send
     * @return this override
     */
    public @NonNull ItemOverride version(@NonNull ItemVersion version) {
        this.version = version;
        return this;
    }

    /**
     * Sets whether the item is sent as component based. {@link #modifyComponents} and {@link #replaceComponents}
     * set it to true.
     *
     * @param componentBased true to send the item as component based
     * @return this override
     */
    public @NonNull ItemOverride componentBased(boolean componentBased) {
        this.componentBased = componentBased;
        return this;
    }

    /**
     * Merges components onto the item's current component data: what they write replaces the original keys (including
     * single {@code item_properties} entries), everything else is sent as before. Marks the item component based.
     * <p>
     * BDS never produces this shape: an add-on override replaces the vanilla components, see {@link #replaceComponents}.
     * The client's behaviour for a merged vanilla item is unverified.
     *
     * @param components the components to merge; a later component replaces an earlier one of the same name
     * @return this override
     */
    public @NonNull ItemOverride modifyComponents(@NonNull ItemComponent... components) {
        for (ItemComponent component : components) {
            this.components.removeIf(existing -> existing.name().equals(component.name()));
            this.components.add(component);
        }
        this.componentBased = true;
        return this;
    }

    /**
     * Replaces the item's components with only these (the custom item defaults underneath), like a BDS add-on that
     * overrides a vanilla data driven item. The vanilla {@code item_properties.creative_category} is kept, as BDS does.
     * Also forces the item data driven and component based, and discards the components of earlier
     * {@link #modifyComponents} calls. Whether the client then stops applying hard coded behaviour is unverified on a
     * client.
     * <p>
     * BDS accepted this for the data driven apple; for a hard coded item it rejected the stick (1.26.60) and accepted
     * the feather only as a legacy item (1.10). Paletter applies it to any item (unverified on a client). Unlike BDS,
     * the runtime id stays the vanilla one.
     *
     * @param components the only components the item has, apart from the custom item defaults
     * @return this override
     */
    public @NonNull ItemOverride replaceComponents(@NonNull ItemComponent... components) {
        version(ItemVersion.DATA_DRIVEN);
        this.components.clear();
        this.fresh = true;
        return modifyComponents(components);
    }

    /**
     * Sets the block this item is sent with. By default, the block is derived from the creative inventory and the palette.
     *
     * @param state the block state, or null to send the item without a block
     * @return this override
     */
    public @NonNull ItemOverride block(@Nullable NbtMap state) {
        this.blockSet = true;
        this.block = state == null ? null : BlockStates.normalize(state);
        return this;
    }

    /**
     * Sends the item without a block, e.g. structure blocks, which otherwise break recipes.
     *
     * @return this override
     */
    public @NonNull ItemOverride noBlock() {
        return block(null);
    }

    boolean changesDefinition() {
        return version != null || componentBased != null || !components.isEmpty() || fresh;
    }

    boolean setsBlock() {
        return blockSet;
    }

    @Nullable NbtMap block() {
        return block;
    }

    /** Like BDS, a replacement keeps the vanilla creative category; a hard coded or legacy original has none to keep. */
    private NbtMap keepCreativeCategory(NbtMap componentData) {
        NbtMap originalComponents = original.getComponentData() == null ? null : original.getComponentData().getCompound("components");
        NbtMap properties = originalComponents == null ? null : originalComponents.getCompound("item_properties");
        if (properties == null || !properties.containsKey("creative_category")) {
            // TODO(client test): a hard coded or legacy item replaced like this deviates from BDS (it rejected the stick
            //  at 1.26.60 and made the feather legacy); the client's result is unverified
            return componentData;
        }
        NbtMap components = componentData.getCompound("components");
        NbtMap kept = components.getCompound("item_properties").toBuilder()
            .putInt("creative_category", properties.getInt("creative_category")).build();
        return componentData.toBuilder().putCompound("components", components.toBuilder().putCompound("item_properties", kept).build()).build();
    }

    RegisteredItem apply(WireContext context) {
        NbtMap componentData = original.getComponentData();
        if (fresh || (componentData == null && !components.isEmpty())) {
            componentData = ItemCompiler.componentData(original.getIdentifier(), original.getRuntimeId(), components, null, context);
            if (fresh) {
                componentData = keepCreativeCategory(componentData);
            }
        } else if (!components.isEmpty()) {
            if (!componentData.containsKey("components")) {
                // A legacy item has no component data (an empty compound): start from the envelope, so name and id are sent
                componentData = NbtMap.builder().putString("name", original.getIdentifier()).putInt("id", original.getRuntimeId())
                    .putCompound("components", NbtMap.EMPTY).build();
            }
            componentData = ItemCompiler.override(componentData, components, context);
        }
        List<ItemComponent> merged = new ArrayList<>(fresh ? List.of() : original.components());
        for (ItemComponent component : components) {
            merged.removeIf(existing -> existing.name().equals(component.name()));
            merged.add(component);
        }
        return new RegisteredItem(original.getIdentifier(), original.getRuntimeId(),
            version != null ? version : original.getVersion(),
            componentBased != null ? componentBased : original.isComponentBased(),
            componentData, original.origin(), merged, original.customItem(), original.isCustom() ? original.key() : null);
    }
}
