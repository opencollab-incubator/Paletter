package dev.opencollab.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * {@code minecraft:publisher_on_use_on}: sent for an item whose custom component registers {@code onUseOn}. Add-on JSON
 * cannot set it; Bedrock Dedicated Server adds it for such a component, and (as {@code autoSucceedOnClient} false) for
 * {@code block_placer} and {@code entity_placer}, which Paletter writes itself.
 * <p>
 * The placer encoders write 0 for this same key, so with both on an item the order of the writes decides which byte is sent
 * (today the later component wins). What BDS sends for a placer together with an {@code onUseOn} component is unverified; the
 * next recapture settles it.
 *
 * @since 1.0.0
 */
public final class UseOnHook implements WireOnlyItemComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:publisher_on_use_on";

    private final boolean autoSucceedOnClient;

    private UseOnHook(boolean autoSucceedOnClient) {
        this.autoSucceedOnClient = autoSucceedOnClient;
    }

    /**
     * Returns whether the client succeeds the use on its own.
     *
     * @return the {@code autoSucceedOnClient} byte sent to the client
     */
    public boolean autoSucceedOnClient() {
        return autoSucceedOnClient;
    }

    /**
     * Creates the component.
     *
     * @param autoSucceedOnClient the {@code autoSucceedOnClient} byte sent to the client
     * @return the component
     */
    public static @NonNull UseOnHook of(boolean autoSucceedOnClient) {
        return new UseOnHook(autoSucceedOnClient);
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof UseOnHook other && autoSucceedOnClient == other.autoSucceedOnClient;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(autoSucceedOnClient);
    }

    @Override
    public String toString() {
        return "UseOnHook[autoSucceedOnClient=" + autoSucceedOnClient + "]";
    }
}
