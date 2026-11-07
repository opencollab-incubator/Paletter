package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.internal.encode.EncodeContext;
import org.geysermc.paletter.internal.encode.ItemEncoder;

/**
 * Changes to a vanilla (or earlier layer's) item definition, applied in a layer. Received in
 * {@link org.geysermc.paletter.PaletteLayer.Builder#overrideItem}; anything not set keeps its current value.
 * <pre>{@code
 * // Bedrock stacks cakes to 64, Java to 1
 * layer.overrideItem("minecraft:cake", item -> item.components(c -> c.maxStackSize(1)));
 * // Prevent client side firework boosting by making the item data driven
 * layer.overrideItem("minecraft:firework_rocket", item -> item.dataDriven(c -> c.maxStackSize(64).throwable(Throwing.of(true))));
 * }</pre>
 *
 * @since 2.0.0
 */
public final class ItemOverride {
    private final PaletteItem original;
    private @Nullable ItemVersion version;
    private @Nullable Boolean componentBased;
    private @Nullable ItemComponents components;
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

    /**
     * @return the current components: those set in this override so far, else the original's. The original's network
     * data is kept as is (as {@link org.geysermc.paletter.annotation.Experimental raw} components), so semantic values set
     * on top only replace what they encode to
     */
    public @NonNull ItemComponents currentComponents() {
        if (components != null) {
            return components;
        }
        NbtMap data = original.getComponentData();
        return data == null ? ItemComponents.EMPTY : ItemEncoder.decode(data);
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
     * Replaces the components. Marks the item component based.
     */
    public @NonNull ItemOverride components(@NonNull ItemComponents components) {
        this.components = components;
        this.componentBased = true;
        return this;
    }

    /**
     * Modifies the current components. Marks the item component based.
     */
    public @NonNull ItemOverride components(java.util.function.@NonNull UnaryOperator<ItemComponents.Builder> modifier) {
        return components(modifier.apply(currentComponents().toBuilder()).build());
    }

    /**
     * Turns the item into a data driven item with fresh components. The client then stops applying its hardcoded
     * behaviour, e.g. firework boosting.
     */
    public @NonNull ItemOverride dataDriven(java.util.function.@NonNull UnaryOperator<ItemComponents.Builder> components) {
        version(ItemVersion.DATA_DRIVEN);
        return components(components.apply(ItemComponents.builder()).build());
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
        return version != null || componentBased != null || components != null;
    }

    boolean setsBlock() {
        return blockSet;
    }

    @Nullable NbtMap block() {
        return block;
    }

    PaletteItem apply(EncodeContext context) {
        NbtMap componentData = components == null ? original.getComponentData()
            : ItemEncoder.componentData(components, original.getIdentifier(), original.getRuntimeId(), null, context);
        return new PaletteItem(original.getIdentifier(), original.getRuntimeId(),
            version != null ? version : original.getVersion(),
            componentBased != null ? componentBased : original.isComponentBased(),
            componentData, original.isCustom());
    }
}
