package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.item.component.Color;
import org.geysermc.paletter.item.component.Cooldown;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.Enchantable;
import org.geysermc.paletter.item.component.EquipmentSlot;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.KineticWeapon;
import org.geysermc.paletter.item.component.PiercingWeapon;
import org.geysermc.paletter.item.component.Rarity;
import org.geysermc.paletter.item.component.RenderOffsets;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.Shooter;
import org.geysermc.paletter.item.component.Throwing;
import org.geysermc.paletter.item.component.UseAnimation;
import org.geysermc.paletter.item.component.UseModifiers;
import org.geysermc.paletter.item.component.Wearable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a data driven item is and does, in Bedrock terms. Immutable; build with {@link #builder()}.
 * <pre>{@code
 * ItemComponents.builder()
 *     .icon("ruby")
 *     .displayName("item.example.ruby.name")
 *     .maxStackSize(16)
 *     .durability(Durability.of(250).damageChance(20, 60))
 *     .tags("minecraft:is_tool")
 *     .build()
 * }</pre>
 * <p>
 * The values are semantic: Paletter encodes them into the network format of each palette's Bedrock version when the
 * palette is compiled, including values Bedrock sends in two places (e.g. the stack size as a component and an item
 * property, tags as {@code item_tags} and {@code minecraft:tags}). A value that a palette's version cannot express is
 * left out of that palette with a warning in its {@link org.geysermc.paletter.PaletteReport}.
 * <p>
 * Components without a semantic method can be set as raw NBT with the {@link Experimental} {@link Builder#raw}; see
 * there for what that means.
 *
 * @since 2.0.0
 */
public final class ItemComponents {
    public static final ItemComponents EMPTY = new ItemComponents(new EnumMap<>(Key.class), Map.of(), Map.of(), Set.of());

    /**
     * The semantic values, by what they set. Private: the encoder reads them through the getters.
     */
    private enum Key {
        ICON, DISPLAY_NAME, MAX_STACK_SIZE, ALLOW_OFF_HAND, HAND_EQUIPPED, GLINT, CAN_DESTROY_IN_CREATIVE, STACKED_BY_DATA,
        LIQUID_CLIPPED, SHOULD_DESPAWN, FIRE_RESISTANT, DURABILITY, ATTACK_DAMAGE, ENCHANTABLE, DYEABLE, KINETIC_WEAPON,
        PIERCING_WEAPON, SWING_DURATION, FOOD, USE_ANIMATION, USE_MODIFIERS, COOLDOWN, BLOCK_PLACER, BLOCK_PLACER_ICON, SHOOTER,
        THROWABLE, PROJECTILE, ENTITY_PLACER, COMPOSTABLE, FUEL, REPAIRABLE, DIGGER, RARITY, WEARABLE, RENDER_OFFSETS,
        MINING_SPEED, FRAME_COUNT, ARMOR_RENDER_OFFSETS
    }

    private final EnumMap<Key, Object> values;
    private final Map<String, NbtMap> raw;
    private final Map<String, Object> rawProperties;
    private final Set<String> tags;

    private ItemComponents(EnumMap<Key, Object> values, Map<String, NbtMap> raw, Map<String, Object> rawProperties, Set<String> tags) {
        this.values = values;
        this.raw = raw;
        this.rawProperties = rawProperties;
        this.tags = tags;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.values.putAll(values);
        builder.raw.putAll(raw);
        builder.rawProperties.putAll(rawProperties);
        builder.tags.addAll(tags);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T get(Key key) {
        return (T) values.get(key);
    }

    /**
     * @return texture slot to texture short name, e.g. {@code default} and {@code dyed}; null if not set
     */
    public @Nullable Map<String, String> icon() {
        return get(Key.ICON);
    }

    public @Nullable String displayName() {
        return get(Key.DISPLAY_NAME);
    }

    public @Nullable Integer maxStackSize() {
        return get(Key.MAX_STACK_SIZE);
    }

    public @Nullable Boolean allowOffHand() {
        return get(Key.ALLOW_OFF_HAND);
    }

    public @Nullable Boolean handEquipped() {
        return get(Key.HAND_EQUIPPED);
    }

    public @Nullable Boolean glint() {
        return get(Key.GLINT);
    }

    public @Nullable Boolean canDestroyInCreative() {
        return get(Key.CAN_DESTROY_IN_CREATIVE);
    }

    public @Nullable Boolean stackedByData() {
        return get(Key.STACKED_BY_DATA);
    }

    public @Nullable Boolean liquidClipped() {
        return get(Key.LIQUID_CLIPPED);
    }

    public @Nullable Boolean shouldDespawn() {
        return get(Key.SHOULD_DESPAWN);
    }

    public @Nullable Boolean fireResistant() {
        return get(Key.FIRE_RESISTANT);
    }

    public @Nullable Durability durability() {
        return get(Key.DURABILITY);
    }

    public @Nullable Integer attackDamage() {
        return get(Key.ATTACK_DAMAGE);
    }

    public @Nullable Enchantable enchantable() {
        return get(Key.ENCHANTABLE);
    }

    /**
     * @return the default colour of a dyeable item, or null if the item is not dyeable
     */
    public @Nullable Color dyeable() {
        return get(Key.DYEABLE);
    }

    public @Nullable KineticWeapon kineticWeapon() {
        return get(Key.KINETIC_WEAPON);
    }

    public @Nullable PiercingWeapon piercingWeapon() {
        return get(Key.PIERCING_WEAPON);
    }

    /**
     * @return the swing duration in seconds
     */
    public @Nullable Float swingDuration() {
        return get(Key.SWING_DURATION);
    }

    public @Nullable Food food() {
        return get(Key.FOOD);
    }

    public @Nullable UseAnimation useAnimation() {
        return get(Key.USE_ANIMATION);
    }

    public @Nullable UseModifiers useModifiers() {
        return get(Key.USE_MODIFIERS);
    }

    public @Nullable Cooldown cooldown() {
        return get(Key.COOLDOWN);
    }

    /**
     * @return the block this item places, or null
     */
    public @Nullable String blockPlacer() {
        return get(Key.BLOCK_PLACER);
    }

    /**
     * @return whether the placed block is rendered as the icon; null without a block placer
     */
    public @Nullable Boolean blockPlacerUsesBlockAsIcon() {
        return get(Key.BLOCK_PLACER_ICON);
    }

    public @Nullable Shooter shooter() {
        return get(Key.SHOOTER);
    }

    public @Nullable Throwing throwable() {
        return get(Key.THROWABLE);
    }

    /**
     * @return the projectile entity, or null
     */
    public @Nullable String projectile() {
        return get(Key.PROJECTILE);
    }

    public @Nullable EntityPlacer entityPlacer() {
        return get(Key.ENTITY_PLACER);
    }

    /**
     * @return the composting chance in percent
     */
    public @Nullable Integer compostable() {
        return get(Key.COMPOSTABLE);
    }

    /**
     * @return the burn duration in seconds
     */
    public @Nullable Float fuel() {
        return get(Key.FUEL);
    }

    public @Nullable Repairable repairable() {
        return get(Key.REPAIRABLE);
    }

    public @Nullable Digger digger() {
        return get(Key.DIGGER);
    }

    public @Nullable Rarity rarity() {
        return get(Key.RARITY);
    }

    public @Nullable Wearable wearable() {
        return get(Key.WEARABLE);
    }

    @Experimental
    /**
     * @since 2.0.0
     */
    public @Nullable Float miningSpeed() {
        return get(Key.MINING_SPEED);
    }

    /**
     * @since 2.0.0
     */
    public @Nullable Integer frameCount() {
        return get(Key.FRAME_COUNT);
    }

    /**
     * @since 2.0.0
     */
    @Experimental
    public @Nullable EquipmentSlot armorRenderOffsets() {
        return get(Key.ARMOR_RENDER_OFFSETS);
    }

    public @Nullable RenderOffsets renderOffsets() {
        return get(Key.RENDER_OFFSETS);
    }

    /**
     * @return the item tags, queryable with Molang and usable in recipes
     */
    public @NonNull Set<String> tags() {
        return tags;
    }

    /**
     * @return the raw components set with {@link Builder#raw}, by name
     */
    @Experimental
    public @NonNull Map<String, NbtMap> raw() {
        return raw;
    }

    /**
     * @return the raw item properties set with {@link Builder#rawProperty}, by name
     */
    @Experimental
    public @NonNull Map<String, Object> rawProperties() {
        return rawProperties;
    }

    /**
     * @since 2.0.0
     */
    public static final class Builder {
        private final EnumMap<Key, Object> values = new EnumMap<>(Key.class);
        private final Map<String, NbtMap> raw = new LinkedHashMap<>();
        private final Map<String, Object> rawProperties = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();

        private Builder() {
        }

        private Builder set(Key key, @Nullable Object value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
            return this;
        }

        /**
         * @param texture the texture short name from {@code item_texture.json}
         */
        public @NonNull Builder icon(@NonNull String texture) {
            return icon(Map.of("default", Objects.requireNonNull(texture, "texture")));
        }

        /**
         * @param textures texture slot to texture short name, e.g. {@code default} and {@code dyed}
         */
        public @NonNull Builder icon(@NonNull Map<String, String> textures) {
            return set(Key.ICON, Collections.unmodifiableMap(new LinkedHashMap<>(textures)));
        }

        /**
         * @param translationKeyOrName a translation key, or the name itself
         */
        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return set(Key.DISPLAY_NAME, Objects.requireNonNull(translationKeyOrName));
        }

        /**
         * @param size how many items fit in one slot, 1 to 64
         */
        public @NonNull Builder maxStackSize(int size) {
            if (size < 1 || size > 64) {
                throw new IllegalArgumentException("Bedrock stack sizes are 1 to 64: " + size);
            }
            return set(Key.MAX_STACK_SIZE, size);
        }

        public @NonNull Builder allowOffHand(boolean allow) {
            return set(Key.ALLOW_OFF_HAND, allow);
        }

        /**
         * @param handEquipped whether the item is held like a tool rather than like an item
         */
        public @NonNull Builder handEquipped(boolean handEquipped) {
            return set(Key.HAND_EQUIPPED, handEquipped);
        }

        /**
         * @param glint whether the item has the enchantment glint
         */
        public @NonNull Builder glint(boolean glint) {
            return set(Key.GLINT, glint);
        }

        /**
         * @param canDestroy whether holding the item breaks blocks in creative mode
         */
        public @NonNull Builder canDestroyInCreative(boolean canDestroy) {
            return set(Key.CAN_DESTROY_IN_CREATIVE, canDestroy);
        }

        /**
         * @param stackedByData whether stacks with different data values stay apart
         */
        public @NonNull Builder stackedByData(boolean stackedByData) {
            return set(Key.STACKED_BY_DATA, stackedByData);
        }

        /**
         * @param liquidClipped whether using the item interacts with liquids, like a bucket
         */
        public @NonNull Builder liquidClipped(boolean liquidClipped) {
            return set(Key.LIQUID_CLIPPED, liquidClipped);
        }

        public @NonNull Builder shouldDespawn(boolean shouldDespawn) {
            return set(Key.SHOULD_DESPAWN, shouldDespawn);
        }

        public @NonNull Builder fireResistant(boolean fireResistant) {
            return set(Key.FIRE_RESISTANT, fireResistant);
        }

        public @NonNull Builder durability(@Nullable Durability durability) {
            return set(Key.DURABILITY, durability);
        }

        /**
         * @param damage the attack damage, 0 to 32767
         */
        public @NonNull Builder attackDamage(int damage) {
            if (damage < 0 || damage > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Attack damage must be 0 to 32767: " + damage);
            }
            return set(Key.ATTACK_DAMAGE, damage);
        }

        public @NonNull Builder enchantable(@Nullable Enchantable enchantable) {
            return set(Key.ENCHANTABLE, enchantable);
        }

        /**
         * Makes the item dyeable.
         *
         * @param defaultColor the colour before it is dyed, or null to make it not dyeable
         */
        public @NonNull Builder dyeable(@Nullable Color defaultColor) {
            return set(Key.DYEABLE, defaultColor);
        }

        public @NonNull Builder kineticWeapon(@Nullable KineticWeapon weapon) {
            return set(Key.KINETIC_WEAPON, weapon);
        }

        public @NonNull Builder piercingWeapon(@Nullable PiercingWeapon weapon) {
            return set(Key.PIERCING_WEAPON, weapon);
        }

        /**
         * @param seconds how long the attack swing animation takes
         */
        public @NonNull Builder swingDuration(float seconds) {
            return set(Key.SWING_DURATION, seconds);
        }

        public @NonNull Builder food(@Nullable Food food) {
            return set(Key.FOOD, food);
        }

        public @NonNull Builder useAnimation(@Nullable UseAnimation animation) {
            return set(Key.USE_ANIMATION, animation);
        }

        public @NonNull Builder useModifiers(@Nullable UseModifiers modifiers) {
            return set(Key.USE_MODIFIERS, modifiers);
        }

        public @NonNull Builder cooldown(@Nullable Cooldown cooldown) {
            return set(Key.COOLDOWN, cooldown);
        }

        /**
         * @param category the cooldown category; items sharing one share their cooldown
         * @param seconds the duration
         */
        public @NonNull Builder cooldown(@NonNull String category, float seconds) {
            return cooldown(Cooldown.of(category, seconds));
        }

        /**
         * Makes the item place a block.
         *
         * @param block the block identifier
         * @param useBlockAsIcon render the placed block as icon instead of the item's icon
         */
        public @NonNull Builder blockPlacer(@NonNull String block, boolean useBlockAsIcon) {
            set(Key.BLOCK_PLACER, Objects.requireNonNull(block, "block"));
            return set(Key.BLOCK_PLACER_ICON, useBlockAsIcon);
        }

        public @NonNull Builder shooter(@Nullable Shooter shooter) {
            return set(Key.SHOOTER, shooter);
        }

        /**
         * Makes the item throwable. Also sets a projectile ({@code minecraft:snowball}) unless one is set: without one the
         * client does not play the swing animation. The entity is irrelevant when the server spawns the real one.
         */
        public @NonNull Builder throwable(@Nullable Throwing throwable) {
            return set(Key.THROWABLE, throwable);
        }

        /**
         * @param entity the projectile entity the item shoots, e.g. {@code minecraft:snowball}
         */
        public @NonNull Builder projectile(@Nullable String entity) {
            return set(Key.PROJECTILE, entity);
        }

        public @NonNull Builder entityPlacer(@Nullable EntityPlacer placer) {
            return set(Key.ENTITY_PLACER, placer);
        }

        /**
         * @param chancePercent the chance that composting the item raises the composter level, 0 to 100
         */
        public @NonNull Builder compostable(int chancePercent) {
            if (chancePercent < 0 || chancePercent > 100) {
                throw new IllegalArgumentException("Composting chance must be 0 to 100: " + chancePercent);
            }
            return set(Key.COMPOSTABLE, chancePercent);
        }

        /**
         * @param seconds how long the item burns in a furnace
         */
        public @NonNull Builder fuel(float seconds) {
            return set(Key.FUEL, seconds);
        }

        public @NonNull Builder repairable(@Nullable Repairable repairable) {
            return set(Key.REPAIRABLE, repairable);
        }

        public @NonNull Builder digger(@Nullable Digger digger) {
            return set(Key.DIGGER, digger);
        }

        public @NonNull Builder rarity(@Nullable Rarity rarity) {
            return set(Key.RARITY, rarity);
        }

        public @NonNull Builder wearable(@Nullable Wearable wearable) {
            return set(Key.WEARABLE, wearable);
        }

        /**
         * See {@link RenderOffsets} for why this is experimental.
         */
        @Experimental
        /**
         * Sets the mining speed (the {@code mining_speed} item property) the client uses for this item, as a multiplier
         * of the base speed.
         *
         * @since 2.0.0
         */
        public @NonNull Builder miningSpeed(float speed) {
            return set(Key.MINING_SPEED, speed);
        }

        /**
         * Sets the amount of animation frames of a chargeable item's icon (the {@code frame_count} item property),
         * e.g. 3 for bows and 10 for crossbows.
         *
         * @since 2.0.0
         */
        public @NonNull Builder frameCount(int frames) {
            return set(Key.FRAME_COUNT, frames);
        }

        /**
         * Uses the client's built-in render offsets of an armor piece for this item (the legacy {@code render_offsets}
         * preset: boots, leggings, chestplates or helmets), as Geyser sends for armor converted from v1 custom mappings.
         * Only head, chest, legs and feet have a preset; other slots send nothing.
         * <p>
         * Not an add-on component in 1.26, so not verified against a vanilla server, like {@link #renderOffsets}.
         *
         * @since 2.0.0
         */
        @Experimental
        public @NonNull Builder armorRenderOffsets(@Nullable EquipmentSlot slot) {
            return set(Key.ARMOR_RENDER_OFFSETS, slot);
        }

        public @NonNull Builder renderOffsets(@Nullable RenderOffsets offsets) {
            return set(Key.RENDER_OFFSETS, offsets);
        }

        /**
         * Adds item tags, queryable with Molang and usable in recipes.
         */
        public @NonNull Builder tags(@NonNull String... tags) {
            for (String tag : tags) {
                this.tags.add(Objects.requireNonNull(tag, "tag"));
            }
            return this;
        }

        /**
         * Adds an item tag.
         */
        public @NonNull Builder tag(@NonNull String tag) {
            return tags(tag);
        }

        public @NonNull Builder removeTag(@NonNull String tag) {
            tags.remove(tag);
            return this;
        }

        /**
         * Sets a component as raw network NBT, replacing any previous raw value of that name. The escape hatch for
         * components without a semantic method.
         * <p>
         * <b>You own the wire format</b>: the NBT is sent as given to every Bedrock version, and Paletter does not adapt it
         * when Bedrock changes the component's network shape. A semantic value that encodes to the same component name
         * replaces it.
         */
        @Experimental
        public @NonNull Builder raw(@NonNull String name, @NonNull NbtMap value) {
            raw.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        @Experimental
        public @NonNull Builder removeRaw(@NonNull String name) {
            raw.remove(name);
            return this;
        }

        /**
         * Sets an {@code item_properties} entry as raw network NBT. Same rules as {@link #raw}.
         *
         * @param value an NBT value; booleans are sent as bytes
         */
        @Experimental
        public @NonNull Builder rawProperty(@NonNull String name, @NonNull Object value) {
            rawProperties.put(Objects.requireNonNull(name, "name"),
                value instanceof Boolean bool ? (byte) (bool ? 1 : 0) : Objects.requireNonNull(value, "value"));
            return this;
        }

        public @NonNull ItemComponents build() {
            return new ItemComponents(new EnumMap<>(values), Collections.unmodifiableMap(new LinkedHashMap<>(raw)),
                Collections.unmodifiableMap(new LinkedHashMap<>(rawProperties)), Collections.unmodifiableSet(new LinkedHashSet<>(tags)));
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemComponents other && values.equals(other.values) && raw.equals(other.raw)
            && rawProperties.equals(other.rawProperties) && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, raw, rawProperties, tags);
    }

    @Override
    public String toString() {
        return "ItemComponents" + values + (tags.isEmpty() ? "" : " tags " + tags) + (raw.isEmpty() ? "" : " raw " + raw.keySet())
            + (rawProperties.isEmpty() ? "" : " raw properties " + rawProperties.keySet());
    }
}
