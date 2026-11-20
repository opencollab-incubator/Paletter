package dev.opencollab.paletter.creative;

/**
 * An element directly inside a creative category: either a {@link CreativeGroup} (a collapsible group with an icon),
 * or a {@link CreativeItem} that is not in any group ("loose").
 * <p>
 * Use pattern matching to tell them apart, with a {@code default}: Bedrock may add kinds of elements, and the compiler
 * enforces the {@code default} (docs/api.md, "Evolution"):
 * <pre>{@code
 * switch (node) {
 *     case CreativeGroup group -> ...
 *     case CreativeItem item -> ...
 *     default -> ...
 * }
 * }</pre>
 * Neither knows which category or group it is in; ask the {@link CreativeLayout} or {@link CreativeEditor} instead.
 * That way an element can never disagree with the layout about where it is.
 *
 * @since 1.0.0
 */
public sealed interface CreativeNode permits CreativeGroup, CreativeItem, FutureCreativeNode {
}
