package dev.opencollab.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Never instantiated: it makes a switch over {@code WireOnlyBlockComponent} need a {@code default}, as Bedrock adds
 * variants in minor releases (docs/api.md, "Evolution").
 */
final class FutureWireOnlyBlockComponent implements WireOnlyBlockComponent {
    private FutureWireOnlyBlockComponent() {
    }

    @Override
    public @NonNull String name() {
        throw new AssertionError();
    }
}
