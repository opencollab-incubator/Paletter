package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.EmbeddedVisual;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.ItemVisual;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.SelectionBox;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Dyeable;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.HoverTextColor;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.Shooter;
import org.geysermc.paletter.item.component.UseAnimation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The hand transforms: the fields no rule inferred from the BDS captures explains. The code generator's table
 * ({@code HandTransforms} in the tools) names the field each method encodes; the generated encoders call
 * {@code Transforms.name(ctx, node, owner)} with the node the owner writes to and the record that holds the field (the
 * component itself for a whole component transform), so a name the table gives and this class lacks does not compile.
 * Overloads per owner type.
 * <p>
 * Each method's Javadoc says why no inferred rule explains its field and what evidence its output rests on. Where BDS
 * builds differ, the method compares {@link WireContext#protocol()} with the generated {@link ComponentIndex} constant
 * of the first capture that shows the newer shape.
 */
public final class Transforms {
    // The bits of a material instance's packed_bools, in the order of Mojang's BlockMaterialInstancePackedBools
    // (names from LeviLamina's headers; BDS 1.26.63.1 reads and writes the low five bits).

    /** {@code mFaceDimming}: JSON {@code face_dimming}, set by default. */
    static final int FACE_DIMMING_BIT = 1;
    /** {@code mRandomizeUVRotation}: JSON {@code isotropic}. */
    static final int RANDOMIZE_UV_ROTATION_BIT = 1 << 1;
    /**
     * {@code mSupportsTextureVariations}. BDS sets it on every material instance of a block whose JSON
     * {@code format_version} is 1.21.110 or newer, and clears it below. The BDS 1.26.63.1 binary does this in
     * {@code handleVersionBasedInitialization} of {@code BlockMaterialInstancesDescription}, {@code BlockItemVisualDescription}
     * and {@code BlockEmbeddedVisualDescription}, by comparing the format version with a static 1.21.110. The captures of
     * BDS 1.26.36.1 to 1.26.63.1 agree, and there the bit does not depend on the texture, the render method or the face.
     * Paletter models current-format blocks, so it is always set.
     */
    static final int SUPPORTS_TEXTURE_VARIATIONS_BIT = 1 << 2;
    /** {@code mAlphaMaskedTint}: JSON {@code alpha_masked_tint}. */
    static final int ALPHA_MASKED_TINT_BIT = 1 << 3;
    /** {@code mDithering}: BDS never sends it (it rejects or ignores JSON {@code dithering}), so Paletter does not either. */
    static final int DITHERING_BIT = 1 << 4;

    private static final String DEFAULT_CULLING_LAYER = "minecraft:culling_layer.undefined";
    /** The documented default of {@code culling_shape}; BDS sends an empty string when it is omitted. */
    private static final String DEFAULT_CULLING_SHAPE = "minecraft:empty";

    private Transforms() {
    }

    // ---- collision_boxes -------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:collision_box} as a list of boxes: {@code enabled} and per box min/max in 0..16 block space; the
     * boolean form is written by the generated rules. Each min/max leaf depends on both {@code origin} and {@code size}
     * (and BDS orders min and max per axis), so fitting one field at a time gives conflicting linear rules on every leaf.
     * <p>
     * y is {@code origin.y} to {@code origin.y + size.y}, z {@code 8 + origin.z} to that plus {@code size.z}. x runs like z
     * in the captures of BDS 1.26.36.1 to 1.26.52.3; from 1.26.63.1 BDS mirrors it: {@code minX = 8 - origin.x - size.x},
     * {@code maxX = 8 - origin.x}. From 1.26.70.24 BDS also sends {@code shape} "" and {@code useVoxelShapes} 0, as it does
     * for the boolean form.
     */
    public static void collisionBoxes(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull CollisionBox c) {
        if (c.enabled() != null) {
            return;
        }
        boolean mirrorX = ctx.protocol() >= ComponentIndex.BDS_1_26_63_1;
        out.put("minecraft:collision_box.enabled", (byte) 1);
        out.list("minecraft:collision_box.boxes");
        for (CollisionBox.Entry box : c.boxes()) {
            CollisionBox.Vec3 origin = box.origin() == null ? new CollisionBox.Vec3(-8, 0, -8) : box.origin();
            CollisionBox.Vec3 size = box.size() == null ? new CollisionBox.Vec3(16, 16, 16) : box.size();
            WireNode b = out.element("minecraft:collision_box.boxes");
            float x1 = mirrorX ? 8f - origin.x() - size.x() : 8f + origin.x();
            float x2 = x1 + size.x();
            float y1 = origin.y();
            float y2 = origin.y() + size.y();
            float z1 = 8f + origin.z();
            float z2 = z1 + size.z();
            b.put("minX", Math.min(x1, x2)).put("maxX", Math.max(x1, x2));
            b.put("minY", Math.min(y1, y2)).put("maxY", Math.max(y1, y2));
            b.put("minZ", Math.min(z1, z2)).put("maxZ", Math.max(z1, z2));
        }
        if (ctx.protocol() >= ComponentIndex.BDS_1_26_70_24) {
            out.put("minecraft:collision_box.shape", "");
            out.put("minecraft:collision_box.useVoxelShapes", (byte) 0);
        }
    }

    // ---- geometry ----------------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:geometry}, in the shape every BDS 1.26.63.1 capture shows: the description fields plus four flags
     * BDS always sends as 0 ({@code ignoreGeometryForIsSolid}, {@code isV1Fullblock}, {@code needsLegacyTopRotation},
     * {@code useBlockTypeLightAbsorption}), unset fields as BDS sends them ({@code culling} "", {@code culling_layer}
     * undefined). Bone visibility is {@link #boneVisibility}.
     * <p>
     * BDS sends {@code n_way_visual_rotation} from 1.26.63.1 on, every axis, "" for an axis not set. The captures of
     * BDS 1.26.36.1 to 1.26.52.3 rejected the base of the component, so their shape is this one without it.
     */
    public static void geometry(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Geometry c) {
        NbtMapBuilder geometry = geometry(c.identifier(), boneVisibility(ctx, Geometry.NAME, c.boneVisibility()), c.culling(), c.cullingLayer(),
            c.cullingShape(), c.uvLock(), c.uvLockBones()).toBuilder();
        Geometry.NWayVisualRotation rotation = c.nWayVisualRotation();
        if (rotation != null && ctx.protocol() >= ComponentIndex.BDS_1_26_63_1) {
            geometry.putCompound("n_way_visual_rotation", rotation(rotation.x(), rotation.y(), rotation.z()));
        }
        out.put("minecraft:geometry", geometry.build());
    }

    /**
     * The geometry of {@code minecraft:embedded_visual}: the shape of {@link #geometry} without its four flags, inline,
     * with {@code n_way_visual_rotation} from BDS 1.26.63.1 on, the {@code uv_lock} of {@link #uvLock(Boolean, List)} and the
     * bone visibility of {@link #boneVisibility}. The captures of BDS 1.26.36.1 to 1.26.70.24 bear it out.
     */
    public static void embeddedGeometry(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull EmbeddedVisual c) {
        EmbeddedVisual.Geometry g = c.geometry();
        NbtMapBuilder geometry = geometry(g.identifier(), boneVisibility(ctx, EmbeddedVisual.NAME, g.boneVisibility()), g.culling(),
            g.cullingLayer(), g.cullingShape(), g.uvLock(), g.uvLockBones()).toBuilder();
        for (String flag : List.of("ignoreGeometryForIsSolid", "isV1Fullblock", "needsLegacyTopRotation", "useBlockTypeLightAbsorption")) {
            geometry.remove(flag);
        }
        EmbeddedVisual.NWayVisualRotation rotation = g.nWayVisualRotation();
        if (rotation != null && ctx.protocol() >= ComponentIndex.BDS_1_26_63_1) {
            geometry.putCompound("n_way_visual_rotation", rotation(rotation.x(), rotation.y(), rotation.z()));
        }
        out.put("minecraft:embedded_visual.geometry", geometry.build());
    }

    /**
     * {@code item_visual.geometry.bone_visibility}: the {@link #boneVisibility} every geometry sends, which no rule over the
     * JSON value fits (BDS parses the expression); the other geometry fields of {@code item_visual} have inferred rules.
     */
    public static void itemVisualBoneVisibility(@NonNull WireContext ctx, @NonNull WireNode out, ItemVisual.@NonNull Geometry c) {
        out.put("minecraft:item_visual.geometryDescription.bone_visibility", boneVisibility(ctx, ItemVisual.NAME, c.boneVisibility()));
    }

    /**
     * {@code bone_visibility} as BDS sends it for the geometry of {@code minecraft:geometry}, {@code embedded_visual} and
     * {@code item_visual} alike: per bone a Molang expression, which BDS parses. The map of the API holds text: the
     * expression as the add-on JSON has it, {@code "true"} or {@code "false"} for a JSON boolean. The captures of BDS
     * 1.26.36.1 to 1.26.70.24, with a geometry other than {@code minecraft:geometry.full_block} (BDS refuses an entry
     * for that one, and so does the API; a bone the geometry does not have is taken), show the same in every build:
     * <ul>
     *     <li>A number is sent as the float it is, in six decimals: {@code "1"}, {@code "1.0"}, {@code " 1 "} and
     *     {@code "1."} as {@code "1.000000"}, {@code ".5"} as {@code "0.500000"}, {@code "1e3"} as {@code "1000.000000"},
     *     {@code "16777217"} as {@code "16777216.000000"}, {@code "0.0078125"} as {@code "0.007812"}, {@code "-0"} as
     *     {@code "-0.000000"} ({@link #molangNumber}).</li>
     *     <li>{@code "true"} is {@code "1.000000"} and {@code "false"} is {@code "0.000000"}. That is what BDS sends for
     *     the text in every build and for the JSON boolean from 1.26.63.1 on; the older builds refuse the JSON boolean
     *     ("expected an object"), so there the text is what an add-on can write.</li>
     *     <li>An expression with a query is sent as written ({@code "q.block_state('a:b')"},
     *     {@code "!q.block_state('a:b')"}, {@code "query.block_state('a:b')"}).</li>
     *     <li>Any other expression BDS computes where it can ({@code "1 + 1"} is {@code "2.000000"}: a capture that is
     *     not kept, see {@code mapValues} of {@code fixtures.json}) and sends as written where it cannot ({@code "+1"},
     *     with an error in its log). Paletter does not compute Molang: it sends the text as written, with a warning.</li>
     * </ul>
     * BDS 1.26.36.1 sends each entry as a compound {@code {expression, version}}, the version being the
     * {@code molangVersion} it sends for every block; 1.26.45.1 and later send the expression as a string.
     */
    private static @NonNull NbtMap boneVisibility(@NonNull WireContext ctx, @NonNull String component, @Nullable Map<String, String> bones) {
        NbtMapBuilder visibility = NbtMap.builder();
        if (bones == null || bones.isEmpty()) {
            return visibility.build();
        }
        boolean compound = ctx.protocol() < ComponentIndex.BDS_1_26_45_1;
        short version = 0;
        if (compound) {
            WireNode envelope = new WireNode();
            BlockWire.envelope(ctx, envelope);
            version = (short) envelope.build().getInt("molangVersion");
        }
        for (Map.Entry<String, String> bone : bones.entrySet()) {
            String value = bone.getValue();
            String expression = value.equals("true") ? "1.000000" : value.equals("false") ? "0.000000" : molangNumber(value);
            if (expression == null) {
                expression = value;
                if (!MOLANG_QUERY.matcher(value).find()) {
                    ctx.changed(component, "bone_visibility " + bone.getKey() + ": " + value + " is sent as written; BDS computes an "
                        + "expression without a query where it can, which Paletter does not");
                }
            }
            if (compound) {
                visibility.putCompound(bone.getKey(), NbtMap.builder().putString("expression", expression).putShort("version", version).build());
            } else {
                visibility.putString(bone.getKey(), expression);
            }
        }
        return visibility.build();
    }

    /** A query in a Molang expression: BDS sends an expression that has one as written. */
    private static final Pattern MOLANG_QUERY = Pattern.compile("(?<![A-Za-z0-9_.])(q|query)\\.[a-z_]");

    /** The shapes of a Molang number the captures have: digits with or without a fraction or an exponent, spaces around. */
    private static final Pattern MOLANG_NUMBER = Pattern.compile("\\s*-?(\\d+\\.?\\d*|\\.\\d+)([eE]-?\\d+)?\\s*");

    /**
     * A Molang number as BDS sends it where it computes an expression (bone visibility, BDS 1.26.36.1 to 1.26.70.24): the
     * float in six decimals of its exact value, a tie to the even digit ({@code 0.0078125} is {@code "0.007812"}), the
     * sign kept ({@code "-0.000000"}).
     *
     * @return the number, or null if the text is not a number of a captured shape or is beyond a float
     */
    static @Nullable String molangNumber(@NonNull String text) {
        if (!MOLANG_NUMBER.matcher(text).matches()) {
            return null;
        }
        float value = Float.parseFloat(text.trim());
        if (Float.isInfinite(value)) {
            return null;
        }
        String digits = new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        return (Float.floatToRawIntBits(value) < 0 && !digits.startsWith("-") ? "-" : "") + digits;
    }

    /** {@code n_way_visual_rotation}: every axis, an empty string for an axis that is not set. */
    private static NbtMap rotation(@Nullable String x, @Nullable String y, @Nullable String z) {
        return NbtMap.builder()
            .putString("x", x == null ? "" : x)
            .putString("y", y == null ? "" : y)
            .putString("z", z == null ? "" : z)
            .build();
    }

    static @NonNull NbtMap geometry(String identifier, @NonNull NbtMap boneVisibility, @Nullable String culling,
                                    @Nullable String cullingLayer, @Nullable String cullingShape, @Nullable Boolean uvLock,
                                    @Nullable List<String> uvLockBones) {
        NbtMapBuilder geometry = NbtMap.builder()
            .putCompound("bone_visibility", boneVisibility)
            .putString("culling", culling == null ? "" : culling)
            .putString("culling_layer", cullingLayer == null ? DEFAULT_CULLING_LAYER : cullingLayer)
            .putString("culling_shape", cullingShape == null || cullingShape.equals(DEFAULT_CULLING_SHAPE) ? "" : cullingShape)
            .putString("identifier", identifier)
            // 1 for ignoreGeometryForIsSolid below format_version 1.21.90 and for isV1Fullblock of the full block geometry up to
            // 1.21.120 (the format sweep of BDS 1.26.63.1); 0 for the current format Paletter writes
            .putByte("ignoreGeometryForIsSolid", (byte) 0)
            .putByte("isV1Fullblock", (byte) 0)
            .putByte("needsLegacyTopRotation", (byte) 0)
            .putByte("useBlockTypeLightAbsorption", (byte) 0);
        geometry.put("uv_lock", uvLock(uvLock, uvLockBones));
        return geometry.build();
    }

    /**
     * {@code geometry.uv_lock} of {@code minecraft:item_visual}: the value {@code minecraft:geometry} sends for its
     * {@code uv_lock}. The bones are sent in the order of {@link #bdsBoneOrder}, which depends on the hash of each name: a
     * rule fitted to the captured names would only order those. The captures of BDS 1.26.36.1 to 1.26.70.24 send
     * {@code true} as 1, a list as its bones, and take both for the full block geometry too.
     */
    public static void itemVisualUvLock(@NonNull WireContext ctx, @NonNull WireNode out, ItemVisual.@NonNull Geometry g) {
        out.put("minecraft:item_visual.geometryDescription.uv_lock", uvLock(g.uvLock(), g.uvLockBones()));
    }

    /**
     * {@code uv_lock} as BDS sends it for every geometry ({@code minecraft:geometry} and the geometry of {@code item_visual}
     * and {@code embedded_visual}, BDS 1.26.36.1 to 1.26.70.24): a byte for the boolean form, 0 when it is not set; for the
     * list form the bones as strings in {@link #bdsBoneOrder BDS's order}, an empty list without element type.
     */
    static @NonNull Object uvLock(@Nullable Boolean uvLock, @Nullable List<String> bones) {
        if (bones == null) {
            return Wire.bool(Boolean.TRUE.equals(uvLock));
        }
        return Wire.strings(bdsBoneOrder(bones));
    }

    /**
     * The {@code uv_lock} bones in the order BDS sends them. BDS keeps them in a {@code std::set<HashedString>}, so each
     * bone is sent once, ascending by the unsigned 64 bit hash of its name (captures of BDS 1.26.36.1 to 1.26.63.1).
     * Bones with equal hashes keep their order; BDS's order for them is not known.
     */
    static @NonNull List<String> bdsBoneOrder(@NonNull List<String> bones) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(bones));
        ordered.sort((a, b) -> Long.compareUnsigned(BlockRegistry.nameHash(a), BlockRegistry.nameHash(b)));
        return ordered;
    }

    // ---- material_instances ----------------------------------------------------------------------------------------

    /**
     * {@code minecraft:material_instances}: face aliases to {@code mappings}, faces to {@code materials}, face dimming,
     * isotropic and alpha_masked_tint packed into {@code packed_bools} (bits 0, 1 and 3; bit 2,
     * {@link #SUPPORTS_TEXTURE_VARIATIONS_BIT}, always set), defaults as BDS sends them. The flags share one byte, so each
     * field's value table only fits with the others at their default, and the faces and aliases of one JSON object split
     * into two compounds: the rules inferred per field describe neither.
     */
    public static void materialInstances(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull MaterialInstances c) {
        out.put("minecraft:material_instances", materials(c));
    }

    /**
     * {@code minecraft:item_visual}'s material instances, sent as {@code materialInstancesDescription} in the
     * {@code minecraft:material_instances} shape (see {@link #materialInstances}).
     */
    public static void materialInstancesPacked(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull ItemVisual c) {
        out.put("minecraft:item_visual.materialInstancesDescription", materials(c.materialInstances()));
    }

    /**
     * {@code minecraft:embedded_visual}'s material instances: a whole {@code minecraft:material_instances} object, inline
     * per face with its flags as bytes. Setting it also fills in the sibling geometry's defaults, so correlating one field
     * at a time cannot separate the two. The captures of BDS 1.26.36.1 to 1.26.70.24 bear out three accepted variants,
     * none of which sets {@code alpha_masked_tint}: it is sent as 0, and a caller who sets it is told (unverified until a
     * capture shows what BDS sends).
     */
    public static void materialInstancesInline(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull EmbeddedVisual c) {
        NbtMapBuilder faces = NbtMap.builder();
        faces(c.materialInstances()).forEach((face, instance) -> {
            if (Boolean.TRUE.equals(instance.alphaMaskedTint())) {
                ctx.changed(EmbeddedVisual.NAME, "material instance " + face + " alpha_masked_tint is sent as 0; no capture shows BDS sending it");
            }
            faces.putCompound(face, NbtMap.builder()
                .putString("texture", instance.texture() == null ? "" : instance.texture())
                .putString("render_method", instance.renderMethod() == null ? "opaque" : instance.renderMethod().value())
                .putString("tint_method", instance.tintMethod() == null ? "none" : instance.tintMethod())
                .putFloat("ambient_occlusion", instance.ambientOcclusion() == null ? 1f : instance.ambientOcclusion())
                .putByte("face_dimming", Wire.bool(instance.faceDimming() == null || instance.faceDimming()))
                .putByte("isotropic", Wire.bool(instance.isotropic()))
                .putByte("alpha_masked_tint", (byte) 0)
                .build());
        });
        out.put("minecraft:embedded_visual.material_instances", faces.build());
        // (an empty material_instances object is sent empty)
    }

    static @NonNull NbtMap materials(@NonNull MaterialInstances c) {
        NbtMapBuilder mappings = NbtMap.builder();
        aliases(c).forEach((face, target) -> mappings.putCompound(face, NbtMap.builder().putString("value", target).build()));
        NbtMapBuilder materials = NbtMap.builder();
        faces(c).forEach((face, instance) -> {
            int packed = SUPPORTS_TEXTURE_VARIATIONS_BIT | (instance.faceDimming() == null || instance.faceDimming() ? FACE_DIMMING_BIT : 0)
                | (instance.isotropic() ? RANDOMIZE_UV_ROTATION_BIT : 0) | (Boolean.TRUE.equals(instance.alphaMaskedTint()) ? ALPHA_MASKED_TINT_BIT : 0);
            materials.putCompound(face, NbtMap.builder()
                .putFloat("ambient_occlusion", instance.ambientOcclusion() == null ? 1f : instance.ambientOcclusion())
                .putByte("packed_bools", (byte) packed)
                .putString("render_method", instance.renderMethod() == null ? "opaque" : instance.renderMethod().value())
                .putString("texture", instance.texture() == null ? "" : instance.texture())
                .putString("tint_method", instance.tintMethod() == null ? "none" : instance.tintMethod())
                .build());
        });
        return NbtMap.builder().putCompound("mappings", mappings.build()).putCompound("materials", materials.build()).build();
    }

    private static Map<String, MaterialInstances.Instance> faces(MaterialInstances c) {
        Map<String, MaterialInstances.Instance> faces = new LinkedHashMap<>();
        putIfSet(faces, "*", c.all());
        putIfSet(faces, "down", c.down());
        putIfSet(faces, "east", c.east());
        putIfSet(faces, "north", c.north());
        putIfSet(faces, "south", c.south());
        putIfSet(faces, "up", c.up());
        putIfSet(faces, "west", c.west());
        faces.putAll(c.instances());
        return faces;
    }

    private static Map<String, String> aliases(MaterialInstances c) {
        Map<String, String> aliases = new LinkedHashMap<>();
        putIfSet(aliases, "*", c.allAlias());
        putIfSet(aliases, "down", c.downAlias());
        putIfSet(aliases, "east", c.eastAlias());
        putIfSet(aliases, "north", c.northAlias());
        putIfSet(aliases, "south", c.southAlias());
        putIfSet(aliases, "up", c.upAlias());
        putIfSet(aliases, "west", c.westAlias());
        aliases.putAll(c.aliases());
        return aliases;
    }

    private static <V> void putIfSet(Map<String, V> map, String key, @Nullable V value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ---- block_descriptor_states -------------------------------------------------------------------------------------

    /**
     * The {@code states} of a block descriptor ({@code block_placer.use_on}, {@code entity_placer.use_on} and
     * {@code dispense_on}, {@code digger.destroy_speeds[].block}, {@code placement_filter} block filters): a compound of
     * state values, typed like the named block's states in the palette's registry (booleans as bytes, ints as the
     * registry's type). BDS 1.26.63.1 sends an enum state as its index ({@code pillar_axis} "y" as 0, see
     * {@code custom_item_registry.snbt}); that index is not derivable from the registry, so a string state is sent as
     * given and reported. BDS drops a whole descriptor whose block lacks a state (the capture's invented state names), which
     * is reported too. No rule over the JSON value fits, because the registry decides the type.
     */
    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, BlockPlacer.UseOn d) {
        Wire.blockDescriptor(ctx, out, "", d.name(), d.states(), d.tags());
    }

    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, EntityPlacer.UseOn d) {
        Wire.blockDescriptor(ctx, out, "", d.name(), d.states(), d.tags());
    }

    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, Digger.Block d) {
        Wire.blockDescriptor(ctx, out, "block", d.name(), d.states(), d.tags());
    }

    /**
     * Placement filter descriptors send their states as a list of {@code {state, type, value}}, only when there are any.
     */
    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, PlacementFilter.BlockFilter d) {
        if (d.states().isEmpty()) {
            return;
        }
        NbtMap typed = Wire.states(ctx, d.name(), d.states());
        out.list("states");
        typed.forEach((state, value) -> {
            WireNode entry = out.element("states");
            entry.put("state", state);
            entry.put("type", stateType(value));
            entry.put("value", value);
        });
    }

    /**
     * The {@code type} of a placement filter state: 2 for an int state, as BDS 1.26.63.1 sent it. 1 for a string and 0 for
     * a boolean state follow the same order but are unverified: no accepted variant has a string or boolean state, so the
     * conformance suite does not check them.
     */
    private static int stateType(Object value) {
        return value instanceof Integer ? 2 : value instanceof Byte ? 0 : 1;
    }

    // ---- block_name ----------------------------------------------------------------------------------------------------

    /**
     * {@code block_placer.block}: the block's name (a name without namespace gets {@code minecraft:}, as BDS 1.26.x sends
     * {@code "dirt"}), or an empty string for a vanilla block this version does not have.
     * BDS resolves the name against its block registry: the captures of 1.26.45.1 and 1.26.52.3 send
     * {@code minecraft:ice_crystal} and {@code minecraft:icicle} as {@code ""}, that of 1.26.63.1 as named, so the leaf
     * depends on the version's blocks, not on the JSON value. A name of another namespace is a custom block, which may be
     * registered after the item, so it is sent as it is.
     */
    public static void blockName(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull BlockPlacer c) {
        String block = c.block();
        if (block == null) {
            return;
        }
        boolean vanilla = block.indexOf(':') < 0 || block.startsWith("minecraft:");
        if (vanilla && ctx.registry().blockStates(block) == null) {
            ctx.changed(BlockPlacer.NAME, "block " + block + " is not a block of this version; sent as an empty name, like BDS");
            out.put("minecraft:block_placer.block", "");
            return;
        }
        out.put("minecraft:block_placer.block", vanilla ? Wire.namespaced(block) : block);
    }

    /**
     * {@code block_placer.replace_block_item}: BDS takes it only for the block that has the item's own identifier. For
     * any other block it logs "Item identifier ... does not match the block identifier ... Cannot replace the block item"
     * and sends {@code replaceBlockItem} 0, so the leaf follows two fields and the item's identifier, which no rule over
     * the field's value fits. The captures of BDS 1.26.36.1 to 1.26.70.24 send 1 for {@code true} with a block of the
     * item's identifier and 0 for {@code true} with {@code minecraft:dirt}.
     */
    public static void replaceBlockItem(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull BlockPlacer c) {
        boolean ownBlock = c.block() != null && Wire.namespaced(c.block()).equals(ctx.subject());
        if (c.replaceBlockItem() && !ownBlock) {
            ctx.changed(BlockPlacer.NAME, "replace_block_item needs the block to have the item's identifier, not " + c.block()
                + "; sent as false, like BDS");
        }
        out.put("minecraft:block_placer.replaceBlockItem", Wire.bool(c.replaceBlockItem() && ownBlock));
    }

    // ---- item descriptors --------------------------------------------------------------------------------------------

    /**
     * {@code ammunition[].item}: an item descriptor: {@code {name}} for an item the
     * registry knows, {@code {}} for any other descriptor (the captures send {@code "item": {}} and objects without a
     * {@code name} as {@code {}}, where the inferred identity of {@code item.name} would leave the compound out); an entry
     * without item is sent with the empty descriptor too.
     */
    public static void ammunitionItemDescriptor(@NonNull WireContext ctx, @NonNull WireNode out, Shooter.@NonNull Ammunition c) {
        Wire.itemDescriptor(ctx, out, "item", Shooter.NAME, "ammunition[].item", c.item());
    }

    /**
     * {@code repair_items}: items as {@code [{name}]} (names as written, not namespaced; or one {@code {tags}} Molang
     * query), an entry without known items dropped with its repair amount. The repair amount is a float for a number and
     * Molang for a string: the two JSON types land on the same leaf with different NBT types, which no single rule fits.
     * BDS 1.26.36.1 sends the Molang as a compound {@code {expression, version: 0s}} ({@code -1s} for an empty expression),
     * 1.26.45.1 and later as a string.
     */
    public static void itemDescriptorList(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Repairable c) {
        boolean molangCompound = ctx.protocol() < ComponentIndex.BDS_1_26_45_1;
        out.list("minecraft:repairable.repair_items");
        for (Repairable.RepairItems entry : c.repairItems()) {
            List<String> items = entry.items() == null ? List.of() : entry.items();
            for (String item : items) {
                if (!ctx.registry().itemExists(item)) {
                    ctx.changed(Repairable.NAME, "repair item " + item + " is not an item of this version; sent anyway, as BDS does");
                }
            }
            WireNode e = out.element("minecraft:repairable.repair_items");
            e.list("items");
            for (String item : items) {
                e.element("items").put("name", item);
            }
            if (entry.itemsTags() != null) {
                // The string form is a Molang tag query ({tags}), e.g. query.any_tag('minecraft:planks')
                e.element("items").put("tags", entry.itemsTags());
            }
            if (entry.repairAmountExpression() != null && molangCompound) {
                // version -1 for an empty expression (BDS 1.26.36.1: repair_amount "")
                e.child("repair_amount").put("expression", entry.repairAmountExpression())
                    .put("version", (short) (entry.repairAmountExpression().isEmpty() ? -1 : 0));
            } else if (entry.repairAmountExpression() != null) {
                e.put("repair_amount", entry.repairAmountExpression());
            } else if (entry.repairAmount() != null) {
                e.put("repair_amount", entry.repairAmount());
            }
        }
    }

    // ---- formatting_code ---------------------------------------------------------------------------------------------

    /**
     * Color name -> formatting code of {@code item_properties.hover_text_color}: Bedrock's color table, hand-entered. The
     * captures have only the names of the accepted {@code hover_text_color} variants; HandTransformsTest lists every other
     * entry as unverified.
     */
    private static final Map<String, Character> FORMATTING = new LinkedHashMap<>();

    static {
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray",
            "blue", "green", "aqua", "red", "light_purple", "yellow", "white", "minecoin_gold", "material_quartz", "material_iron",
            "material_netherite", "material_redstone", "material_copper", "material_gold", "material_emerald", "material_diamond",
            "material_lapis", "material_amethyst", "material_resin"};
        String codes = "0123456789abcdefghijmnpqstuv";
        for (int i = 0; i < names.length; i++) {
            FORMATTING.put(names[i], codes.charAt(i));
        }
    }

    /**
     * {@code minecraft:hover_text_color}: the component keeps the color name, {@code item_properties.hover_text_color}
     * gets its formatting code ({@code red} -> {@code §c}); unknown names only in the component, and with no value BDS
     * sends the component with an empty value. The docs give no enum for the field, so the captured names make no table
     * that covers the others: the codes are Bedrock's text color table ({@link #FORMATTING}).
     */
    public static void formattingCode(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull HoverTextColor c) {
        out.put("minecraft:hover_text_color.value", c.value() == null ? "" : c.value());
        if (c.value() == null) {
            return;
        }
        Character code = FORMATTING.get(c.value().toLowerCase(Locale.ROOT));
        if (code != null) {
            out.put("item_properties.hover_text_color", "§" + code);
        }
    }

    // ---- icon_textures -----------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:icon}: only {@code item_properties.minecraft:icon} as {@code {textures: {default: <texture>}}}; the
     * string form is the default texture. The captures vary the textures map only as {@code {}}, and the documented
     * {@code texture} field is unknown to BDS, so only the string form has an inferred rule.
     */
    public static void iconTextures(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Icon c) {
        NbtMapBuilder textures = NbtMap.builder();
        if (c.textures() != null) {
            c.textures().forEach(textures::putString);
        }
        if (c.texture() != null) {
            textures.putString("default", c.texture());
        }
        out.put("item_properties.minecraft:icon", NbtMap.builder().putCompound("textures", textures.build()).build());
    }

    // ---- rgb_color ---------------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:dyeable} default_color: six hex digits or exactly [r, g, b] to a list of three ints, white by
     * default. BDS 1.26.x rejects the whole item for any other form ("a hex value with 6 characters"; the captured
     * {@code []}, {@code [3]} and {@code [3, 7]}), so those are reported and the field is left out rather than sent as a
     * derived color. Channels outside 0-255 are clamped, as captured ({@code [-90, 450, 720]}). Three or eight hex digits
     * are uncaptured; they are treated as rejected, like BDS's message says.
     */
    public static void rgbColor(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Dyeable c) {
        List<Integer> rgb = List.of(255, 255, 255);
        if (c.defaultColorHex() != null) {
            String hex = c.defaultColorHex().startsWith("#") ? c.defaultColorHex().substring(1) : c.defaultColorHex();
            if (!HEX_COLOR.matcher(hex).matches()) {
                ctx.drop(Dyeable.NAME, "default_color " + c.defaultColorHex() + " is not a hex color of 6 digits");
                return;
            }
            int value = Integer.parseInt(hex, 16);
            rgb = List.of((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
        } else if (c.defaultColor() != null) {
            if (c.defaultColor().size() != 3) {
                ctx.drop(Dyeable.NAME, "default_color " + c.defaultColor() + " does not have exactly 3 channels");
                return;
            }
            rgb = c.defaultColor().stream().map(v -> Math.max(0, Math.min(255, v))).toList();
        }
        out.put("minecraft:dyeable.default_color", Wire.ints(rgb));
    }

    private static final Pattern HEX_COLOR = Pattern.compile("[0-9a-fA-F]{6}");

    // ---- destructible_by_mining --------------------------------------------------------------------------------------

    /**
     * {@code minecraft:destructible_by_mining}: {@code value} is seconds_to_destroy (0 by default, -1 for the false form);
     * item specific speeds in the legacy {@code {Name, Aux}} item descriptor shape, only when the list is not empty (a list
     * of entries without item is sent empty, as BDS does for {@code "item": []}). The captures rejected the base, and
     * fitting one field at a time gave conflicting count and table rules for {@code value}.
     */
    public static void destructibleByMining(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull DestructibleByMining c) {
        float value = Boolean.FALSE.equals(c.enabled()) ? -1f : Boolean.TRUE.equals(c.enabled()) ? 0f : c.secondsToDestroy();
        out.put("minecraft:destructible_by_mining.value", value);
        if (c.itemSpecificSpeeds() != null && !c.itemSpecificSpeeds().isEmpty()) {
            out.list("minecraft:destructible_by_mining.item_specific_speeds");
            for (DestructibleByMining.ItemSpecificSpeeds speed : c.itemSpecificSpeeds()) {
                if (speed.item() == null || Wire.namespaced(speed.item()).startsWith("minecraft:") && !ctx.registry().itemExists(speed.item())) {
                    // BDS keeps the list but drops an entry that names no item ("item": [] in add-on JSON) or a vanilla item
                    // it does not have ("", "minecraft:paletter_unknown"); a custom item may be registered later
                    continue;
                }
                WireNode e = out.element("minecraft:destructible_by_mining.item_specific_speeds");
                e.put("destroy_speed", speed.destroySpeed());
                e.put("item", NbtMap.builder().putString("Name", Wire.namespaced(speed.item())).putShort("Aux", (short) 32767).build());
            }
        }
    }


    /**
     * A permutation's Molang condition as BDS sends it: a number constant like any Molang number ({@link #molangNumber}),
     * anything else as written. Only {@code "1"} and {@code "0"} are captured as conditions (BDS 1.26.36.1 to 1.26.70.24);
     * that BDS prints the other number shapes the same way here is unverified.
     */
    static String condition(String condition) {
        String number = molangNumber(condition);
        return number != null ? number : condition;
    }

    // ---- selection_box -----------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:selection_box}: {@code enabled} and the box as origin/size in JSON units, always all three; the
     * false form keeps the full cube. BDS validates the box against the block bounds and rejected every origin variant of
     * the captures, so {@code origin} has no inferred rule.
     */
    public static void selectionBox(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull SelectionBox c) {
        SelectionBox.Vec3 origin = c.origin() == null ? new SelectionBox.Vec3(-8, 0, -8) : c.origin();
        SelectionBox.Vec3 size = c.size() == null ? new SelectionBox.Vec3(16, 16, 16) : c.size();
        out.put("minecraft:selection_box.enabled", Wire.bool(c.enabled() == null || c.enabled()));
        out.put("minecraft:selection_box.origin", Wire.floats(List.of(origin.x(), origin.y(), origin.z())));
        out.put("minecraft:selection_box.size", Wire.floats(List.of(size.x(), size.y(), size.z())));
    }

    // ---- use_animation_id --------------------------------------------------------------------------------------------

    /**
     * Animation name -> {@code item_properties.use_animation}, hand-entered. The captures have none, eat, drink and spear;
     * HandTransformsTest lists every other entry as unverified.
     */
    private static final Map<String, Integer> USE_ANIMATIONS = Map.of("none", 0, "eat", 1, "drink", 2, "block", 3, "bow", 4,
        "camera", 5, "spear", 6, "crossbow", 9, "spyglass", 10, "brush", 12);

    /**
     * {@code minecraft:use_animation}: the component keeps the name, {@code item_properties.use_animation} numbers it
     * (none, eat, drink and spear are captured, see {@link #USE_ANIMATIONS}); unknown names are 0. BDS's error truncates the
     * enum ("none, eat, drink, ... (7 others)"), so the other names and ids are not BDS data yet.
     */
    public static void useAnimationId(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull UseAnimation c) {
        String value = c.value() == null ? "none" : c.value();
        out.put("minecraft:use_animation.value", value);
        out.put("item_properties.use_animation", USE_ANIMATIONS.getOrDefault(value, 0));
    }

    // ---- permutation_components --------------------------------------------------------------------------------------

    /**
     * One entry of {@code permutations}: its components compiled with each component's own rules; a permutation without
     * components is not sent at all, which takes its condition leaf with it. The condition is sent as BDS sends it
     * ({@link #condition}: a number constant with six decimals).
     *
     * @param out the block property data
     */
    public static void permutationComponents(@NonNull WireContext ctx, @NonNull WireNode out, CustomBlock.Permutation p) {
        if (p.components().isEmpty()) {
            return;
        }
        WireNode components = new WireNode();
        for (BlockComponent component : p.components()) {
            BlockWire.write(component, ctx, components);
        }
        WireNode entry = out.element("permutations");
        entry.put("components", components.build());
        entry.put("condition", condition(p.condition()));
    }
}
