package org.geysermc.paletter.block.component;

/**
 * Hand-written block components for wire fields Bedrock clients read but add-on JSON does not model (or no longer can set): they
 * have no source in the component model, so they are not generated. They go through the same {@code CustomBlock} and
 * compile path as the generated components.
 * <ul>
 *     <li>{@link OnPlayerPlacing}: {@code minecraft:on_player_placing}, retired from the add-on format</li>
 *     <li>{@link ScriptHooks}: {@code minecraft:custom_components}, the flags of the script custom components</li>
 * </ul>
 *
 * @since 1.0.0
 */
@SuppressWarnings("deprecation")
public sealed interface WireOnlyBlockComponent extends BlockComponent permits OnPlayerPlacing, ScriptHooks {
}
