package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data driven block components, as sent in the start game packet. Immutable; build with {@link #builder()}.
 * <p>
 * The typed methods produce exactly the NBT the client expects (units, key names, value types), so callers
 * describe a block instead of encoding it. Anything not covered can be set as raw NBT with {@link Builder#component}.
 */
public final class BlockComponents {
    public static final BlockComponents EMPTY = new BlockComponents(NbtMap.EMPTY);

    private final NbtMap nbt;

    private BlockComponents(NbtMap nbt) {
        this.nbt = nbt;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public static @NonNull BlockComponents raw(@NonNull NbtMap nbt) {
        return new BlockComponents(nbt);
    }

    public @NonNull NbtMap toNbt() {
        return nbt;
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.components.putAll(nbt);
        return builder;
    }

    /**
     * A box in pixels, relative to the bottom center of the block (Bedrock's origin convention).
     */
    public record Box(float originX, float originY, float originZ, float sizeX, float sizeY, float sizeZ) {
        public static final Box FULL = new Box(-8, 0, -8, 16, 16, 16);
        public static final Box EMPTY = new Box(0, 0, 0, 0, 0, 0);

        public boolean isEmpty() {
            return sizeX == 0 || sizeY == 0 || sizeZ == 0;
        }
    }

    /**
     * @param texture the texture short name, or null when the resource pack's blocks.json defines it
     * @param renderMethod e.g. {@code opaque}, {@code alpha_test}, {@code blend}, or null for the default
     * @param tintMethod e.g. {@code grass}, or null for none
     * @param ambientOcclusion the ambient occlusion exponent; 1 is the default
     * @param faceDimming whether faces are dimmed by direction
     * @param isotropic whether the texture is randomly rotated
     */
    public record Material(@Nullable String texture, @Nullable String renderMethod, @Nullable String tintMethod,
                           float ambientOcclusion, boolean faceDimming, boolean isotropic) {
        public static @NonNull Material of(@NonNull String texture) {
            return new Material(texture, null, null, 1.0f, true, false);
        }

        public @NonNull Material renderMethod(@NonNull String renderMethod) {
            return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
        }

        public @NonNull Material tintMethod(@NonNull String tintMethod) {
            return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
        }
    }

    public enum Face {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;

        private final int bit = 1 << ordinal();
    }

    /**
     * One placement condition: the block may be placed on any of the faces, against any of the filters.
     *
     * @param faces the faces the block may be placed on
     * @param blocks block identifiers it may be placed against
     * @param tags Molang block tag queries it may be placed against
     */
    public record PlacementCondition(@NonNull Set<Face> faces, @NonNull List<String> blocks, @NonNull List<String> tags) {
    }

    public static final class Builder {
        private final Map<String, Object> components = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets a raw component, replacing any previous value.
         */
        public @NonNull Builder component(@NonNull String name, @NonNull NbtMap value) {
            components.put(name, value);
            return this;
        }

        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return component("minecraft:display_name", NbtMap.builder().putString("value", translationKeyOrName).build());
        }

        public @NonNull Builder selectionBox(@NonNull Box box) {
            return component("minecraft:selection_box", NbtMap.builder()
                .putBoolean("enabled", !box.isEmpty())
                .putList("origin", NbtType.FLOAT, box.originX(), box.originY(), box.originZ())
                .putList("size", NbtType.FLOAT, box.sizeX(), box.sizeY(), box.sizeZ())
                .build());
        }

        /**
         * Sets the collision boxes. Empty boxes are skipped; with no non-empty box, collision is disabled.
         */
        public @NonNull Builder collisionBoxes(@NonNull Box... boxes) {
            List<NbtMap> boxesNbt = new ArrayList<>();
            for (Box box : boxes) {
                if (box.isEmpty()) {
                    continue;
                }
                float minX = 8f + box.originX();
                float minY = box.originY();
                float minZ = 8f + box.originZ();
                boxesNbt.add(NbtMap.builder()
                    .putFloat("minX", minX)
                    .putFloat("minY", minY)
                    .putFloat("minZ", minZ)
                    .putFloat("maxX", minX + box.sizeX())
                    .putFloat("maxY", minY + box.sizeY())
                    .putFloat("maxZ", minZ + box.sizeZ())
                    .build());
            }
            return component("minecraft:collision_box", NbtMap.builder()
                .putBoolean("enabled", !boxesNbt.isEmpty())
                .putList("boxes", NbtType.COMPOUND, boxesNbt)
                .build());
        }

        public @NonNull Builder geometry(@NonNull String identifier) {
            return geometry(identifier, Map.of());
        }

        /**
         * @param boneVisibility bone name to a Molang expression (or {@code true}/{@code false})
         */
        public @NonNull Builder geometry(@NonNull String identifier, @NonNull Map<String, String> boneVisibility) {
            NbtMapBuilder geometry = NbtMap.builder().putString("identifier", identifier);
            if (!boneVisibility.isEmpty()) {
                NbtMapBuilder bones = NbtMap.builder();
                boneVisibility.forEach(bones::putString);
                geometry.putCompound("bone_visibility", bones.build());
            }
            return component("minecraft:geometry", geometry.build());
        }

        /**
         * @param materials material instance name (e.g. {@code *}, {@code up}, {@code north}) to material
         */
        public @NonNull Builder materials(@NonNull Map<String, Material> materials) {
            NbtMapBuilder materialsNbt = NbtMap.builder();
            materials.forEach((name, material) -> {
                NbtMapBuilder builder = NbtMap.builder()
                    // Bedrock stopped accepting a byte here in 1.26.20; it must be a float
                    .putFloat("ambient_occlusion", material.ambientOcclusion())
                    .putBoolean("isotropic", material.isotropic())
                    // Actually a bitset; face dimming is its only known flag so far
                    .putBoolean("packed_bools", material.faceDimming());
                if (material.renderMethod() != null) {
                    builder.putString("render_method", material.renderMethod());
                }
                if (material.tintMethod() != null) {
                    builder.putString("tint_method", material.tintMethod());
                }
                if (material.texture() != null) {
                    builder.putString("texture", material.texture());
                }
                materialsNbt.putCompound(name, builder.build());
            });
            return component("minecraft:material_instances", NbtMap.builder()
                .putCompound("mappings", NbtMap.EMPTY)
                .putCompound("materials", materialsNbt.build())
                .build());
        }

        public @NonNull Builder placementFilter(@NonNull PlacementCondition... conditions) {
            List<NbtMap> conditionsNbt = new ArrayList<>();
            for (PlacementCondition condition : conditions) {
                byte faces = 0;
                for (Face face : condition.faces()) {
                    faces |= (byte) face.bit;
                }
                List<NbtMap> filters = new ArrayList<>();
                for (String block : condition.blocks()) {
                    filters.add(NbtMap.builder().putString("name", block).build());
                }
                for (String tag : condition.tags()) {
                    // The meaning of tags_version is unknown, but tags do not work without it
                    filters.add(NbtMap.builder().putString("tags", tag).putInt("tags_version", 6).build());
                }
                conditionsNbt.add(NbtMap.builder()
                    .putByte("allowed_faces", faces)
                    .putList("block_filters", NbtType.COMPOUND, filters)
                    .build());
            }
            return component("minecraft:placement_filter", NbtMap.builder()
                .putList("conditions", NbtType.COMPOUND, conditionsNbt)
                .build());
        }

        /**
         * @param seconds the time to break the block by hand
         */
        public @NonNull Builder destructibleByMining(float seconds) {
            return component("minecraft:destructible_by_mining", NbtMap.builder().putFloat("value", seconds).build());
        }

        public @NonNull Builder friction(float friction) {
            return component("minecraft:friction", NbtMap.builder().putFloat("value", friction).build());
        }

        public @NonNull Builder lightEmission(int level) {
            return component("minecraft:light_emission", NbtMap.builder().putByte("emission", (byte) level).build());
        }

        public @NonNull Builder lightDampening(int level) {
            return component("minecraft:light_dampening", NbtMap.builder().putByte("lightLevel", (byte) level).build());
        }

        /**
         * @param rotationX rotation in degrees; Bedrock only supports multiples of 90
         */
        public @NonNull Builder transformation(float rotationX, float rotationY, float rotationZ,
                                               float scaleX, float scaleY, float scaleZ,
                                               float translateX, float translateY, float translateZ) {
            return component("minecraft:transformation", NbtMap.builder()
                .putInt("RX", quarterTurns(rotationX))
                .putInt("RY", quarterTurns(rotationY))
                .putInt("RZ", quarterTurns(rotationZ))
                .putFloat("SX", scaleX)
                .putFloat("SY", scaleY)
                .putFloat("SZ", scaleZ)
                .putFloat("TX", translateX)
                .putFloat("TY", translateY)
                .putFloat("TZ", translateZ)
                .build());
        }

        public @NonNull Builder rotation(float rotationX, float rotationY, float rotationZ) {
            return transformation(rotationX, rotationY, rotationZ, 1, 1, 1, 0, 0, 0);
        }

        /**
         * Makes the client fire the given event instead of placing anything itself. Useful when the server
         * decides what is placed, as it avoids the client briefly placing the block twice.
         */
        public @NonNull Builder onPlayerPlacing(@NonNull String eventName) {
            return component("minecraft:on_player_placing", NbtMap.builder().putString("triggerType", eventName).build());
        }

        /**
         * Adds a block tag, queryable with Molang. Tags are plain components named {@code tag:<name>}.
         */
        public @NonNull Builder tag(@NonNull String tag) {
            return component("tag:" + tag, NbtMap.EMPTY);
        }

        public @NonNull BlockComponents build() {
            return new BlockComponents(NbtMap.fromMap(components));
        }

        private static int quarterTurns(float degrees) {
            return (int) ((degrees % 360 + 360) % 360) / 90;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockComponents other && nbt.equals(other.nbt);
    }

    @Override
    public int hashCode() {
        return nbt.hashCode();
    }

    @Override
    public String toString() {
        return nbt.toString();
    }
}
