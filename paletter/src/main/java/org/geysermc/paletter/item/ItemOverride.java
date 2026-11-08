package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.internal.component.ItemCompiler;
import org.geysermc.paletter.internal.component.WireContext;
import org.geysermc.paletter.item.component.ItemComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Changes to a vanilla (or earlier layer's) item definition, applied in a layer. Received in
 * {@link org.geysermc.paletter.PaletteLayer.Builder#overrideItem}; anything not set keeps its current value.
 * <pre>{@code
 * // Bedrock stacks cakes to 64, Java to 1
 * layer.overrideItem("minecraft:cake", item -> item.components(MaxStackSize.of(1)));
 * // Prevent client side firework boosting by making the item data driven
 * layer.overrideItem("minecraft:firework_rocket", item -> item.dataDriven(MaxStackSize.of(64), Throwable.builder().build()));
 * }</pre>
 *
 * @since 1.0.0
 */
public final class ItemOverride {
    private final PaletteItem original;
    private @Nullable ItemVersion version;
    private @Nullable Boolean componentBased;
    private final List<ItemComponent> components = new ArrayList<>();
    private boolean fresh;
    private boolean blockSet;
    private @Nullable NbtMap block;

    ItemOverride(PaletteItem original) {
        this.original = original;
    }

    /**
     * @return the definition before this override
     */
    public @NonNull PaletteItem original() {
        return original;
    }

    public @NonNull ItemOverride version(@NonNull ItemVersion version) {
        this.version = version;
        return this;
    }

    public @NonNull ItemOverride componentBased(boolean componentBased) {
        this.componentBased = componentBased;
        return this;
    }

    /**
     * Adds components on top of the item's current component data: what they write replaces the original keys
     * (including single {@code item_properties} entries), everything else is sent as before. Marks the item component
     * based.
     */
    public @NonNull ItemOverride components(@NonNull ItemComponent... components) {
        for (ItemComponent component : components) {
            this.components.removeIf(existing -> existing.name().equals(component.name()));
            this.components.add(component);
        }
        this.componentBased = true;
        return this;
    }

    /**
     * Turns the item into a data driven item with only these components (the custom item defaults underneath). The
     * client then stops applying its hardcoded behaviour, e.g. firework boosting.
     */
    public @NonNull ItemOverride dataDriven(@NonNull ItemComponent... components) {
        version(ItemVersion.DATA_DRIVEN);
        this.components.clear();
        this.fresh = true;
        return components(components);
    }

    /**
     * Sets the block this item is sent with. By default, the block is derived from the creative inventory and the palette.
     *
     * @param state the block state, or null to send the item without a block
     */
    public @NonNull ItemOverride block(@Nullable NbtMap state) {
        this.blockSet = true;
        this.block = state == null ? null : BlockStates.normalize(state);
        return this;
    }

    /**
     * Sends the item without a block, e.g. structure blocks, which otherwise break recipes.
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

    PaletteItem apply(WireContext context) {
        NbtMap componentData = original.getComponentData();
        if (fresh || (componentData == null && !components.isEmpty())) {
            componentData = ItemCompiler.componentData(original.getIdentifier(), original.getRuntimeId(), components, null, null, context);
        } else if (!components.isEmpty()) {
            componentData = ItemCompiler.override(componentData, components, context);
        }
        return new PaletteItem(original.getIdentifier(), original.getRuntimeId(),
            version != null ? version : original.getVersion(),
            componentBased != null ? componentBased : original.isComponentBased(),
            componentData, original.isCustom());
    }
}
