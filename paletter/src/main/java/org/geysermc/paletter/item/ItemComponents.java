package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.component.ComponentType;
import org.geysermc.paletter.component.ItemComponent;
import org.geysermc.paletter.component.item.BlockPlacer;
import org.geysermc.paletter.component.item.Compostable;
import org.geysermc.paletter.component.item.Cooldown;
import org.geysermc.paletter.component.item.Damage;
import org.geysermc.paletter.component.item.DisplayName;
import org.geysermc.paletter.component.item.Durability;
import org.geysermc.paletter.component.item.Fuel;
import org.geysermc.paletter.component.item.Glint;
import org.geysermc.paletter.component.item.ItemProperties;
import org.geysermc.paletter.component.item.MaxStackSize;
import org.geysermc.paletter.component.item.Projectile;
import org.geysermc.paletter.component.item.Tags;
import org.geysermc.paletter.component.item.ThrowableComponent;
import org.geysermc.paletter.component.item.Wearable;
import org.geysermc.paletter.creative.CreativePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data driven item components. Immutable; build with {@link #builder()}.
 * <p>
 * Bedrock splits item data into components ({@code minecraft:*}) and {@code item_properties}, and a few values
 * have to be sent in both places in different types. Typed components (generated from captured network NBT, see
 * {@code org.geysermc.paletter.component.item}) are added with {@link Builder#with}; the convenience methods also fill
 * in the mirrored item properties. Anything without a generated type can be set as raw NBT with
 * {@link Builder#component}. The item's name, runtime id and creative category are filled in by the palette.
 */
public final class ItemComponents {
    public static final ItemComponents EMPTY = new ItemComponents(Map.of(), Map.of(), Set.of());
    private static final String ITEM_TAGS = "item_tags";

    private final Map<String, Object> components;
    private final Map<String, Object> properties;
    private final Set<String> tags;

    private ItemComponents(Map<String, Object> components, Map<String, Object> properties, Set<String> tags) {
        this.components = components;
        this.properties = properties;
        this.tags = tags;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * Reads existing component data, e.g. a vanilla item's, so it can be modified. Tags are read from both
     * {@code item_tags} and {@code minecraft:tags}.
     *
     * @param componentData data as returned by {@link #toComponentData}, or just its {@code components} compound
     */
    public static @NonNull ItemComponents fromComponentData(@NonNull NbtMap componentData) {
        NbtMap componentsNbt = componentData.containsKey("components") ? componentData.getCompound("components") : componentData;
        Builder builder = new Builder();
        componentsNbt.forEach((key, value) -> {
            if (key.equals("item_properties")) {
                builder.properties.putAll((NbtMap) value);
            } else if (key.equals(ITEM_TAGS)) {
                ((List<?>) value).forEach(tag -> builder.tags.add((String) tag));
            } else if (key.equals(Tags.TYPE.name()) && value instanceof NbtMap map) {
                builder.tags.addAll(Tags.fromNbt(map).tags());
            } else {
                builder.components.put(key, value);
            }
        });
        return builder.build();
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.components.putAll(components);
        builder.properties.putAll(properties);
        builder.tags.addAll(tags);
        return builder;
    }

    /**
     * @return the raw NBT of a component, or null if not set. Tags are not a component here, see {@link #tags()}
     */
    public @Nullable Object component(@NonNull String name) {
        return components.get(name);
    }

    /**
     * @return the component read as its generated type, or null if not set
     */
    public <T extends ItemComponent> @Nullable T get(@NonNull ComponentType<T> type) {
        if (type == Tags.TYPE) {
            return tags.isEmpty() ? null : type.read(Tags.of(List.copyOf(tags)).toNbt());
        }
        return components.get(type.name()) instanceof NbtMap nbt ? type.read(nbt) : null;
    }

    public @Nullable Object property(@NonNull String name) {
        return properties.get(name);
    }

    /**
     * @return the item properties set so far, typed. The creative category and group are added by the palette
     */
    public @NonNull ItemProperties itemProperties() {
        return ItemProperties.fromNbt(NbtMap.fromMap(properties));
    }

    public @NonNull Set<String> tags() {
        return tags;
    }

    /**
     * @return the full component data of a data driven item definition, as sent in the item component packet
     */
    public @NonNull NbtMap toComponentData(@NonNull String identifier, int runtimeId, @Nullable CreativePlacement creative) {
        NbtMapBuilder itemProperties = NbtMap.builder();
        itemProperties.putAll(properties);
        if (creative != null) {
            itemProperties.putInt("creative_category", creative.category().itemPropertyId());
            if (creative.group() != null) {
                itemProperties.putString("creative_group", creative.group());
            }
        }

        NbtMapBuilder componentsNbt = NbtMap.builder();
        componentsNbt.putAll(components);
        if (!tags.isEmpty()) {
            // Vanilla sends tags twice: as the bare item_tags list and as the minecraft:tags component
            List<String> tagList = new ArrayList<>(tags);
            componentsNbt.putList(ITEM_TAGS, NbtType.STRING, tagList);
            componentsNbt.putCompound(Tags.TYPE.name(), Tags.of(tagList).toNbt());
        }
        componentsNbt.putCompound("item_properties", itemProperties.build());

        return NbtMap.builder()
            .putString("name", identifier)
            .putInt("id", runtimeId)
            .putCompound("components", componentsNbt.build())
            .build();
    }

    public static final class Builder {
        private final Map<String, Object> components = new LinkedHashMap<>();
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();

        private Builder() {
        }

        /**
         * Sets a typed component, replacing any previous value. {@link Tags} are added to the item's tags.
         * Mirrored item properties are not touched; use {@link #properties(ItemProperties)} for those.
         */
        public @NonNull Builder with(@NonNull ItemComponent component) {
            if (component instanceof Tags tagComponent) {
                tags.addAll(tagComponent.tags());
                return this;
            }
            components.put(component.componentType().name(), component.toNbt());
            return this;
        }

        /**
         * Sets a raw component, replacing any previous value. The escape hatch for components without a
         * generated type; see {@code codegen/needs-capture.txt}.
         */
        public @NonNull Builder component(@NonNull String name, @NonNull NbtMap value) {
            components.put(name, value);
            return this;
        }

        public @NonNull Builder removeComponent(@NonNull String name) {
            components.remove(name);
            return this;
        }

        /**
         * Sets a raw item property, replacing any previous value.
         */
        public @NonNull Builder property(@NonNull String name, @NonNull Object value) {
            // NBT has no boolean type; Bedrock reads bytes
            properties.put(name, value instanceof Boolean bool ? (byte) (bool ? 1 : 0) : value);
            return this;
        }

        /**
         * Sets every non-null item property of {@code itemProperties}, keeping the others.
         */
        public @NonNull Builder properties(@NonNull ItemProperties itemProperties) {
            properties.putAll(itemProperties.toNbt());
            return this;
        }

        public @NonNull Builder icon(@NonNull String texture) {
            return icon(Map.of("default", texture));
        }

        /**
         * @param textures texture slot to texture, e.g. {@code default} and {@code dyed}
         */
        public @NonNull Builder icon(@NonNull Map<String, String> textures) {
            return property("minecraft:icon", new ItemProperties.Icon(textures).toNbt());
        }

        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return with(DisplayName.of(translationKeyOrName));
        }

        /**
         * Sets the stack size. Sent both as a property and as a component: without the component,
         * {@code minecraft:wearable} resets the stack size to one.
         */
        public @NonNull Builder maxStackSize(int size) {
            property("max_stack_size", size);
            return with(MaxStackSize.of((byte) size));
        }

        public @NonNull Builder allowOffHand(boolean allow) {
            return property("allow_off_hand", allow);
        }

        public @NonNull Builder handEquipped(boolean handEquipped) {
            return property("hand_equipped", handEquipped);
        }

        /**
         * Sets the durability, with a damage chance of {@code min = max = 1} as Geyser sends it.
         */
        public @NonNull Builder durability(int maxDurability) {
            return durability(maxDurability, 1, 1);
        }

        public @NonNull Builder durability(int maxDurability, int minDamageChance, int maxDamageChance) {
            return with(Durability.builder()
                .damageChance(new Durability.DamageChance(maxDamageChance, minDamageChance))
                .maxDurability(maxDurability)
                .build());
        }

        /**
         * Sets the attack damage, as the {@code damage} property and the {@code minecraft:damage} component
         * (a short on the network).
         */
        public @NonNull Builder attackDamage(int damage) {
            property("damage", damage);
            return with(Damage.of((short) damage));
        }

        /**
         * Sets the enchantment glint, as the {@code minecraft:glint} component and the {@code foil} property.
         */
        public @NonNull Builder glint(boolean glint) {
            property("foil", glint);
            return with(Glint.of(glint));
        }

        /**
         * Makes the item throwable. Also adds a projectile component, without which the swing animation does not play;
         * the projectile entity is irrelevant when the server spawns the real one.
         */
        public @NonNull Builder throwable(boolean swingAnimation) {
            with(ThrowableComponent.builder().doSwingAnimation(swingAnimation).build());
            return projectile("minecraft:snowball");
        }

        public @NonNull Builder projectile(@NonNull String entity) {
            return with(Projectile.builder().projectileEntity(entity).build());
        }

        /**
         * @param slot the armor slot without prefix, e.g. {@code armor.chest}
         */
        public @NonNull Builder wearable(@NonNull String slot, int protection) {
            return with(new Wearable(protection, slot.startsWith("slot.") ? slot : "slot." + slot));
        }

        /**
         * @param useBlockAsIcon render the placed block as icon instead of the item's icon
         */
        public @NonNull Builder blockPlacer(@NonNull String block, boolean useBlockAsIcon) {
            return with(BlockPlacer.builder()
                .block(block)
                .canUseBlockAsIcon(useBlockAsIcon)
                .useOn(new NbtList<>(NbtType.STRING, Collections.emptyList()))
                .build());
        }

        public @NonNull Builder cooldown(@NonNull String category, float seconds) {
            return with(Cooldown.builder().category(category).duration(seconds).build());
        }

        public @NonNull Builder fuel(float duration) {
            return with(Fuel.of(duration));
        }

        /**
         * @param chance the composting chance in percent; a byte on the network
         */
        public @NonNull Builder compostable(int chance) {
            return with(Compostable.of((byte) chance));
        }

        /**
         * Adds an item tag, queryable with Molang and usable in recipes.
         */
        public @NonNull Builder tag(@NonNull String tag) {
            tags.add(tag);
            return this;
        }

        public @NonNull ItemComponents build() {
            return new ItemComponents(Collections.unmodifiableMap(new LinkedHashMap<>(components)),
                Collections.unmodifiableMap(new LinkedHashMap<>(properties)),
                Collections.unmodifiableSet(new LinkedHashSet<>(tags)));
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemComponents other && components.equals(other.components)
            && properties.equals(other.properties) && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * components.hashCode() + properties.hashCode()) + tags.hashCode();
    }
}
