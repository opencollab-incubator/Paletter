package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.BlockComponent;
import dev.opencollab.paletter.block.component.BlockTrait;

/**
 * Compiles a custom block to the block property data of one protocol, in the shape BDS sends:
 * {@code {components, menu_category, molangVersion, vanilla_block_data, properties, traits, permutations, blockTags}}:
 * what BDS sends for every custom block ({@link BlockWire#envelope}), the description's menu category
 * ({@link BlockWire#menuCategory}), the components through their generated encoders, the states and permutations.
 */
public final class BlockCompiler {
    private BlockCompiler() {
    }

    /**
     * @param blockId the numeric block id, unique among the custom blocks of a palette
     */
    public static @NonNull NbtMap propertyData(@NonNull CustomBlock block, int blockId, @NonNull WireContext context) {
        WireContext ctx = context.subject(block.identifier());
        WireNode out = new WireNode();
        WireNode components = new WireNode();
        // minecraft:tags is no component on the wire: BDS 1.26.36.1 to 1.26.70.24 send its tags as the block's top level
        // blockTags, as written (duplicates kept), and nothing for an empty list
        java.util.List<String> tags = new java.util.ArrayList<>();
        for (BlockComponent component : WireOnlyWire.rawLast(block.components())) {
            if (component instanceof dev.opencollab.paletter.block.component.BlockTags blockTags) {
                tags.addAll(blockTags.tags());
                continue;
            }
            BlockWire.write(component, ctx, components);
        }
        if (!components.isEmpty()) {
            out.put("components", components.build());
        }
        BlockWire.envelope(ctx, out);
        BlockWire.menuCategory(block.menuCategory(), ctx, out);
        out.put("vanilla_block_data.block_id", blockId);
        if (!block.properties().isEmpty()) {
            out.list("properties");
            for (BlockProperty<?> property : block.properties()) {
                out.element("properties").put("name", property.name()).put("enum", values(property));
            }
        }
        // Without the enabled_states this version's client adds no well-defined states for (reported): the palette's states of
        // the block leave them out too (TraitStates)
        java.util.List<BlockTrait> traits = new java.util.ArrayList<>();
        for (BlockTrait trait : block.traits()) {
            BlockTrait sent = TraitStates.supported(trait, ctx);
            if (sent != null) {
                traits.add(sent);
            }
        }
        if (!traits.isEmpty()) {
            out.list("traits");
            for (BlockTrait trait : traits) {
                TraitWire.write(trait, ctx, out.element("traits"));
            }
        }
        for (CustomBlock.Permutation permutation : block.permutations()) {
            Transforms.permutationComponents(ctx, out, permutation);
        }
        if (!tags.isEmpty()) {
            out.put("blockTags", Wire.strings(tags));
        }
        return out.build();
    }

    private static Object values(BlockProperty<?> property) {
        return switch (property.type()) {
            // Booleans are a byte list on the wire
            case BOOLEAN -> new org.cloudburstmc.nbt.NbtList<>(NbtType.BYTE, property.values().stream().map(v -> (Byte) dev.opencollab.paletter.internal.block.BlockStates.nbtValue(v)).toList());
            case INTEGER -> new org.cloudburstmc.nbt.NbtList<>(NbtType.INT, property.values().stream().map(Integer.class::cast).toList());
            case STRING -> new org.cloudburstmc.nbt.NbtList<>(NbtType.STRING, property.values().stream().map(String.class::cast).toList());
        };
    }
}
