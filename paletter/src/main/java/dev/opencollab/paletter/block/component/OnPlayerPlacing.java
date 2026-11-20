package dev.opencollab.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * {@code minecraft:on_player_placing}: the event the client triggers when a player places the block. A trigger type that
 * no behavior pack handles makes the client not predict the placement itself, which stops it from sometimes placing a
 * block twice (what Geyser uses it for).
 * <p>
 * Retired from the add-on format (the Bedrock Dedicated Server of Bedrock 26.60 rejects it at every {@code format_version}). Paletter
 * assumes current clients still honour it on the wire and keeps sending it for legacy content; it will be removed if
 * clients stop honouring it. Sent as {@code {triggerType: <string>}}.
 *
 * @since 1.0.0
 * @deprecated retired from the add-on format, still sent for legacy content; will be removed if clients stop honouring it
 */
@Deprecated
public final class OnPlayerPlacing implements WireOnlyBlockComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:on_player_placing";

    private final @NonNull String triggerType;

    private OnPlayerPlacing(@NonNull String triggerType) {
        this.triggerType = Objects.requireNonNull(triggerType, "OnPlayerPlacing.triggerType");
    }

    /**
     * Returns the event the client triggers.
     *
     * @return the event name, e.g. {@code geyser:place_event}
     */
    public @NonNull String triggerType() {
        return triggerType;
    }

    /**
     * Creates the component.
     *
     * @param triggerType the event name, e.g. {@code geyser:place_event}
     * @return the component
     * @throws NullPointerException if {@code triggerType} is null
     */
    public static @NonNull OnPlayerPlacing of(@NonNull String triggerType) {
        return new OnPlayerPlacing(triggerType);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof OnPlayerPlacing other && triggerType.equals(other.triggerType);
    }

    @Override
    public int hashCode() {
        return triggerType.hashCode();
    }

    @Override
    public String toString() {
        return "OnPlayerPlacing[triggerType=" + triggerType + "]";
    }
}
