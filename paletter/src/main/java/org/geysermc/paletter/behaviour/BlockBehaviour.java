package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The behaviour keys of block states. Values are per state and per data version; where they come from is in
 * {@link Behaviour#provenance}. Units are those of Bedrock Dedicated Server's engine, not of add-on components (custom
 * blocks are converted, see {@link Behaviour}).
 *
 * @since 0.1.0
 */
public final class BlockBehaviour {
    /** Destroy time ("hardness") in seconds with bare hands before tool and effect factors; -1 for unbreakable blocks. */
    public static final BehaviourKey<Float> DESTROY_TIME = key("destroy_time", Float.class);
    public static final BehaviourKey<Float> EXPLOSION_RESISTANCE = key("explosion_resistance", Float.class);
    /** Friction of an entity standing on the block: 0.6 for most blocks, 0.98 ice, 0.989 blue ice. */
    public static final BehaviourKey<Float> FRICTION = key("friction", Float.class);
    public static final BehaviourKey<Boolean> SOLID = key("solid", Boolean.class);
    /** The boxes entities collide with, without neighbours or position offsets (see {@link BlockShape}). */
    public static final BehaviourKey<BlockShape> COLLISION_SHAPE = key("collision_shape", BlockShape.class);
    /** The selection outline, one box, without the random offset some plants have. */
    public static final BehaviourKey<BlockShape> OUTLINE_SHAPE = key("outline_shape", BlockShape.class);
    public static final BehaviourKey<Integer> LIGHT_EMISSION = key("light_emission", Integer.class);
    public static final BehaviourKey<Integer> LIGHT_DAMPENING = key("light_dampening", Integer.class);
    /** Whether a liquid source can share the block (waterlogging). */
    public static final BehaviourKey<Boolean> CAN_CONTAIN_LIQUID = key("can_contain_liquid", Boolean.class);
    /** What flowing liquid does to the block: {@code BLOCKING}, {@code BROKEN}, {@code POPPED}, {@code NO_REACTION}. */
    public static final BehaviourKey<String> LIQUID_REACTION = key("liquid_reaction", String.class);
    public static final BehaviourKey<Boolean> REQUIRES_CORRECT_TOOL = key("requires_correct_tool", Boolean.class);
    /** Map colour as {@code 0xRRGGBBAA}. */
    public static final BehaviourKey<Integer> MAP_COLOR = key("map_color", Integer.class);
    /** Biome tint of the texture, e.g. {@code None}, {@code Grass}, {@code Foliage}. */
    public static final BehaviourKey<String> TINT_METHOD = key("tint_method", String.class);
    /** Chance modifier of catching fire. */
    public static final BehaviourKey<Integer> FLAME_ODDS = key("flame_odds", Integer.class);
    /** Chance modifier of burning away. */
    public static final BehaviourKey<Integer> BURN_ODDS = key("burn_odds", Integer.class);
    public static final BehaviourKey<Float> TRANSLUCENCY = key("translucency", Float.class);
    public static final BehaviourKey<Float> THICKNESS = key("thickness", Float.class);
    /** Whether entities climb the block. No bundled source yet (needs the native capture): always null. */
    public static final BehaviourKey<Boolean> CLIMBABLE = key("climbable", Boolean.class);

    private static final List<BehaviourKey<?>> KEYS = List.of(DESTROY_TIME, EXPLOSION_RESISTANCE, FRICTION, SOLID,
        COLLISION_SHAPE, OUTLINE_SHAPE, LIGHT_EMISSION, LIGHT_DAMPENING, CAN_CONTAIN_LIQUID, LIQUID_REACTION,
        REQUIRES_CORRECT_TOOL, MAP_COLOR, TINT_METHOD, FLAME_ODDS, BURN_ODDS, TRANSLUCENCY, THICKNESS, CLIMBABLE);

    private BlockBehaviour() {
    }

    private static <T> BehaviourKey<T> key(String name, Class<T> type) {
        return new BehaviourKey<>(name, BehaviourKey.Owner.BLOCK, type);
    }

    /**
     * @return every block key
     */
    public static @NonNull List<BehaviourKey<?>> keys() {
        return KEYS;
    }

    /**
     * @return the key of a name, or null if there is none
     */
    public static @Nullable BehaviourKey<?> key(@NonNull String name) {
        for (BehaviourKey<?> key : KEYS) {
            if (key.name().equals(name)) {
                return key;
            }
        }
        return null;
    }
}
