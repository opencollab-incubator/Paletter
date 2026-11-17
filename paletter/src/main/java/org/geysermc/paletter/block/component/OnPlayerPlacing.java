package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * {@code minecraft:on_player_placing}: the event the client triggers when a player places the block. A trigger type that
 * no behavior pack handles makes the client not predict the placement itself, which stops it from sometimes placing a
 * block twice (what Geyser uses it for).
 * <p>
 * Retired from the add-on format (Bedrock Dedicated Server 1.26.63.1 rejects it at every {@code format_version}). Paletter
 * assumes current clients still honour it on the wire and keeps sending it for legacy content; it will be removed if
 * clients stop honouring it. Sent as {@code {triggerType: <string>}}.
 *
 * @param triggerType the event name, e.g. {@code geyser:place_event}
 * @since 1.0.0
 * @deprecated retired from the add-on format, still sent for legacy content; will be removed if clients stop honouring it
 */
@Deprecated
public record OnPlayerPlacing(@NonNull String triggerType) implements WireOnlyBlockComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:on_player_placing";

    public OnPlayerPlacing {
        Objects.requireNonNull(triggerType, "OnPlayerPlacing.triggerType");
    }

    /**
     * @param triggerType the event name, e.g. {@code geyser:place_event}
     * @return the component
     */
    public static @NonNull OnPlayerPlacing of(@NonNull String triggerType) {
        return new OnPlayerPlacing(triggerType);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }
}
