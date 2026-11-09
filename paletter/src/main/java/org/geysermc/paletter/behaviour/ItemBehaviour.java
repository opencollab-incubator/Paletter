package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The behaviour keys of item types. Values are per item type (not per aux value) and per data version.
 *
 * @since 0.1.0
 */
public final class ItemBehaviour {
    public static final BehaviourKey<Integer> MAX_STACK_SIZE = key("max_stack_size", Integer.class);
    /** Durability; 0 for items that take no damage. */
    public static final BehaviourKey<Integer> MAX_DAMAGE = key("max_damage", Integer.class);
    public static final BehaviourKey<Integer> ATTACK_DAMAGE = key("attack_damage", Integer.class);
    public static final BehaviourKey<Integer> ARMOR_VALUE = key("armor_value", Integer.class);
    public static final BehaviourKey<Integer> TOUGHNESS = key("toughness", Integer.class);
    public static final BehaviourKey<Integer> ENCHANT_VALUE = key("enchant_value", Integer.class);
    /** Ticks the item burns in a furnace; 0 if it is no fuel. */
    public static final BehaviourKey<Integer> FURNACE_BURN_DURATION = key("furnace_burn_duration", Integer.class);
    public static final BehaviourKey<Float> FURNACE_XP_MULTIPLIER = key("furnace_xp_multiplier", Float.class);
    /**
     * Ticks of using the item (eating, drinking, ...). Known for component based items (from what BDS sends); null for
     * items whose use is native code (bow, trident, potion) until the native capture exists.
     */
    public static final BehaviourKey<Integer> USE_DURATION = key("use_duration", Integer.class);
    /** The use animation id BDS sends ({@code item_properties.use_animation}: 1 eat, 2 drink, 6 spear, ...). */
    public static final BehaviourKey<Integer> USE_ANIMATION = key("use_animation", Integer.class);

    private static final List<BehaviourKey<?>> KEYS = List.of(MAX_STACK_SIZE, MAX_DAMAGE, ATTACK_DAMAGE, ARMOR_VALUE,
        TOUGHNESS, ENCHANT_VALUE, FURNACE_BURN_DURATION, FURNACE_XP_MULTIPLIER, USE_DURATION, USE_ANIMATION);

    private ItemBehaviour() {
    }

    private static <T> BehaviourKey<T> key(String name, Class<T> type) {
        return new BehaviourKey<>(name, BehaviourKey.Owner.ITEM, type);
    }

    /**
     * @return every item key
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
