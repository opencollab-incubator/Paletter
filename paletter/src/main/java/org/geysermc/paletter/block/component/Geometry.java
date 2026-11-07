package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The model of a block (add-on JSON {@code minecraft:geometry}). Blocks need both a geometry and materials, or neither.
 * <pre>{@code
 * Geometry.of("geometry.my_block").boneVisibility("lid", "q.block_state('example:open') == 0")
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Geometry {
    /**
     * The vanilla full block geometry.
     */
    public static final String FULL_BLOCK = "minecraft:geometry.full_block";

    private final String identifier;
    private final Map<String, String> boneVisibility;
    private final @Nullable String culling;
    private final @Nullable String cullingLayer;
    private final boolean uvLock;

    private Geometry(String identifier, Map<String, String> boneVisibility, @Nullable String culling, @Nullable String cullingLayer,
                     boolean uvLock) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.boneVisibility = Collections.unmodifiableMap(new LinkedHashMap<>(boneVisibility));
        this.culling = culling;
        this.cullingLayer = cullingLayer;
        this.uvLock = uvLock;
    }

    /**
     * @param identifier the geometry identifier, e.g. {@code geometry.my_block} or {@link #FULL_BLOCK}
     */
    public static @NonNull Geometry of(@NonNull String identifier) {
        return new Geometry(identifier, Map.of(), null, null, false);
    }

    /**
     * @param bone a bone of the geometry
     * @param molang a Molang condition for the bone to be visible, or {@code "true"} / {@code "false"}
     */
    public @NonNull Geometry boneVisibility(@NonNull String bone, @NonNull String molang) {
        Map<String, String> all = new LinkedHashMap<>(boneVisibility);
        all.put(Objects.requireNonNull(bone, "bone"), Objects.requireNonNull(molang, "molang"));
        return new Geometry(identifier, all, culling, cullingLayer, uvLock);
    }

    /**
     * @param culling the identifier of a block culling rule from the resource pack, or null for none
     */
    public @NonNull Geometry culling(@Nullable String culling) {
        return new Geometry(identifier, boneVisibility, culling, cullingLayer, uvLock);
    }

    /**
     * @param cullingLayer the culling layer, e.g. {@code minecraft:culling_layer.leaves}, or null for the default
     */
    public @NonNull Geometry cullingLayer(@Nullable String cullingLayer) {
        return new Geometry(identifier, boneVisibility, culling, cullingLayer, uvLock);
    }

    /**
     * @param uvLock whether textures keep their orientation when the block is rotated
     */
    public @NonNull Geometry uvLock(boolean uvLock) {
        return new Geometry(identifier, boneVisibility, culling, cullingLayer, uvLock);
    }

    public @NonNull String identifier() {
        return identifier;
    }

    public @NonNull Map<String, String> boneVisibility() {
        return boneVisibility;
    }

    public @Nullable String culling() {
        return culling;
    }

    public @Nullable String cullingLayer() {
        return cullingLayer;
    }

    public boolean uvLock() {
        return uvLock;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Geometry other && identifier.equals(other.identifier) && boneVisibility.equals(other.boneVisibility)
            && Objects.equals(culling, other.culling) && Objects.equals(cullingLayer, other.cullingLayer) && uvLock == other.uvLock;
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, boneVisibility, culling, cullingLayer, uvLock);
    }

    @Override
    public String toString() {
        return "Geometry[" + identifier + ", bones " + boneVisibility + ", culling " + culling + ", layer " + cullingLayer
            + ", uv lock " + uvLock + "]";
    }
}
