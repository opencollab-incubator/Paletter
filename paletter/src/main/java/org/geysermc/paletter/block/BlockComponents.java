package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.block.component.Box;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.Material;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.Transformation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a data driven block (or one of its permutations) looks like and does, in Bedrock terms. Immutable; build with
 * {@link #builder()}.
 * <pre>{@code
 * BlockComponents.builder()
 *     .geometry(Geometry.of(Geometry.FULL_BLOCK))
 *     .materials(Map.of(Material.ALL_FACES, Material.of("my_block").faceDimming(false)))
 *     .placementFilter(PlacementFilter.allowOn(Face.UP).blocks("minecraft:grass_block"))
 *     .lightEmission(10)
 *     .tags("minecraft:stone")
 *     .build()
 * }</pre>
 * <p>
 * The values are semantic, in the units of add-on JSON (pixels relative to the bottom center, degrees, seconds): Paletter
 * encodes them into the network format of each palette's Bedrock version when the palette is compiled. A value a
 * palette's version cannot express is left out of that palette with a warning in its
 * {@link org.geysermc.paletter.PaletteReport}. Components without a semantic method can be set as raw NBT with the
 * {@link Experimental} {@link Builder#raw}.
 *
 * @since 2.0.0
 */
public final class BlockComponents {
    public static final BlockComponents EMPTY = new BlockComponents(new EnumMap<>(Key.class), Map.of(), Set.of());

    private enum Key {
        DISPLAY_NAME, SELECTION_BOX, COLLISION_BOXES, GEOMETRY, MATERIALS, MATERIAL_ALIASES, PLACEMENT_FILTER, DESTRUCTIBLE_BY_MINING,
        FRICTION, LIGHT_EMISSION, LIGHT_DAMPENING, TRANSFORMATION, ON_PLAYER_PLACING
    }

    private final EnumMap<Key, Object> values;
    private final Map<String, NbtMap> raw;
    private final Set<String> tags;

    private BlockComponents(EnumMap<Key, Object> values, Map<String, NbtMap> raw, Set<String> tags) {
        this.values = values;
        this.raw = raw;
        this.tags = tags;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.values.putAll(values);
        builder.raw.putAll(raw);
        builder.tags.addAll(tags);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T get(Key key) {
        return (T) values.get(key);
    }

    public @Nullable String displayName() {
        return get(Key.DISPLAY_NAME);
    }

    /**
     * @return the selection box; an empty box means the block cannot be selected
     */
    public @Nullable Box selectionBox() {
        return get(Key.SELECTION_BOX);
    }

    /**
     * @return the collision boxes; empty means no collision
     */
    public @Nullable List<Box> collisionBoxes() {
        return get(Key.COLLISION_BOXES);
    }

    public @Nullable Geometry geometry() {
        return get(Key.GEOMETRY);
    }

    /**
     * @return material instance name to material
     */
    public @Nullable Map<String, Material> materials() {
        return get(Key.MATERIALS);
    }

    /**
     * @return face to the material instance it uses (add-on JSON {@code "up": "north"}); empty without aliases
     */
    public @NonNull Map<String, String> materialAliases() {
        Map<String, String> aliases = get(Key.MATERIAL_ALIASES);
        return aliases == null ? Map.of() : aliases;
    }

    /**
     * @return the placement conditions; the block can be placed if any holds
     */
    public @Nullable List<PlacementFilter> placementFilter() {
        return get(Key.PLACEMENT_FILTER);
    }

    /**
     * @return the seconds to break the block by hand, or -1 if it cannot be mined
     */
    public @Nullable Float destructibleByMining() {
        return get(Key.DESTRUCTIBLE_BY_MINING);
    }

    public @Nullable Float friction() {
        return get(Key.FRICTION);
    }

    public @Nullable Integer lightEmission() {
        return get(Key.LIGHT_EMISSION);
    }

    public @Nullable Integer lightDampening() {
        return get(Key.LIGHT_DAMPENING);
    }

    public @Nullable Transformation transformation() {
        return get(Key.TRANSFORMATION);
    }

    @Experimental
    public @Nullable String onPlayerPlacing() {
        return get(Key.ON_PLAYER_PLACING);
    }

    /**
     * @return the block tags, queryable with Molang
     */
    public @NonNull Set<String> tags() {
        return tags;
    }

    @Experimental
    public @NonNull Map<String, NbtMap> raw() {
        return raw;
    }

    /**
     * @since 2.0.0
     */
    public static final class Builder {
        private final EnumMap<Key, Object> values = new EnumMap<>(Key.class);
        private final Map<String, NbtMap> raw = new LinkedHashMap<>();
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
         * @param translationKeyOrName a translation key, or the name itself
         */
        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return set(Key.DISPLAY_NAME, Objects.requireNonNull(translationKeyOrName));
        }

        /**
         * @param box the selection box; {@link Box#EMPTY} makes the block unselectable
         */
        public @NonNull Builder selectionBox(@NonNull Box box) {
            return set(Key.SELECTION_BOX, Objects.requireNonNull(box, "box"));
        }

        /**
         * Sets the collision boxes. Empty boxes are skipped; with no non-empty box, the block has no collision.
         */
        public @NonNull Builder collisionBoxes(@NonNull Box... boxes) {
            List<Box> nonEmpty = new ArrayList<>();
            for (Box box : boxes) {
                if (!box.isEmpty()) {
                    nonEmpty.add(box);
                }
            }
            return set(Key.COLLISION_BOXES, List.copyOf(nonEmpty));
        }

        public @NonNull Builder geometry(@NonNull Geometry geometry) {
            return set(Key.GEOMETRY, Objects.requireNonNull(geometry, "geometry"));
        }

        /**
         * @param identifier the geometry identifier, e.g. {@link Geometry#FULL_BLOCK}
         */
        public @NonNull Builder geometry(@NonNull String identifier) {
            return geometry(Geometry.of(identifier));
        }

        /**
         * @param materials material instance name ({@link Material#ALL_FACES}, a face like {@code up}, or a name the
         *                  geometry uses) to material
         */
        public @NonNull Builder materials(@NonNull Map<String, Material> materials) {
            return materials(materials, Map.of());
        }

        /**
         * @param materials material instance name to material
         * @param aliases face to the material instance it uses, e.g. {@code up -> north} (add-on JSON {@code "up": "north"})
         */
        public @NonNull Builder materials(@NonNull Map<String, Material> materials, @NonNull Map<String, String> aliases) {
            set(Key.MATERIALS, Collections.unmodifiableMap(new LinkedHashMap<>(materials)));
            return set(Key.MATERIAL_ALIASES, Collections.unmodifiableMap(new LinkedHashMap<>(aliases)));
        }

        /**
         * @param conditions the conditions; the block can be placed if any holds
         */
        public @NonNull Builder placementFilter(@NonNull PlacementFilter... conditions) {
            if (conditions.length == 0) {
                throw new IllegalArgumentException("A placement filter needs at least one condition");
            }
            return set(Key.PLACEMENT_FILTER, List.of(conditions));
        }

        /**
         * @param seconds the time to break the block by hand; -1 makes it unbreakable by mining
         */
        public @NonNull Builder destructibleByMining(float seconds) {
            return set(Key.DESTRUCTIBLE_BY_MINING, seconds);
        }

        /**
         * @param friction 0 to 0.9; vanilla blocks use 0.4, ice 0.02
         */
        public @NonNull Builder friction(float friction) {
            return set(Key.FRICTION, friction);
        }

        /**
         * @param level the light level the block emits, 0 to 15
         */
        public @NonNull Builder lightEmission(int level) {
            return set(Key.LIGHT_EMISSION, lightLevel(level));
        }

        /**
         * @param level how much light the block absorbs, 0 to 15
         */
        public @NonNull Builder lightDampening(int level) {
            return set(Key.LIGHT_DAMPENING, lightLevel(level));
        }

        public @NonNull Builder transformation(@NonNull Transformation transformation) {
            return set(Key.TRANSFORMATION, Objects.requireNonNull(transformation, "transformation"));
        }

        /**
         * @param x rotation in degrees; Bedrock only supports multiples of 90
         */
        public @NonNull Builder rotation(float x, float y, float z) {
            return transformation(Transformation.rotation(x, y, z));
        }

        /**
         * Makes the client fire the given event instead of placing anything itself. Useful when the server decides what
         * is placed, as it avoids the client briefly placing the block twice.
         * <p>
         * Experimental: 1.26 add-on JSON rejects this component (it was replaced by script custom components), so its
         * 1.26 network form is only known from Geyser's production use.
         */
        @Experimental
        public @NonNull Builder onPlayerPlacing(@NonNull String eventName) {
            return set(Key.ON_PLAYER_PLACING, Objects.requireNonNull(eventName, "eventName"));
        }

        /**
         * Adds block tags, queryable with Molang. Tags belong to the block, not to a permutation: tags set in a
         * permutation's components cannot be sent, and are reported and left out.
         */
        public @NonNull Builder tags(@NonNull String... tags) {
            for (String tag : tags) {
                this.tags.add(Objects.requireNonNull(tag, "tag"));
            }
            return this;
        }

        public @NonNull Builder tag(@NonNull String tag) {
            return tags(tag);
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

        public @NonNull BlockComponents build() {
            return new BlockComponents(new EnumMap<>(values), Collections.unmodifiableMap(new LinkedHashMap<>(raw)),
                Collections.unmodifiableSet(new LinkedHashSet<>(tags)));
        }

        private static int lightLevel(int level) {
            if (level < 0 || level > 15) {
                throw new IllegalArgumentException("Light levels are 0 to 15: " + level);
            }
            return level;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockComponents other && values.equals(other.values) && raw.equals(other.raw) && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, raw, tags);
    }

    @Override
    public String toString() {
        return "BlockComponents" + values + (tags.isEmpty() ? "" : " tags " + tags) + (raw.isEmpty() ? "" : " raw " + raw.keySet());
    }
}
