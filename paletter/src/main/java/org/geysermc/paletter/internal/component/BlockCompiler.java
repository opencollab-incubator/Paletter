package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.BlockTrait;
import org.geysermc.paletter.block.component.MenuCategory;

/**
 * Compiles a custom block to the block property data of one protocol, in the shape BDS 1.26.63.1 sends:
 * {@code {components, menu_category, molangVersion, vanilla_block_data, properties, traits, permutations, blockTags}}.
 */
public final class BlockCompiler {
    /** The Molang version BDS 1.26.63 sends, as do the vanilla data driven blocks of 26.50 and 26.60. */
    static final int MOLANG_VERSION = 13;
    /** The {@code vanilla_block_data.material} BDS sends for every custom block. */
    static final String CUSTOM_BLOCK_MATERIAL = "dirt";

    private BlockCompiler() {
    }

    /**
     * @param blockId the numeric block id, unique among the custom blocks of a palette
     */
    public static @NonNull NbtMap propertyData(@NonNull CustomBlock block, int blockId, @NonNull WireContext context) {
        WireContext ctx = context.subject(block.identifier());
        WireNode out = new WireNode();
        WireNode components = new WireNode();
        for (BlockComponent component : block.components()) {
            BlockWire.write(component, ctx, components);
        }
        if (!components.isEmpty()) {
            out.put("components", components.build());
        }
        MenuCategory menu = block.menuCategory();
        out.put("menu_category", NbtMap.builder()
            .putString("category", menu == null ? MenuCategory.Category.NONE.value() : menu.category().value())
            .putString("group", menu == null || menu.group() == null ? "" : menu.group())
            .putByte("is_hidden_in_commands", Wire.bool(menu != null && menu.hiddenInCommands()))
            .build());
        out.put("molangVersion", MOLANG_VERSION);
        out.put("vanilla_block_data", NbtMap.builder().putInt("block_id", blockId).putString("material", CUSTOM_BLOCK_MATERIAL).build());
        if (!block.properties().isEmpty()) {
            out.list("properties");
            for (BlockProperty<?> property : block.properties()) {
                out.element("properties").put("name", property.name()).put("enum", values(property));
            }
        }
        if (!block.traits().isEmpty()) {
            out.list("traits");
            for (BlockTrait trait : block.traits()) {
                TraitWire.write(trait, ctx, out.element("traits"));
            }
        }
        for (CustomBlock.Permutation permutation : block.permutations()) {
            Transforms.permutationComponents(ctx, out, permutation);
        }
        if (!block.tags().isEmpty()) {
            out.put("blockTags", Wire.strings(block.tags()));
        }
        return out.build();
    }

    private static Object values(BlockProperty<?> property) {
        return switch (property.type()) {
            // Booleans are a byte list on the wire
            case BOOLEAN -> new org.cloudburstmc.nbt.NbtList<>(NbtType.BYTE, property.values().stream().map(Byte.class::cast).toList());
            case INTEGER -> new org.cloudburstmc.nbt.NbtList<>(NbtType.INT, property.values().stream().map(Integer.class::cast).toList());
            case STRING -> new org.cloudburstmc.nbt.NbtList<>(NbtType.STRING, property.values().stream().map(String.class::cast).toList());
        };
    }
}
