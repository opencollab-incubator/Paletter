package org.geysermc.paletter.behaviour;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.Behaviour;
import org.geysermc.paletter.internal.behaviour.WireRules;
import org.geysermc.paletter.item.RegisteredItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire rules (what custom content is read with) against the bundled dumps, on the vanilla content that has both:
 * the data driven blocks and the component based items of every bundled version. A difference is either a wrong rule
 * or a disagreement between what BDS sends and what it does; known ones are listed with the reason.
 */
class WireCrossCheckTest {
    private static final Paletter PALETTER = Paletter.builder().build();
    /** {@code version key identifier}: differences that are understood. Empty means the rules match every dump value. */
    private static final Set<String> KNOWN = Set.of();

    private static Object value(Behaviour.Value value) {
        return value == null ? null : value.value();
    }

    @Test
    void wireRulesAgreeWithTheDumps() {
        Set<String> differences = new TreeSet<>();
        int compared = 0;
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            Behaviour behaviour = palette.behaviour();
            for (var data : palette.blockProperties()) {
                RegisteredBlock block = palette.blocks().block(data.getName());
                for (RegisteredBlockState state : block.states()) {
                    for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                        Object wire = WireRules.value(key, block.properties(state));
                        Object dump = value(behaviour.resolve(state, key));
                        if (wire != null && dump != null) {
                            compared++;
                            if (!Objects.equals(wire, dump)) {
                                differences.add(version.name() + " " + key + " " + block.identifier() + " wire " + wire + " dump " + dump);
                            }
                        }
                    }
                }
            }
            for (RegisteredItem item : palette.items().items()) {
                for (BehaviourKey<?> key : ItemBehaviour.keys()) {
                    Object wire = WireRules.value(key, item.properties());
                    Object dump = value(behaviour.resolve(item, key));
                    if (wire != null && dump != null) {
                        compared++;
                        if (!Objects.equals(wire, dump)) {
                            differences.add(version.name() + " " + key + " " + item.getIdentifier() + " wire " + wire + " dump " + dump);
                        }
                    }
                }
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
        List<String> unexpected = new ArrayList<>();
        for (String difference : differences) {
            if (KNOWN.stream().noneMatch(difference::startsWith)) {
                unexpected.add(difference);
            }
        }
        assertEquals(List.of(), unexpected, unexpected.size() + " of " + compared + " values differ");
    }
}
