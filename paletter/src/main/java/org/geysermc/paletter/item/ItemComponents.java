package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.creative.CreativePlacement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data driven item components. Immutable; build with {@link #builder()}.
 * <p>
 * Bedrock splits item data into components ({@code minecraft:*}) and {@code item_properties}, and a few values
 * have to be sent in both places in different types. The typed methods take care of that. The item's name,
 * runtime id and creative category are filled in by the palette, since only it knows them.
 */
public final class ItemComponents {
    public static final ItemComponents EMPTY = new ItemComponents(Map.of(), Map.of(), Set.of());

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
     * Reads existing component data, e.g. a vanilla item's, so it can be modified.
     *
     * @param componentData data as returned by {@link #toComponentData}, or just its {@code components} compound
     */
    public static @NonNull ItemComponents fromComponentData(@NonNull NbtMap componentData) {
        NbtMap componentsNbt = componentData.containsKey("components") ? componentData.getCompound("components") : componentData;
        Builder builder = new Builder();
        componentsNbt.forEach((key, value) -> {
            switch (key) {
                case "item_properties" -> builder.properties.putAll((NbtMap) value);
                case "item_tags" -> ((List<?>) value).forEach(tag -> builder.tags.add((String) tag));
                default -> builder.components.put(key, value);
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

    public @Nullable Object component(@NonNull String name) {
        return components.get(name);
    }

    public @Nullable Object property(@NonNull String name) {
        return properties.get(name);
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
            componentsNbt.putList("item_tags", NbtType.STRING, new ArrayList<>(tags));
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
         * Sets a raw component, replacing any previous value.
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

        public @NonNull Builder icon(@NonNull String texture) {
            return icon(Map.of("default", texture));
        }

        /**
         * @param textures texture slot to texture, e.g. {@code default} and {@code dyed}
         */
        public @NonNull Builder icon(@NonNull Map<String, String> textures) {
            NbtMapBuilder texturesNbt = NbtMap.builder();
            textures.forEach(texturesNbt::putString);
            return property("minecraft:icon", NbtMap.builder().putCompound("textures", texturesNbt.build()).build());
        }

        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return component("minecraft:display_name", NbtMap.builder().putString("value", translationKeyOrName).build());
        }

        /**
         * Sets the stack size. Sent both as a property and as a component: without the component,
         * {@code minecraft:wearable} resets the stack size to one.
         */
        public @NonNull Builder maxStackSize(int size) {
            property("max_stack_size", size);
            return component("minecraft:max_stack_size", NbtMap.builder().putByte("value", (byte) size).build());
        }

        public @NonNull Builder allowOffHand(boolean allow) {
            return property("allow_off_hand", allow);
        }

        public @NonNull Builder handEquipped(boolean handEquipped) {
            return property("hand_equipped", handEquipped);
        }

        public @NonNull Builder durability(int maxDurability) {
            return component("minecraft:durability", NbtMap.builder()
                .putCompound("damage_chance", NbtMap.builder().putInt("max", 1).putInt("min", 1).build())
                .putInt("max_durability", maxDurability)
                .build());
        }

        public @NonNull Builder attackDamage(int damage) {
            property("damage", damage);
            return component("minecraft:damage", NbtMap.builder().putByte("value", (byte) damage).build());
        }

        public @NonNull Builder glint(boolean glint) {
            return component("minecraft:glint", NbtMap.builder().putBoolean("value", glint).build());
        }

        /**
         * Makes the item throwable. Also adds a projectile component, without which the swing animation does not play;
         * the projectile entity is irrelevant when the server spawns the real one.
         */
        public @NonNull Builder throwable(boolean swingAnimation) {
            component("minecraft:throwable", NbtMap.builder().putBoolean("do_swing_animation", swingAnimation).build());
            return projectile("minecraft:snowball");
        }

        public @NonNull Builder projectile(@NonNull String entity) {
            return component("minecraft:projectile", NbtMap.builder().putString("projectile_entity", entity).build());
        }

        /**
         * @param slot the armor slot without prefix, e.g. {@code armor.chest}
         */
        public @NonNull Builder wearable(@NonNull String slot, int protection) {
            return component("minecraft:wearable", NbtMap.builder()
                .putString("slot", slot.startsWith("slot.") ? slot : "slot." + slot)
                .putInt("protection", protection)
                .build());
        }

        /**
         * @param useBlockAsIcon render the placed block as icon instead of the item's icon
         */
        public @NonNull Builder blockPlacer(@NonNull String block, boolean useBlockAsIcon) {
            return component("minecraft:block_placer", NbtMap.builder()
                .putString("block", block)
                .putBoolean("canUseBlockAsIcon", useBlockAsIcon)
                .putList("use_on", NbtType.STRING, List.of())
                .build());
        }

        public @NonNull Builder cooldown(@NonNull String category, float seconds) {
            return component("minecraft:cooldown", NbtMap.builder()
                .putString("category", category)
                .putFloat("duration", seconds)
                .build());
        }

        public @NonNull Builder fuel(float duration) {
            return component("minecraft:fuel", NbtMap.builder().putFloat("duration", duration).build());
        }

        public @NonNull Builder compostable(int chance) {
            return component("minecraft:compostable", NbtMap.builder().putInt("composting_chance", chance).build());
        }

        /**
         * Adds an item tag, queryable with Molang and usable in recipes.
         */
        public @NonNull Builder tag(@NonNull String tag) {
            tags.add(tag);
            return this;
        }

        public @NonNull ItemComponents build() {
            return new ItemComponents(Map.copyOf(components), Map.copyOf(properties),
                java.util.Collections.unmodifiableSet(new LinkedHashSet<>(tags)));
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
