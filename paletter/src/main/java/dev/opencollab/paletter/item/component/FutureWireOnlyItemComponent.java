package dev.opencollab.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Never instantiated: it makes a switch over {@code WireOnlyItemComponent} need a {@code default}, as Bedrock adds
 * variants in minor releases (docs/api.md, "Evolution").
 */
final class FutureWireOnlyItemComponent implements WireOnlyItemComponent {
    private FutureWireOnlyItemComponent() {
    }

    @Override
    public @NonNull String name() {
        throw new AssertionError();
    }
}
