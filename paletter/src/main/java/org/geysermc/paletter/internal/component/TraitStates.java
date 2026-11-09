package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.component.BlockTrait;
import org.geysermc.paletter.block.component.Connection;
import org.geysermc.paletter.block.component.MultiBlock;
import org.geysermc.paletter.block.component.PlacementDirection;
import org.geysermc.paletter.block.component.PlacementPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The block states traits add. The client derives them from the trait (they are not in the block's {@code properties}
 * on the wire), so every palette has to enumerate them the same way: the first value is the default. Values verified
 * against BDS 1.26.63.1 block hashes ({@code data/bds/WIRE-NOTES.md}, Traits).
 */
public final class TraitStates {
    private static final String[] CARDINAL = {"south", "west", "north", "east"};
    private static final String[] FACING = {"down", "up", "north", "south", "west", "east"};

    private TraitStates() {
    }

    public static @NonNull List<BlockProperty<?>> of(@NonNull List<BlockTrait> traits) {
        List<BlockProperty<?>> states = new ArrayList<>();
        for (BlockTrait trait : traits) {
            switch (trait) {
                case PlacementDirection direction -> {
                    for (PlacementDirection.EnabledStates state : direction.enabledStates()) {
                        switch (state) {
                            case CARDINAL_DIRECTION -> states.add(BlockProperty.strings(state.value(), CARDINAL));
                            case FACING_DIRECTION -> states.add(BlockProperty.strings(state.value(), FACING));
                            // Values not verified: the vanilla values of minecraft:corner, with the cardinal directions
                            case CORNER_AND_CARDINAL_DIRECTION -> states.add(BlockProperty.strings("minecraft:corner",
                                "none", "inner_left", "inner_right", "outer_left", "outer_right"));
                        }
                    }
                }
                case PlacementPosition position -> {
                    for (PlacementPosition.EnabledStates state : position.enabledStates()) {
                        switch (state) {
                            case BLOCK_FACE -> states.add(BlockProperty.strings(state.value(), FACING));
                            case VERTICAL_HALF -> states.add(BlockProperty.strings(state.value(), "bottom", "top"));
                        }
                    }
                }
                case Connection connection -> {
                    if (!connection.enabledStates().isEmpty()) {
                        for (String side : List.of("north", "east", "south", "west")) {
                            states.add(BlockProperty.bool("minecraft:connection_" + side));
                        }
                    }
                }
                case MultiBlock multiBlock -> states.add(BlockProperty.range("minecraft:multi_block_part", 0, multiBlock.parts() - 1));
            }
        }
        return states;
    }
}
