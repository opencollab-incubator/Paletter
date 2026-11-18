package dev.opencollab.paletter.item.component;

/**
 * Hand-written item components for wire fields Bedrock clients read but add-on JSON does not model (or no longer can set): they
 * have no source in the component model, so they are not generated. They go through the same {@code CustomItem} and
 * compile path as the generated components, and are encoded per protocol window by Paletter.
 * <ul>
 *     <li>{@link RenderOffsets}: {@code minecraft:render_offsets}, retired from the add-on format</li>
 *     <li>{@link MiningSpeed}: {@code item_properties.mining_speed}</li>
 *     <li>{@link FrameCount}: {@code item_properties.frame_count}</li>
 *     <li>{@link UseOnHook}: {@code minecraft:publisher_on_use_on}, sent for script custom components with {@code onUseOn}</li>
 * </ul>
 *
 * @since 1.0.0
 */
@SuppressWarnings("deprecation")
public sealed interface WireOnlyItemComponent extends ItemComponent permits RenderOffsets, MiningSpeed, FrameCount, UseOnHook {
}
