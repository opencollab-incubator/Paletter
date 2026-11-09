package org.geysermc.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.property.Properties;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Behaviour values read from the component data BDS sends (or Paletter sends for custom content): one rule per key.
 * The same rules read vanilla wire data and custom components, so the tests check them against the vanilla dumps on
 * the data driven blocks that carry both.
 */
@Internal
public final class WireRules {
    private static final Map<BehaviourKey<?>, Function<Properties, Object>> BLOCK = Map.of(
        BlockBehaviour.DESTROY_TIME, p -> floatOf(p.component("minecraft:destructible_by_mining"), "value"),
        // Engine friction is 1 - the component value (component 0.4 = the default 0.6). No vanilla block sends
        // minecraft:friction, so this rule is not checked against a dump yet (needs a native capture of a custom block)
        BlockBehaviour.FRICTION, p -> {
            Float value = floatOf(p.component("minecraft:friction"), "value");
            return value == null ? null : 1 - value;
        },
        BlockBehaviour.LIGHT_EMISSION, p -> intOf(p.component("minecraft:light_emission"), "emission"),
        BlockBehaviour.LIGHT_DAMPENING, p -> intOf(p.component("minecraft:light_dampening"), "lightLevel"),
        BlockBehaviour.COLLISION_SHAPE, p -> transformed(collision(p.component("minecraft:collision_box")), p),
        BlockBehaviour.OUTLINE_SHAPE, p -> transformed(selection(p.component("minecraft:selection_box")), p)
    );
    private static final Map<BehaviourKey<?>, Function<Properties, Object>> ITEM = Map.of(
        ItemBehaviour.MAX_STACK_SIZE, p -> intOf(p.component("item_properties"), "max_stack_size"),
        ItemBehaviour.ATTACK_DAMAGE, p -> intOf(p.component("item_properties"), "damage"),
        ItemBehaviour.USE_DURATION, p -> intOf(p.component("item_properties"), "use_duration"),
        ItemBehaviour.USE_ANIMATION, p -> intOf(p.component("item_properties"), "use_animation"),
        ItemBehaviour.MAX_DAMAGE, p -> intOf(p.component("minecraft:durability"), "max_durability")
    );

    private WireRules() {
    }

    /**
     * @param properties the properties of a block state (permutations applied) or an item
     * @return the value, or null if the component data does not say
     */
    public static <T> @Nullable T value(@NonNull BehaviourKey<T> key, @NonNull Properties properties) {
        Function<Properties, Object> rule = (key.owner() == BehaviourKey.Owner.BLOCK ? BLOCK : ITEM).get(key);
        return rule == null ? null : key.type().cast(rule.apply(properties));
    }

    private static @Nullable Float floatOf(@Nullable NbtMap map, String field) {
        return map != null && map.get(field) instanceof Number number ? number.floatValue() : null;
    }

    private static @Nullable Integer intOf(@Nullable NbtMap map, String field) {
        return map != null && map.get(field) instanceof Number number ? number.intValue() : null;
    }

    /** {@code {enabled, boxes: [{minX..maxZ}]}} in pixels (0 to 16). */
    private static @Nullable BlockShape collision(@Nullable NbtMap box) {
        if (box == null) {
            return null;
        }
        if (box.getByte("enabled", (byte) 1) == 0) {
            return BlockShape.EMPTY;
        }
        if (!box.containsKey("boxes")) {
            return pixels(box);
        }
        List<NbtMap> boxes = box.getList("boxes", NbtType.COMPOUND);
        float[] coordinates = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            NbtMap b = boxes.get(i);
            float[] one = {b.getFloat("minX"), b.getFloat("minY"), b.getFloat("minZ"), b.getFloat("maxX"), b.getFloat("maxY"), b.getFloat("maxZ")};
            for (int j = 0; j < 6; j++) {
                coordinates[i * 6 + j] = one[j] / 16f;
            }
        }
        return BlockShape.of(coordinates);
    }

    /** {@code {enabled, origin: [x, y, z], size: [x, y, z]}}: origin -8 to 8 in x and z, 0 to 16 in y. */
    private static @Nullable BlockShape selection(@Nullable NbtMap box) {
        if (box == null) {
            return null;
        }
        return box.getByte("enabled", (byte) 1) == 0 ? BlockShape.EMPTY : pixels(box);
    }

    /**
     * Applies {@code minecraft:transformation} (the boxes are given before it): quarter turns around y about the block
     * centre ({@code RY}, checked against the dumps on shelf_mushroom). Other transformations (x or z rotation, scale,
     * translation) have no vanilla block to check a rule against, so the shape is unknown (null) then.
     */
    private static @Nullable BlockShape transformed(@Nullable BlockShape shape, Properties properties) {
        NbtMap transformation = properties.component("minecraft:transformation");
        if (shape == null || transformation == null) {
            return shape;
        }
        for (String identity : List.of("RX", "RZ", "TX", "TY", "TZ", "RXP", "RYP", "RZP", "SXP", "SYP", "SZP")) {
            if (transformation.get(identity) instanceof Number number && number.floatValue() != 0) {
                return null;
            }
        }
        for (String scale : List.of("SX", "SY", "SZ")) {
            if (transformation.get(scale) instanceof Number number && number.floatValue() != 1) {
                return null;
            }
        }
        int turns = Math.floorMod(transformation.getInt("RY", 0), 4);
        float[] coordinates = shape.coordinates();
        for (int turn = 0; turn < turns; turn++) {
            for (int at = 0; at < coordinates.length; at += 6) {
                // (x, z) -> (z, 1 - x), as a box: min z from max x
                float minZ = 1 - coordinates[at + 3];
                float maxZ = 1 - coordinates[at];
                coordinates[at] = coordinates[at + 2];
                coordinates[at + 3] = coordinates[at + 5];
                coordinates[at + 2] = minZ;
                coordinates[at + 5] = maxZ;
            }
        }
        return BlockShape.of(coordinates);
    }

    private static BlockShape pixels(NbtMap box) {
        List<Float> origin = box.getList("origin", NbtType.FLOAT);
        List<Float> size = box.getList("size", NbtType.FLOAT);
        float x = origin.get(0) + 8;
        float y = origin.get(1);
        float z = origin.get(2) + 8;
        return BlockShape.of(x / 16f, y / 16f, z / 16f, (x + size.get(0)) / 16f, (y + size.get(1)) / 16f, (z + size.get(2)) / 16f);
    }
}
