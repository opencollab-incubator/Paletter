package org.geysermc.paletter.internal.encode;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockComponents;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Box;
import org.geysermc.paletter.block.component.Face;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.Material;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.internal.wire.block.BlockDefinition;
import org.geysermc.paletter.internal.wire.block.CollisionBox;
import org.geysermc.paletter.internal.wire.block.DestructibleByMining;
import org.geysermc.paletter.internal.wire.block.DisplayName;
import org.geysermc.paletter.internal.wire.block.Friction;
import org.geysermc.paletter.internal.wire.block.LightDampening;
import org.geysermc.paletter.internal.wire.block.LightEmission;
import org.geysermc.paletter.internal.wire.block.MaterialInstances;
import org.geysermc.paletter.internal.wire.block.OnPlayerPlacing;
import org.geysermc.paletter.internal.wire.block.SelectionBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Encodes custom blocks and semantic {@link BlockComponents} into the block property data of one Bedrock version. The
 * only place that knows the block wire format; see {@code codegen/DEVIATIONS.md} for where it differs from what Geyser
 * used to send.
 */
public final class BlockEncoder {
    /**
     * Bit of {@code packed_bools} for face dimming (captured from BDS 1.26.63.1).
     */
    static final int FACE_DIMMING_BIT = 1;
    /**
     * Bit of {@code packed_bools} for isotropic (captured from BDS 1.26.63.1).
     */
    static final int ISOTROPIC_BIT = 1 << 1;
    /**
     * Bit of {@code packed_bools} that BDS sets in every material it sends (vanilla and custom); meaning unknown.
     */
    static final int ALWAYS_SET_BIT = 1 << 2;
    /**
     * The {@code vanilla_block_data.material} BDS sends for every custom block.
     */
    static final String CUSTOM_BLOCK_MATERIAL = "dirt";

    private BlockEncoder() {
    }

    /**
     * The Molang version of block property data and of placement filter tag queries ({@code tags_version}).
     * BDS 1.26.63 sends 13, as do vanilla data driven blocks of 26.50 and 26.60; Geyser sent 1 (and {@code tags_version} 6).
     */
    static int molangVersion(EncodeContext context) {
        return 13;
    }

    /**
     * @param blockId the numeric block id, unique among the custom blocks of a palette
     * @return the block property data, as sent in the start game packet
     */
    public static @NonNull NbtMap propertyData(@NonNull CustomBlock block, int blockId, @NonNull EncodeContext context) {
        String id = block.identifier();
        List<NbtMap> permutations = new ArrayList<>(block.permutations().size());
        for (CustomBlock.Permutation permutation : block.permutations()) {
            if (!permutation.components().tags().isEmpty()) {
                context.drop(id, "block tags " + permutation.components().tags() + " in the permutation '" + permutation.condition()
                    + "' cannot be sent (tags belong to the block, not a permutation)");
            }
            permutations.add(NbtMap.builder()
                .putCompound("components", components(permutation.components(), id, context))
                .putString("condition", permutation.condition())
                .build());
        }
        List<NbtMap> properties = new ArrayList<>(block.properties().size());
        for (BlockProperty<?> property : block.properties()) {
            properties.add(property(property));
        }

        // Tags are the top level blockTags, not tag:<name> components (1.26 JSON rejects those)
        Set<String> tags = new LinkedHashSet<>(block.tags());
        tags.addAll(block.components().tags());

        // The client crashes without a menu category
        CustomBlock.Menu menu = block.menu();
        NbtMapBuilder data = NbtMap.builder();
        if (context.supports(id, BlockDefinition.TYPE)) {
            BlockDefinition definition = new BlockDefinition(
                tags.isEmpty() ? null : List.copyOf(tags),
                new BlockDefinition.MenuCategory(menu == null ? CreativeCategory.NONE_NAME : menu.category().bedrockName(),
                    menu == null ? "" : menu.group(), menu != null && menu.hiddenInCommands()),
                molangVersion(context),
                new NbtList<>(NbtType.COMPOUND, properties),
                null,
                BlockDefinition.VanillaBlockData.builder().blockId(blockId).material(CUSTOM_BLOCK_MATERIAL).build());
            data.putAll(definition.toNbt());
        }
        data.putCompound("components", components(block.components(), id, context));
        data.putList("permutations", NbtType.COMPOUND, permutations);
        return data.build();
    }

    private static NbtMap property(BlockProperty<?> property) {
        NbtMapBuilder builder = NbtMap.builder().putString("name", property.name());
        switch (property.type()) {
            // Booleans are a byte list on the wire
            case BOOLEAN -> builder.putList("enum", NbtType.BYTE, property.values().stream().map(Byte.class::cast).toList());
            case INTEGER -> builder.putList("enum", NbtType.INT, property.values().stream().map(Integer.class::cast).toList());
            case STRING -> builder.putList("enum", NbtType.STRING, property.values().stream().map(String.class::cast).toList());
        }
        return builder.build();
    }

    /**
     * @param subject the block, for the report
     * @return the {@code components} compound of block property data or of a permutation
     */
    public static @NonNull NbtMap components(@NonNull BlockComponents c, @NonNull String subject, @NonNull EncodeContext context) {
        NbtMapBuilder out = NbtMap.builder();
        out.putAll(c.raw());
        if (c.displayName() != null) {
            context.put(out, subject, DisplayName.TYPE, () -> DisplayName.of(c.displayName()));
        }
        if (c.selectionBox() != null) {
            // Origin and size as in JSON. Like BDS for JSON false, an empty box disables selection and sends the full cube
            Box box = c.selectionBox().isEmpty() ? Box.FULL : c.selectionBox();
            boolean enabled = !c.selectionBox().isEmpty();
            context.put(out, subject, SelectionBox.TYPE, () -> SelectionBox.builder()
                .enabled(enabled)
                .origin(List.of(box.originX(), box.originY(), box.originZ()))
                .size(List.of(box.sizeX(), box.sizeY(), box.sizeZ()))
                .build());
        }
        if (c.collisionBoxes() != null) {
            List<CollisionBox.Box> boxes = new ArrayList<>();
            for (Box box : c.collisionBoxes()) {
                // Min/max in 0..16 block space; JSON origins are relative to the bottom center
                float minX = 8f + box.originX();
                float minY = box.originY();
                float minZ = 8f + box.originZ();
                boxes.add(CollisionBox.Box.builder()
                    .minX(minX).minY(minY).minZ(minZ)
                    .maxX(minX + box.sizeX()).maxY(minY + box.sizeY()).maxZ(minZ + box.sizeZ())
                    .build());
            }
            context.put(out, subject, CollisionBox.TYPE, () -> CollisionBox.builder().enabled(!boxes.isEmpty()).boxes(boxes).build());
        }
        if (c.geometry() != null) {
            Geometry geometry = c.geometry();
            context.put(out, subject, org.geysermc.paletter.internal.wire.block.Geometry.TYPE,
                () -> org.geysermc.paletter.internal.wire.block.Geometry.builder()
                    .identifier(geometry.identifier())
                    .boneVisibility(geometry.boneVisibility().isEmpty() ? null : geometry.boneVisibility())
                    .culling(geometry.culling())
                    .cullingLayer(geometry.cullingLayer())
                    .uvLock(geometry.uvLock() ? (Object) (byte) 1 : null)
                    .build());
        }
        if (c.materials() != null) {
            materials(out, subject, c.materials(), c.materialAliases(), context);
        }
        if (c.placementFilter() != null) {
            placementFilter(out, subject, c.placementFilter(), context);
        }
        if (c.destructibleByMining() != null) {
            context.put(out, subject, DestructibleByMining.TYPE, () -> DestructibleByMining.builder().value(c.destructibleByMining()).build());
        }
        if (c.friction() != null) {
            context.put(out, subject, Friction.TYPE, () -> Friction.of(c.friction()));
        }
        if (c.lightEmission() != null) {
            context.put(out, subject, LightEmission.TYPE, () -> LightEmission.of(c.lightEmission().byteValue()));
        }
        if (c.lightDampening() != null) {
            context.put(out, subject, LightDampening.TYPE, () -> LightDampening.of(c.lightDampening().byteValue()));
        }
        if (c.transformation() != null) {
            transformation(out, subject, c.transformation(), context);
        }
        if (c.onPlayerPlacing() != null) {
            context.put(out, subject, OnPlayerPlacing.TYPE, () -> OnPlayerPlacing.of(c.onPlayerPlacing()));
        }
        return out.build();
    }

    private static void materials(NbtMapBuilder out, String subject, Map<String, Material> materials, Map<String, String> aliases,
                                  EncodeContext context) {
        Map<String, MaterialInstances.Material> converted = new LinkedHashMap<>();
        materials.forEach((name, material) -> converted.put(name, MaterialInstances.Material.builder()
            // A float: Bedrock stopped accepting a byte here in 1.26.20
            .ambientOcclusion(material.ambientOcclusion())
            // No isotropic/face_dimming keys: both are packed with an always set bit 2
            .packedBools((byte) (ALWAYS_SET_BIT | (material.faceDimming() ? FACE_DIMMING_BIT : 0) | (material.isotropic() ? ISOTROPIC_BIT : 0)))
            .renderMethod(material.renderMethod() == null ? null : lower(material.renderMethod()))
            .tintMethod(material.tintMethod() == null ? null : lower(material.tintMethod()))
            .texture(material.texture())
            .build()));
        // Face aliases ("up": "north") go to mappings
        Map<String, MaterialInstances.Mapping> mappings = new LinkedHashMap<>();
        aliases.forEach((face, target) -> mappings.put(face, MaterialInstances.Mapping.of(target)));
        context.put(out, subject, MaterialInstances.TYPE, () -> MaterialInstances.builder().mappings(mappings).materials(converted).build());
    }

    private static void placementFilter(NbtMapBuilder out, String subject, List<PlacementFilter> filters, EncodeContext context) {
        int tagsVersion = molangVersion(context);
        List<org.geysermc.paletter.internal.wire.block.PlacementFilter.Condition> conditions = new ArrayList<>();
        for (PlacementFilter filter : filters) {
            byte faces = 0;
            for (Face face : filter.faces()) {
                // down 1, up 2, north 4, south 8, west 16, east 32
                faces |= (byte) (1 << face.ordinal());
            }
            List<org.geysermc.paletter.internal.wire.block.PlacementFilter.BlockFilter> blockFilter = new ArrayList<>();
            for (String block : filter.blocks()) {
                blockFilter.add(org.geysermc.paletter.internal.wire.block.PlacementFilter.BlockFilter.builder().name(block).build());
            }
            for (String tag : filter.tags()) {
                blockFilter.add(org.geysermc.paletter.internal.wire.block.PlacementFilter.BlockFilter.builder()
                    .tags(tag).tagsVersion(tagsVersion).build());
            }
            // The key is block_filter (singular); Geyser's block_filters is not on the wire
            conditions.add(new org.geysermc.paletter.internal.wire.block.PlacementFilter.Condition(faces, blockFilter));
        }
        context.put(out, subject, org.geysermc.paletter.internal.wire.block.PlacementFilter.TYPE,
            () -> org.geysermc.paletter.internal.wire.block.PlacementFilter.of(conditions));
    }

    private static void transformation(NbtMapBuilder out, String subject, Transformation t, EncodeContext context) {
        boolean pivots = t.hasPivots();
        context.put(out, subject, org.geysermc.paletter.internal.wire.block.Transformation.TYPE,
            () -> org.geysermc.paletter.internal.wire.block.Transformation.builder()
                // Rotation in quarter turns
                .rotationX(quarterTurns(t.rotationX()))
                .rotationY(quarterTurns(t.rotationY()))
                .rotationZ(quarterTurns(t.rotationZ()))
                .scaleX(t.scaleX()).scaleY(t.scaleY()).scaleZ(t.scaleZ())
                .translationX(t.translationX()).translationY(t.translationY()).translationZ(t.translationZ())
                .rotationPivotX(pivots ? t.rotationPivotX() : null)
                .rotationPivotY(pivots ? t.rotationPivotY() : null)
                .rotationPivotZ(pivots ? t.rotationPivotZ() : null)
                .scalePivotX(pivots ? t.scalePivotX() : null)
                .scalePivotY(pivots ? t.scalePivotY() : null)
                .scalePivotZ(pivots ? t.scalePivotZ() : null)
                .build());
    }

    private static int quarterTurns(float degrees) {
        return (int) ((degrees % 360 + 360) % 360) / 90;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
