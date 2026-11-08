package org.geysermc.paletter.item.component;

/**
 * Hand-written item components for wire fields Bedrock clients read but add-on JSON cannot set (or no longer can): they
 * have no source in the component model, so they are not generated. They go through the same {@code CustomItem} and
 * compile path as the generated components, and are encoded per protocol window by Paletter.
 * <ul>
 *     <li>{@link RenderOffsets}: {@code minecraft:render_offsets}, retired from the add-on format</li>
 *     <li>{@link MiningSpeed}: {@code item_properties.mining_speed}</li>
 *     <li>{@link FrameCount}: {@code item_properties.frame_count}</li>
 * </ul>
 *
 * @since 1.0.0
 */
@SuppressWarnings("deprecation")
public sealed interface WireOnlyItemComponent extends ItemComponent permits RenderOffsets, MiningSpeed, FrameCount {
}
