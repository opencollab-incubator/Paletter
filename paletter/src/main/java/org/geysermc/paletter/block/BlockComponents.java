package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.component.BlockComponent;
import org.geysermc.paletter.component.ComponentType;
import org.geysermc.paletter.component.block.CollisionBox;
import org.geysermc.paletter.component.block.DestructibleByMining;
import org.geysermc.paletter.component.block.DisplayName;
import org.geysermc.paletter.component.block.Friction;
import org.geysermc.paletter.component.block.Geometry;
import org.geysermc.paletter.component.block.LightDampening;
import org.geysermc.paletter.component.block.LightEmission;
import org.geysermc.paletter.component.block.MaterialInstances;
import org.geysermc.paletter.component.block.OnPlayerPlacing;
import org.geysermc.paletter.component.block.PlacementFilter;
import org.geysermc.paletter.component.block.SelectionBox;
import org.geysermc.paletter.component.block.Transformation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data driven block components, as sent in the start game packet. Immutable; build with {@link #builder()}.
 * <p>
 * Typed components (generated from captured network NBT, see {@code org.geysermc.paletter.component.block}) are added
 * with {@link Builder#with}. The convenience methods convert units and conventions (pixels relative to the bottom
 * center, degrees, face sets), so callers describe a block instead of encoding it. Anything without a generated type
 * can be set as raw NBT with {@link Builder#component}.
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

    /**
     * @return the component read as its generated type, or null if not set
     */
    public <T extends BlockComponent> @Nullable T get(@NonNull ComponentType<T> type) {
        return nbt.get(type.name()) instanceof NbtMap value ? type.read(value) : null;
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
        /**
         * Bit of {@code packed_bools} for face dimming. Geyser sends face dimming as {@code packed_bools} 0 or 1 in
         * production, so this bit is backed by use.
         */
        public static final int FACE_DIMMING_BIT = 1;
        /**
         * Bit of {@code packed_bools} assumed for isotropic, following the order of the add-on JSON fields
         * (face_dimming, isotropic, alpha_masked_tint). Unverified: needs a capture of an isotropic material.
         */
        public static final int ISOTROPIC_BIT = 1 << 1;

        public static @NonNull Material of(@NonNull String texture) {
            return new Material(texture, null, null, 1.0f, true, false);
        }

        public @NonNull Material renderMethod(@NonNull String renderMethod) {
            return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
        }

        public @NonNull Material tintMethod(@NonNull String tintMethod) {
            return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
        }

        /**
         * @return the network form. Vanilla sends no {@code isotropic} key; the flags are packed into {@code packed_bools}
         */
        public MaterialInstances.@NonNull Material toComponent() {
            return MaterialInstances.Material.builder()
                // Bedrock stopped accepting a byte here in 1.26.20; it must be a float
                .ambientOcclusion(ambientOcclusion)
                .packedBools((byte) ((faceDimming ? FACE_DIMMING_BIT : 0) | (isotropic ? ISOTROPIC_BIT : 0)))
                .renderMethod(renderMethod)
                .tintMethod(tintMethod)
                .texture(texture)
                .build();
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
         * Sets a typed component, replacing any previous value.
         */
        public @NonNull Builder with(@NonNull BlockComponent component) {
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

        public @NonNull Builder displayName(@NonNull String translationKeyOrName) {
            return with(DisplayName.of(translationKeyOrName));
        }

        public @NonNull Builder selectionBox(@NonNull Box box) {
            return with(SelectionBox.builder()
                .enabled(!box.isEmpty())
                .origin(List.of(box.originX(), box.originY(), box.originZ()))
                .size(List.of(box.sizeX(), box.sizeY(), box.sizeZ()))
                .build());
        }

        /**
         * Sets the collision boxes. Empty boxes are skipped; with no non-empty box, collision is disabled.
         */
        public @NonNull Builder collisionBoxes(@NonNull Box... boxes) {
            List<CollisionBox.Box> converted = new ArrayList<>();
            for (Box box : boxes) {
                if (box.isEmpty()) {
                    continue;
                }
                float minX = 8f + box.originX();
                float minY = box.originY();
                float minZ = 8f + box.originZ();
                converted.add(CollisionBox.Box.builder()
                    .minX(minX).minY(minY).minZ(minZ)
                    .maxX(minX + box.sizeX()).maxY(minY + box.sizeY()).maxZ(minZ + box.sizeZ())
                    .build());
            }
            return with(CollisionBox.builder().enabled(!converted.isEmpty()).boxes(converted).build());
        }

        public @NonNull Builder geometry(@NonNull String identifier) {
            return geometry(identifier, Map.of());
        }

        /**
         * @param boneVisibility bone name to a Molang expression (or {@code true}/{@code false})
         */
        public @NonNull Builder geometry(@NonNull String identifier, @NonNull Map<String, String> boneVisibility) {
            Geometry.Builder geometry = Geometry.builder().identifier(identifier);
            if (!boneVisibility.isEmpty()) {
                NbtMapBuilder bones = NbtMap.builder();
                boneVisibility.forEach(bones::putString);
                geometry.boneVisibility(bones.build());
            }
            return with(geometry.build());
        }

        /**
         * @param materials material instance name (e.g. {@code *}, {@code up}, {@code north}) to material
         */
        public @NonNull Builder materials(@NonNull Map<String, Material> materials) {
            Map<String, MaterialInstances.Material> converted = new LinkedHashMap<>();
            materials.forEach((name, material) -> converted.put(name, material.toComponent()));
            return with(MaterialInstances.builder().mappings(NbtMap.EMPTY).materials(converted).build());
        }

        public @NonNull Builder placementFilter(@NonNull PlacementCondition... conditions) {
            List<PlacementFilter.Condition> converted = new ArrayList<>();
            for (PlacementCondition condition : conditions) {
                byte faces = 0;
                for (Face face : condition.faces()) {
                    faces |= (byte) face.bit;
                }
                List<PlacementFilter.BlockFilter> filters = new ArrayList<>();
                for (String block : condition.blocks()) {
                    filters.add(PlacementFilter.BlockFilter.builder().name(block).build());
                }
                for (String tag : condition.tags()) {
                    // The meaning of tags_version is unknown, but tags do not work without it
                    filters.add(PlacementFilter.BlockFilter.builder().tags(tag).tagsVersion(6).build());
                }
                converted.add(new PlacementFilter.Condition(faces, filters));
            }
            return with(PlacementFilter.of(converted));
        }

        /**
         * @param seconds the time to break the block by hand
         */
        public @NonNull Builder destructibleByMining(float seconds) {
            return with(DestructibleByMining.of(seconds));
        }

        public @NonNull Builder friction(float friction) {
            return with(Friction.of(friction));
        }

        public @NonNull Builder lightEmission(int level) {
            return with(LightEmission.of((byte) level));
        }

        public @NonNull Builder lightDampening(int level) {
            return with(LightDampening.of((byte) level));
        }

        /**
         * @param rotationX rotation in degrees; Bedrock only supports multiples of 90
         */
        public @NonNull Builder transformation(float rotationX, float rotationY, float rotationZ,
                                               float scaleX, float scaleY, float scaleZ,
                                               float translateX, float translateY, float translateZ) {
            return with(Transformation.builder()
                .rotationX(quarterTurns(rotationX))
                .rotationY(quarterTurns(rotationY))
                .rotationZ(quarterTurns(rotationZ))
                .scaleX(scaleX).scaleY(scaleY).scaleZ(scaleZ)
                .translationX(translateX).translationY(translateY).translationZ(translateZ)
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
            return with(OnPlayerPlacing.of(eventName));
        }

        /**
         * Adds a block tag, queryable with Molang. Tags are plain components named {@code tag:<name>}, as Geyser sends
         * them; vanilla 26.50+ data driven blocks list their tags in a top level {@code blockTags} list instead.
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
