package org.geysermc.paletter.block.component;

/**
 * How a block material is rendered (add-on JSON {@code render_method}).
 *
 * @since 2.0.0
 */
public enum RenderMethod {
    OPAQUE,
    DOUBLE_SIDED,
    BLEND,
    ALPHA_TEST,
    ALPHA_TEST_SINGLE_SIDED,
    BLEND_TO_OPAQUE,
    ALPHA_TEST_TO_OPAQUE,
    ALPHA_TEST_SINGLE_SIDED_TO_OPAQUE
}
