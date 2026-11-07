package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * One material instance of a block (add-on JSON {@code minecraft:material_instances} entry).
 * <pre>{@code
 * Material.of("my_texture").renderMethod(RenderMethod.ALPHA_TEST).faceDimming(false).isotropic(true)
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Material {
    /**
     * The material instance name that applies to every face without a material of its own.
     */
    public static final String ALL_FACES = "*";

    private final @Nullable String texture;
    private final @Nullable RenderMethod renderMethod;
    private final @Nullable TintMethod tintMethod;
    private final float ambientOcclusion;
    private final boolean faceDimming;
    private final boolean isotropic;

    private Material(@Nullable String texture, @Nullable RenderMethod renderMethod, @Nullable TintMethod tintMethod,
                     float ambientOcclusion, boolean faceDimming, boolean isotropic) {
        this.texture = texture;
        this.renderMethod = renderMethod;
        this.tintMethod = tintMethod;
        this.ambientOcclusion = ambientOcclusion;
        this.faceDimming = faceDimming;
        this.isotropic = isotropic;
    }

    /**
     * @param texture the texture short name from {@code terrain_texture.json}
     * @return a material with the add-on JSON defaults: default render method, no tint, ambient occlusion 1, face
     * dimming, not isotropic
     */
    public static @NonNull Material of(@NonNull String texture) {
        return new Material(Objects.requireNonNull(texture, "texture"), null, null, 1f, true, false);
    }

    /**
     * @return a material without a texture of its own, for blocks whose resource pack {@code blocks.json} defines it
     */
    public static @NonNull Material withoutTexture() {
        return new Material(null, null, null, 1f, true, false);
    }

    /**
     * @param renderMethod the render method, or null for the client's default ({@code opaque})
     */
    public @NonNull Material renderMethod(@Nullable RenderMethod renderMethod) {
        return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    /**
     * @param tintMethod the tint method, or null for none
     */
    public @NonNull Material tintMethod(@Nullable TintMethod tintMethod) {
        return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    /**
     * @param ambientOcclusion the ambient occlusion exponent; 1 is the default, 0 disables it
     */
    public @NonNull Material ambientOcclusion(float ambientOcclusion) {
        return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    /**
     * @param faceDimming whether faces are shaded by direction (default true)
     */
    public @NonNull Material faceDimming(boolean faceDimming) {
        return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    /**
     * @param isotropic whether the texture is rotated randomly per block (default false)
     */
    public @NonNull Material isotropic(boolean isotropic) {
        return new Material(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    public @Nullable String texture() {
        return texture;
    }

    public @Nullable RenderMethod renderMethod() {
        return renderMethod;
    }

    public @Nullable TintMethod tintMethod() {
        return tintMethod;
    }

    public float ambientOcclusion() {
        return ambientOcclusion;
    }

    public boolean faceDimming() {
        return faceDimming;
    }

    public boolean isotropic() {
        return isotropic;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Material other && Objects.equals(texture, other.texture) && renderMethod == other.renderMethod
            && tintMethod == other.tintMethod && ambientOcclusion == other.ambientOcclusion && faceDimming == other.faceDimming
            && isotropic == other.isotropic;
    }

    @Override
    public int hashCode() {
        return Objects.hash(texture, renderMethod, tintMethod, ambientOcclusion, faceDimming, isotropic);
    }

    @Override
    public String toString() {
        return "Material[" + texture + ", " + renderMethod + ", tint " + tintMethod + ", ao " + ambientOcclusion + ", dimming "
            + faceDimming + ", isotropic " + isotropic + "]";
    }
}
