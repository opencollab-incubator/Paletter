package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.ConnectionRule;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.EmbeddedVisual;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.ItemVisual;
import org.geysermc.paletter.block.component.LiquidDetection;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.block.component.PlacementDirection;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.RedstoneProducer;
import org.geysermc.paletter.block.component.SelectionBox;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Dyeable;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.HoverTextColor;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.UseAnimation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The hand transforms named in {@code codegen/model/transforms.json}: what no inferred rule explains (each entry there
 * says why). Method names are the camelCase of the transform names; the generator checks every name in transforms.json
 * against the public static methods here. The generated encoders call {@code Transforms.name(ctx, node, owner)} with the
 * node the owner writes to and the record that holds the transformed field (the component itself for a component level
 * transform); overloads per owner type.
 */
public final class Transforms {
    /** Bit of {@code packed_bools} BDS sets in every material it sends; meaning unknown. */
    static final int ALWAYS_SET_BIT = 1 << 2;
    static final int FACE_DIMMING_BIT = 1;
    static final int ISOTROPIC_BIT = 1 << 1;
    private static final String DEFAULT_CULLING_LAYER = "minecraft:culling_layer.undefined";
    /** The documented default of {@code culling_shape}; BDS sends an empty string when it is omitted. */
    private static final String DEFAULT_CULLING_SHAPE = "minecraft:empty";

    private Transforms() {
    }

    // ---- collision_boxes -------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:collision_box} as a list of boxes: {@code enabled} and per box min/max in 0..16 block space. BDS
     * 1.26.63.1 mirrors x: {@code minX = 8 - origin.x - size.x}, {@code maxX = 8 - origin.x}; y is {@code origin.y} to
     * {@code origin.y + size.y}, z {@code 8 + origin.z} to that plus {@code size.z}. The boolean form is written by the
     * generated rules.
     */
    public static void collisionBoxes(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull CollisionBox c) {
        if (c.enabled() != null) {
            return;
        }
        out.put("minecraft:collision_box.enabled", (byte) 1);
        out.list("minecraft:collision_box.boxes");
        for (CollisionBox.Entry box : c.boxes()) {
            CollisionBox.Vec3 origin = box.origin() == null ? new CollisionBox.Vec3(-8, 0, -8) : box.origin();
            CollisionBox.Vec3 size = box.size() == null ? new CollisionBox.Vec3(16, 16, 16) : box.size();
            WireNode b = out.element("minecraft:collision_box.boxes");
            float x1 = 8f - origin.x() - size.x();
            float x2 = 8f - origin.x();
            float y1 = origin.y();
            float y2 = origin.y() + size.y();
            float z1 = 8f + origin.z();
            float z2 = z1 + size.z();
            b.put("minX", Math.min(x1, x2)).put("maxX", Math.max(x1, x2));
            b.put("minY", Math.min(y1, y2)).put("maxY", Math.max(y1, y2));
            b.put("minZ", Math.min(z1, z2)).put("maxZ", Math.max(z1, z2));
        }
    }

    // ---- geometry ----------------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:geometry}, in the shape every BDS 1.26.63.1 capture shows: the description fields plus four flags
     * BDS always sends as 0. Bone visibility is a Molang expression, kept as a string like BDS does; the constants {@code true}/{@code false} become
     * {@code "1.000000"}/{@code "0.000000"}.
     */
    public static void geometry(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Geometry c) {
        out.put("minecraft:geometry", geometry(c.identifier(), c.boneVisibility(), c.culling(), c.cullingLayer(), c.cullingShape(),
            c.uvLock()));
    }

    /**
     * The geometry of {@code minecraft:embedded_visual}, same shape (unverified: BDS rejected every variant).
     */
    public static void geometry(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull EmbeddedVisual c) {
        // Without the four flags minecraft:geometry has, with n_way_visual_rotation when set (BDS 1.26.63.1)
        EmbeddedVisual.Geometry g = c.geometry();
        NbtMapBuilder geometry = geometry(g.identifier(), g.boneVisibility(), g.culling(), g.cullingLayer(), g.cullingShape(), g.uvLock())
            .toBuilder();
        for (String flag : List.of("ignoreGeometryForIsSolid", "isV1Fullblock", "needsLegacyTopRotation", "useBlockTypeLightAbsorption")) {
            geometry.remove(flag);
        }
        if (g.culling() == null) {
            geometry.remove("culling");
        }
        EmbeddedVisual.NWayVisualRotation rotation = g.nWayVisualRotation();
        if (rotation != null) {
            NbtMapBuilder r = NbtMap.builder();
            if (rotation.x() != null) {
                r.putString("x", rotation.x());
            }
            if (rotation.y() != null) {
                r.putString("y", rotation.y());
            }
            if (rotation.z() != null) {
                r.putString("z", rotation.z());
            }
            geometry.putCompound("n_way_visual_rotation", r.build());
        }
        out.put("minecraft:embedded_visual.geometry", geometry.build());
    }

    static @NonNull NbtMap geometry(String identifier, @Nullable Map<String, String> bones, @Nullable String culling,
                                    @Nullable String cullingLayer, @Nullable String cullingShape, @Nullable Boolean uvLock) {
        NbtMapBuilder visibility = NbtMap.builder();
        if (bones != null) {
            bones.forEach((bone, expression) -> visibility.putString(bone, switch (expression) {
                case "true" -> "1.000000";
                case "false" -> "0.000000";
                default -> expression;
            }));
        }
        return NbtMap.builder()
            .putCompound("bone_visibility", visibility.build())
            .putString("culling", culling == null ? "" : culling)
            .putString("culling_layer", cullingLayer == null ? DEFAULT_CULLING_LAYER : cullingLayer)
            .putString("culling_shape", cullingShape == null || cullingShape.equals(DEFAULT_CULLING_SHAPE) ? "" : cullingShape)
            .putString("identifier", identifier)
            .putByte("ignoreGeometryForIsSolid", (byte) 0)
            .putByte("isV1Fullblock", (byte) 0)
            .putByte("needsLegacyTopRotation", (byte) 0)
            .putByte("useBlockTypeLightAbsorption", (byte) 0)
            .putByte("uv_lock", Wire.bool(Boolean.TRUE.equals(uvLock)))
            .build();
    }

    // ---- material_instances ----------------------------------------------------------------------------------------

    /**
     * {@code minecraft:material_instances}: face aliases to {@code mappings}, faces to {@code materials}, face dimming
     * and isotropic packed into {@code packed_bools} (bit 0, bit 1, bit 2 always set), defaults as BDS sends them.
     */
    public static void materialInstances(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull MaterialInstances c) {
        out.put("minecraft:material_instances", materials(c));
    }

    /**
     * {@code minecraft:item_visual}'s material instances, in the {@code minecraft:material_instances} shape.
     */
    public static void materialInstancesPacked(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull ItemVisual c) {
        out.put("minecraft:item_visual.materialInstancesDescription", materials(c.materialInstances()));
    }

    /**
     * {@code minecraft:embedded_visual}'s material instances: inline per face, flags as bytes (unverified: BDS rejected
     * every variant).
     */
    public static void materialInstancesInline(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull EmbeddedVisual c) {
        NbtMapBuilder faces = NbtMap.builder();
        faces(c.materialInstances()).forEach((face, instance) -> faces.putCompound(face, NbtMap.builder()
            .putString("texture", instance.texture() == null ? "" : instance.texture())
            .putString("render_method", instance.renderMethod() == null ? "opaque" : instance.renderMethod().value())
            .putString("tint_method", instance.tintMethod() == null ? "none" : instance.tintMethod())
            .putFloat("ambient_occlusion", instance.ambientOcclusion() == null ? 1f : instance.ambientOcclusion())
            .putByte("face_dimming", Wire.bool(instance.faceDimming() == null || instance.faceDimming()))
            .putByte("isotropic", Wire.bool(instance.isotropic()))
            .putByte("alpha_masked_tint", (byte) 0)
            .build()));
        out.put("minecraft:embedded_visual.material_instances", faces.build());
        // (an empty material_instances object is sent empty)
    }

    static @NonNull NbtMap materials(@NonNull MaterialInstances c) {
        NbtMapBuilder mappings = NbtMap.builder();
        aliases(c).forEach((face, target) -> mappings.putCompound(face, NbtMap.builder().putString("value", target).build()));
        NbtMapBuilder materials = NbtMap.builder();
        faces(c).forEach((face, instance) -> {
            int packed = ALWAYS_SET_BIT | (instance.faceDimming() == null || instance.faceDimming() ? FACE_DIMMING_BIT : 0)
                | (instance.isotropic() ? ISOTROPIC_BIT : 0);
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
        return aliases;
    }

    private static <V> void putIfSet(Map<String, V> map, String key, @Nullable V value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ---- block_descriptor_states -------------------------------------------------------------------------------------

    /**
     * The {@code states} of a block descriptor: a compound of state values, typed like the named block's states in the
     * palette's registry (booleans as bytes). The registry decides the type, not the JSON value.
     */
    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, BlockPlacer.UseOn d) {
        descriptor(ctx, out, "", d.name(), d.states(), d.tags());
    }

    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, EntityPlacer.UseOn d) {
        descriptor(ctx, out, "", d.name(), d.states(), d.tags());
    }

    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, Digger.Block d) {
        descriptor(ctx, out, "block.", d.name(), d.states(), d.tags());
    }

    /** Item components send block descriptors as the full {name, states, tags} triple, "" for what is not set. */
    private static void descriptor(WireContext ctx, WireNode out, String prefix, @Nullable String name, Map<String, Object> states,
                                   @Nullable String tags) {
        out.put(prefix + "name", name == null ? "" : name);
        out.put(prefix + "states", states(ctx, name, states));
        out.put(prefix + "tags", tags == null ? "" : tags);
    }

    /**
     * Placement filter descriptors send their states as a list of {@code {state, type, value}}, only when there are any.
     */
    public static void blockDescriptorStates(@NonNull WireContext ctx, @NonNull WireNode out, PlacementFilter.BlockFilter d) {
        if (d.states().isEmpty()) {
            return;
        }
        NbtMap typed = states(ctx, d.name(), d.states());
        out.list("states");
        typed.forEach((state, value) -> {
            WireNode entry = out.element("states");
            entry.put("state", state);
            // int 2 observed (BDS 1.26.63.1); string 1 and boolean 0 by the same order, unverified
            entry.put("type", value instanceof Integer ? 2 : value instanceof Byte ? 0 : 1);
            entry.put("value", value);
        });
    }

    static @NonNull NbtMap states(WireContext ctx, @Nullable String block, Map<String, Object> states) {
        NbtMap known = block == null ? null : ctx.registry().blockStates(block);
        NbtMapBuilder out = NbtMap.builder();
        states.forEach((name, value) -> {
            Object type = known == null ? null : known.get(name);
            Object converted;
            if (value instanceof Boolean b) {
                converted = Wire.bool(b);
            } else if (value instanceof Integer i) {
                converted = type instanceof Byte ? (Object) i.byteValue() : i;
            } else {
                converted = value;
            }
            out.put(name, converted);
        });
        return out.build();
    }

    // ---- item descriptors --------------------------------------------------------------------------------------------

    /**
     * {@code using_converts_to}: {@code {name}} for an item the registry knows, {@code {}} otherwise (as BDS sends it).
     */
    public static void itemDescriptorName(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Food c) {
        String item = c.usingConvertsTo();
        NbtMap descriptor = item != null && ctx.registry().itemExists(item)
            ? NbtMap.builder().putString("name", Wire.namespaced(item)).build() : NbtMap.EMPTY;
        if (item != null && descriptor.isEmpty()) {
            ctx.drop(Food.NAME, "using_converts_to " + item + " is not an item");
        }
        out.put("minecraft:food.using_converts_to", descriptor);
    }

    /**
     * {@code repair_items}: items as {@code [{name}]} (or one {@code {tags}} Molang query), unknown items dropped, an entry without known items dropped with its
     * repair amount; the amount a float, or a Molang string.
     */
    public static void itemDescriptorList(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Repairable c) {
        out.list("minecraft:repairable.repair_items");
        for (Repairable.RepairItems entry : c.repairItems()) {
            List<String> items = entry.items() == null ? List.of() : entry.items();
            for (String item : items) {
                if (!ctx.registry().itemExists(item)) {
                    ctx.drop(Repairable.NAME, "repair item " + item + " is not an item of this version (sent anyway, as BDS does)");
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
            if (entry.repairAmountExpression() != null) {
                e.put("repair_amount", entry.repairAmountExpression());
            } else if (entry.repairAmount() != null) {
                e.put("repair_amount", entry.repairAmount());
            }
        }
    }

    // ---- formatting_code ---------------------------------------------------------------------------------------------

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
     * gets its formatting code ({@code red} -> {@code §c}); unknown names only in the component.
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

    // ---- quarter_turns -----------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:transformation} rotation: degrees to quarter turns per axis ({@code RX}, {@code RY}, {@code RZ}).
     */
    public static void quarterTurns(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Transformation c) {
        Transformation.Vec3 r = c.rotation();
        out.put("minecraft:transformation.RX", quarterTurns(r == null ? 0 : r.x()));
        out.put("minecraft:transformation.RY", quarterTurns(r == null ? 0 : r.y()));
        out.put("minecraft:transformation.RZ", quarterTurns(r == null ? 0 : r.z()));
    }

    static int quarterTurns(float degrees) {
        return (int) (((degrees % 360) + 360) % 360) / 90;
    }

    // ---- face_bits / face_list ---------------------------------------------------------------------------------------

    private static final List<String> FACES = List.of("down", "up", "north", "south", "west", "east");

    static byte faceBits(List<String> faces) {
        int bits = 0;
        for (String face : faces) {
            int index = FACES.indexOf(face);
            if (index >= 0) {
                bits |= 1 << index;
            }
        }
        return (byte) bits;
    }

    /**
     * Faces as a bit set: down 1, up 2, north 4, south 8, west 16, east 32.
     */
    public static void faceBits(@NonNull WireContext ctx, @NonNull WireNode out, PlacementFilter.Conditions c) {
        out.put("allowed_faces", faceBits(c.allowedFaces()));
    }

    public static void faceBits(@NonNull WireContext ctx, @NonNull WireNode out, LiquidDetection.DetectionRules c) {
        out.put("stopsLiquidFromDirection", faceBits(c.stopsLiquidFlowingFromDirection()));
    }

    /**
     * A list of face or direction names, as named.
     */
    public static void faceList(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull RedstoneProducer c) {
        out.put("minecraft:redstone_producer.connected_faces", Wire.strings(c.connectedFaces().stream().map(f -> f.value()).toList()));
    }

    public static void faceList(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull ConnectionRule c) {
        out.put("minecraft:connection_rule.enabled_directions", Wire.strings(c.enabledDirections().stream().map(f -> f.value()).toList()));
    }

    // ---- enabled_state_flags -----------------------------------------------------------------------------------------

    /**
     * {@code minecraft:placement_direction} enabled_states: one byte per state the trait can enable.
     */
    public static void enabledStateFlags(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull PlacementDirection c) {
        for (PlacementDirection.EnabledStates state : PlacementDirection.EnabledStates.values()) {
            out.put("enabled_states." + state.value().replaceFirst("^minecraft:", ""), Wire.bool(c.enabledStates().contains(state)));
        }
        out.put("enabled_states.sixteen_way_rotation", (byte) 0);
    }

    // ---- icon_textures -----------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:icon}: only {@code item_properties.minecraft:icon {textures}}; the string form is the default texture.
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
     * {@code minecraft:dyeable} default_color: hex or [r, g, b] to a list of three ints; white by default.
     */
    public static void rgbColor(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull Dyeable c) {
        List<Integer> rgb = List.of(255, 255, 255);
        if (c.defaultColorHex() != null) {
            String hex = c.defaultColorHex().startsWith("#") ? c.defaultColorHex().substring(1) : c.defaultColorHex();
            try {
                int value = Integer.parseInt(hex, 16);
                rgb = List.of((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            } catch (NumberFormatException e) {
                ctx.drop(Dyeable.NAME, "default_color " + c.defaultColorHex() + " is not a hex color");
            }
        } else if (c.defaultColor() != null && c.defaultColor().size() == 3) {
            rgb = c.defaultColor().stream().map(v -> Math.max(0, Math.min(255, v))).toList();
        }
        out.put("minecraft:dyeable.default_color", Wire.ints(rgb));
    }

    // ---- destructible_by_mining --------------------------------------------------------------------------------------

    /**
     * {@code minecraft:destructible_by_mining}: {@code value} is seconds_to_destroy, -1 for the false form; item specific
     * speeds in the legacy item descriptor shape, only when there are any.
     */
    public static void destructibleByMining(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull DestructibleByMining c) {
        float value = Boolean.FALSE.equals(c.enabled()) ? -1f : Boolean.TRUE.equals(c.enabled()) ? 0f : c.secondsToDestroy();
        out.put("minecraft:destructible_by_mining.value", value);
        if (c.itemSpecificSpeeds() != null && !c.itemSpecificSpeeds().isEmpty()) {
            out.list("minecraft:destructible_by_mining.item_specific_speeds");
            for (DestructibleByMining.ItemSpecificSpeeds speed : c.itemSpecificSpeeds()) {
                WireNode e = out.element("minecraft:destructible_by_mining.item_specific_speeds");
                e.put("destroy_speed", speed.destroySpeed());
                e.put("item", NbtMap.builder().putString("Name", Wire.namespaced(speed.item())).putShort("Aux", (short) 32767).build());
            }
        }
    }


    // ---- selection_box -----------------------------------------------------------------------------------------------

    /**
     * {@code minecraft:selection_box}: {@code enabled} and the box as origin/size in JSON units; the false form keeps the
     * full cube.
     */
    public static void selectionBox(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull SelectionBox c) {
        SelectionBox.Vec3 origin = c.origin() == null ? new SelectionBox.Vec3(-8, 0, -8) : c.origin();
        SelectionBox.Vec3 size = c.size() == null ? new SelectionBox.Vec3(16, 16, 16) : c.size();
        out.put("minecraft:selection_box.enabled", Wire.bool(c.enabled() == null || c.enabled()));
        out.put("minecraft:selection_box.origin", Wire.floats(List.of(origin.x(), origin.y(), origin.z())));
        out.put("minecraft:selection_box.size", Wire.floats(List.of(size.x(), size.y(), size.z())));
    }

    // ---- use_animation_id --------------------------------------------------------------------------------------------

    private static final Map<String, Integer> USE_ANIMATIONS = Map.of("none", 0, "eat", 1, "drink", 2, "block", 3, "bow", 4,
        "camera", 5, "spear", 6, "crossbow", 9, "spyglass", 10, "brush", 12);

    /**
     * {@code minecraft:use_animation}: the component keeps the name, {@code item_properties.use_animation} numbers it
     * (eat 1 and spear 6 captured from BDS 1.26.63.1, the others as vanilla items send them); unknown names are 0.
     */
    public static void useAnimationId(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull UseAnimation c) {
        String value = c.value() == null ? "none" : c.value();
        out.put("minecraft:use_animation.value", value);
        out.put("item_properties.use_animation", USE_ANIMATIONS.getOrDefault(value, 0));
    }

    // ---- permutation_components --------------------------------------------------------------------------------------

    /**
     * One entry of {@code permutations}: its components compiled with each component's own rules; a permutation without
     * components is not sent at all (its condition with it).
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
        entry.put("condition", p.condition());
    }
}
