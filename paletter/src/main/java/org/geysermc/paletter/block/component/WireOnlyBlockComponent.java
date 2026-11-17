package org.geysermc.paletter.block.component;

/**
 * Hand-written block components for wire fields Bedrock clients read but add-on JSON cannot set (or no longer can): they
 * have no source in the component model, so they are not generated. They go through the same {@code CustomBlock} and
 * compile path as the generated components.
 * <ul>
 *     <li>{@link OnPlayerPlacing}: {@code minecraft:on_player_placing}, retired from the add-on format</li>
 * </ul>
 *
 * @since 1.0.0
 */
@SuppressWarnings("deprecation")
public sealed interface WireOnlyBlockComponent extends BlockComponent permits OnPlayerPlacing {
}
