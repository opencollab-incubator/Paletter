package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
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
import java.util.function.BiConsumer;

/**
 * The block states traits add. The client derives them from the trait (they are not in the block's {@code properties}
 * on the wire), so every palette has to enumerate them the same way: the first value is the default. The states of each
 * {@code enabled_states} value come from the generated {@link TraitStateTable}, which says per state and protocol window what
 * backs it.
 * <p>
 * A value the table marks unsupported in a protocol (BDS 1.26.36.1 lists {@code minecraft:sixteen_way_rotation} with duplicate
 * values, so the states a 26.30 client adds for it are not well defined) is left out of the trait in that protocol, on the wire
 * ({@link #supported}, which reports it) and in the states ({@link #of(List, int)}), so no state the client lacks is sent.
 */
public final class TraitStates {
    private TraitStates() {
    }

    /**
     * @return the states of the newest protocol: those of a custom block's type, the same for every palette
     */
    public static @NonNull List<BlockProperty<?>> of(@NonNull List<BlockTrait> traits) {
        return of(traits, Integer.MAX_VALUE);
    }

    /**
     * @param protocol the protocol of the palette's version
     * @return the states the traits add in that protocol: without those of values unsupported there
     */
    public static @NonNull List<BlockProperty<?>> of(@NonNull List<BlockTrait> traits, int protocol) {
        List<BlockProperty<?>> states = new ArrayList<>();
        for (BlockTrait full : traits) {
            BlockTrait trait = supported(full, protocol, (name, reason) -> { });
            if (trait == null) {
                continue;
            }
            // An enabled state newer than Paletter (not isKnown) has no table entry: it adds no state, as the encoder sends no bit
            // for it. The trait's encoder (TraitWire) reports it, once per value and palette: the warning is not repeated here,
            // where no palette is known yet
            switch (trait) {
                case PlacementDirection t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(PlacementDirection.NAME, s.value(), protocol)));
                case PlacementPosition t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(PlacementPosition.NAME, s.value(), protocol)));
                case Connection t -> t.enabledStates().stream().filter(s -> s.isKnown())
                    .forEach(s -> states.addAll(TraitStateTable.of(Connection.NAME, s.value(), protocol)));
                case MultiBlock t -> t.enabledStates().stream().filter(s -> s.isKnown()).forEach(s -> {
                    for (BlockProperty<?> state : TraitStateTable.of(MultiBlock.NAME, s.value(), protocol)) {
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
     * The trait as the context's version can send it: without the {@code enabled_states} values the trait state table marks
     * unsupported there, each reported as left out with the table's reason.
     *
     * @return the trait to encode, or null if it is left out as a whole
     */
    public static @Nullable BlockTrait supported(@NonNull BlockTrait trait, @NonNull WireContext ctx) {
        return supported(trait, ctx.protocol(), (name, reason) -> ctx.drop(trait.name(), "enabled_states = " + name
            + " adds no well-defined states: " + reason));
    }

    /**
     * @param dropped gets each value left out with the reason
     * @return the trait without the values unsupported in the protocol; null for a trait whose encoder does not follow its
     *         {@code enabled_states} (connection, multi block: it sends the same flags for any list), which is left out as a
     *         whole then, its states with it
     */
    private static @Nullable BlockTrait supported(BlockTrait trait, int protocol, BiConsumer<String, String> dropped) {
        return switch (trait) {
            case PlacementDirection t -> {
                List<PlacementDirection.EnabledStates> kept = new ArrayList<>();
                for (PlacementDirection.EnabledStates s : t.enabledStates()) {
                    String reason = s.isKnown() ? TraitStateTable.unsupported(PlacementDirection.NAME, s.value(), protocol) : null;
                    if (reason == null) {
                        kept.add(s);
                    } else {
                        dropped.accept(s.value(), reason);
                    }
                }
                yield kept.size() == t.enabledStates().size() ? t : t.toBuilder().enabledStates(kept).build();
            }
            case PlacementPosition t -> {
                List<PlacementPosition.EnabledStates> kept = new ArrayList<>();
                for (PlacementPosition.EnabledStates s : t.enabledStates()) {
                    String reason = s.isKnown() ? TraitStateTable.unsupported(PlacementPosition.NAME, s.value(), protocol) : null;
                    if (reason == null) {
                        kept.add(s);
                    } else {
                        dropped.accept(s.value(), reason);
                    }
                }
                yield kept.size() == t.enabledStates().size() ? t : t.toBuilder().enabledStates(kept).build();
            }
            case Connection t -> wholeOrNone(t, Connection.NAME, t.enabledStates().stream().filter(s -> s.isKnown())
                .map(s -> s.value()).toList(), protocol, dropped);
            case MultiBlock t -> wholeOrNone(t, MultiBlock.NAME, t.enabledStates().stream().filter(s -> s.isKnown())
                .map(s -> s.value()).toList(), protocol, dropped);
            default -> throw new IllegalArgumentException(trait.toString());
        };
    }

    private static @Nullable BlockTrait wholeOrNone(BlockTrait trait, String name, List<String> values, int protocol,
                                                    BiConsumer<String, String> dropped) {
        boolean any = false;
        for (String value : values) {
            String reason = TraitStateTable.unsupported(name, value, protocol);
            if (reason != null) {
                dropped.accept(value, reason + "; the trait is left out, as its encoder sends the same for any enabled_states");
                any = true;
            }
        }
        return any ? null : trait;
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
