package org.geysermc.paletter.tools.model.infer;

import org.geysermc.paletter.tools.model.rule.Leaf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The envelope: what every control sends alike, leaves a control's companions or description explain left out of its vote. */
class EnvelopeTest {
    private static Leaf i(int value) {
        return new Leaf("int", value);
    }

    @Test
    void aLeafEveryControlSendsAlikeIsInTheEnvelope() {
        Map<String, Leaf> envelope = MappingInference.shared(
            List.of(Map.of("p.size", i(64), "p.damage", i(0)), Map.of("p.size", i(64), "p.damage", i(0))),
            List.of(Set.of(), Set.of()));
        assertEquals(Map.of("p.size", i(64), "p.damage", i(0)), envelope);
    }

    @Test
    void aLeafTheControlsDisagreeOnOrOneLacksIsNot() {
        Map<String, Leaf> envelope = MappingInference.shared(
            List.of(Map.of("p.size", i(64), "p.damage", i(0)), Map.of("p.size", i(1))),
            List.of(Set.of(), Set.of()));
        assertEquals(Map.of(), envelope);
    }

    @Test
    void aControlWhoseCompanionExplainsALeafDoesNotVoteOnIt() {
        // A wearable companion sets the stack size to 1; the filler sets it to 64 explicitly; the third control says 64
        Map<String, Leaf> envelope = MappingInference.shared(
            List.of(Map.of("p.size", i(1)), Map.of("p.size", i(64)), Map.of("p.size", i(64))),
            List.of(Set.of("p.size"), Set.of("p"), Set.of()));
        assertEquals(Map.of("p.size", i(64)), envelope);
    }

    @Test
    void aLeafEveryControlExplainsIsNot() {
        Map<String, Leaf> envelope = MappingInference.shared(List.of(Map.of("menu.category", i(4))), List.of(Set.of("menu.category")));
        assertEquals(Map.of(), envelope);
    }

    @Test
    void blockComponentsAreMatchedWithoutTheirComponentsPrefix() {
        Map<String, Leaf> envelope = MappingInference.shared(
            List.of(Map.of("components.minecraft:geometry.bones[0]", i(1), "molangVersion", i(13)), Map.of("molangVersion", i(13))),
            List.of(Set.of("minecraft:geometry"), Set.of()));
        assertEquals(Map.of("molangVersion", i(13)), envelope);
    }
}
