package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.component.BlockTrait;
import dev.opencollab.paletter.block.component.Connection;
import dev.opencollab.paletter.block.component.MultiBlock;
import dev.opencollab.paletter.block.component.PlacementDirection;
import dev.opencollab.paletter.block.component.PlacementPosition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The block states traits add. The client derives them from the trait (they are not in the block's {@code properties}
 * on the wire), so every palette has to enumerate them the same way: the first value is the default. The states of each
 * {@code enabled_states} value come from the generated {@link TraitStateTable}, which says per state what backs it.
 */
public final class TraitStates {
    private TraitStates() {
    }

    public static @NonNull List<BlockProperty<?>> of(@NonNull List<BlockTrait> traits) {
        List<BlockProperty<?>> states = new ArrayList<>();
        for (BlockTrait trait : traits) {
            // An enabled state newer than Paletter (not isKnown) has no table entry: it adds no state, as the encoder sends no bit for it
            switch (trait) {
                case PlacementDirection t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(PlacementDirection.NAME, s.value())));
                case PlacementPosition t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(PlacementPosition.NAME, s.value())));
                case Connection t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(Connection.NAME, s.value())));
                case MultiBlock t -> t.enabledStates().stream().filter(s -> s.isKnown()).forEach(s -> {
                    for (BlockProperty<?> state : TraitStateTable.of(MultiBlock.NAME, s.value())) {
                        states.add(firstParts(state, t.parts()));
                    }
                });
                default -> throw new IllegalArgumentException(trait.toString());
            }
        }
        // a state two values add (minecraft:cardinal_direction and minecraft:corner_and_cardinal_direction) is one state
        Set<String> names = new HashSet<>();
        states.removeIf(state -> !names.add(state.name()));
        return states;
    }

    /**
     * A multi block has one {@code multi_block_part} value per part: the trait's {@code parts} decides how many of the
     * documented values exist. Hand-written: no data says it, it follows from the docs' meaning of {@code parts}, and only
     * the default (part 0 of 2) is verified by a captured hash.
     */
    @SuppressWarnings("unchecked")
    private static BlockProperty<?> firstParts(BlockProperty<?> state, int parts) {
        List<Integer> values = ((List<Integer>) state.values()).subList(0, parts);
        return BlockProperty.ints(state.name(), values.toArray(Integer[]::new));
    }
}
