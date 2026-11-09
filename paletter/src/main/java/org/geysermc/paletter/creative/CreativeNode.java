package org.geysermc.paletter.creative;

/**
 * An element directly inside a creative category: either a {@link CreativeGroup} (a collapsible group with an icon),
 * or a {@link CreativeItem} that is not in any group ("loose").
 * <p>
 * Use pattern matching to tell them apart:
 * <pre>{@code
 * switch (node) {
 *     case CreativeGroup group -> ...
 *     case CreativeItem item -> ...
 * }
 * }</pre>
 * Neither knows which category or group it is in; ask the {@link CreativeLayout} or {@link CreativeEditor} instead.
 * That way an element can never disagree with the layout about where it is.
 *
 * @since 1.0.0
 */
public sealed interface CreativeNode permits CreativeGroup, CreativeItem {
}
