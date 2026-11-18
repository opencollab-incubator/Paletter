package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Obs;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Variant;
import org.geysermc.paletter.tools.model.rule.Descriptor;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The descriptor fit of {@link FieldAnalysis}: which object of the JSON a field's variants send as a block or item descriptor. */
class DescriptorFitTest {
    /** A variant of a component with the JSON text, and the leaves BDS sent for it (name, then its value, ...). */
    private static Obs variant(String json, Object... leaves) {
        Map<String, Leaf> wire = new TreeMap<>();
        for (int i = 0; i < leaves.length; i += 2) {
            wire.put((String) leaves[i], new Leaf(Wire.typeOf(leaves[i + 1]), leaves[i + 1]));
        }
        Variant variant = new Variant("id", "item", "f", "item:c", "field", "p", json, null, JsonParser.parseString(json), List.of());
        return new Obs(variant, wire, List.of());
    }

    @Test
    void theObjectHoldingAFieldThatVariesIsABlockDescriptorWrittenFromItsRecord() {
        String d = "c.use_on[0]";
        Obs reference = variant("{\"use_on\": [{\"name\": \"minecraft:stone\"}]}", d + ".name", "minecraft:stone", d + ".tags", "");
        Obs states = variant("{\"use_on\": [{\"name\": \"minecraft:stone\", \"states\": {\"k\": 1}}]}",
            d + ".name", "minecraft:stone", d + ".states.k", 1, d + ".tags", "");
        Descriptor rule = FieldAnalysis.descriptor("use_on[].states", reference, List.of(states));
        assertEquals("block", rule.of);
        assertEquals("c.use_on[]", rule.wire);
        assertTrue(rule.writesHolder());
    }

    @Test
    void aStringFieldThatNamesAnItemIsAnItemDescriptorWrittenFromItsValue() {
        String d = "c.ammunition[0].item";
        Obs reference = variant("{\"ammunition\": [{\"item\": \"minecraft:stick\"}]}", d + ".name", "minecraft:stick");
        Obs unknown = variant("{\"ammunition\": [{\"item\": \"minecraft:unknown\"}]}");
        Descriptor rule = FieldAnalysis.descriptor("ammunition[].item", reference, List.of(unknown));
        assertEquals("item", rule.of);
        assertEquals("c.ammunition[].item", rule.wire);
        assertFalse(rule.writesHolder());
    }

    @Test
    void anObjectThatNamesAnItemIsNoItemDescriptor() {
        // a map of items under any key: its entry is sent as a leaf of its own, not as the item of a descriptor
        String d = "c.converts";
        Obs reference = variant("{\"converts\": {\"name\": \"minecraft:bowl\"}}", d + ".name", "minecraft:bowl");
        Obs other = variant("{\"converts\": {\"name\": \"minecraft:stick\"}}", d + ".name", "minecraft:stick");
        assertNull(FieldAnalysis.descriptor("converts", reference, List.of(other)));
    }

    @Test
    void statesSentAsAListAreNoBlockDescriptorOfTheCompound() {
        String d = "c.filter[0]";
        Obs reference = variant("{\"filter\": [{\"name\": \"minecraft:dirt\"}]}", d + ".name", "minecraft:dirt", d + ".tags", "");
        Obs states = variant("{\"filter\": [{\"name\": \"minecraft:dirt\", \"states\": {\"k\": 1}}]}",
            d + ".name", "minecraft:dirt", d + ".states[0].state", "k", d + ".tags", "");
        assertNull(FieldAnalysis.descriptor("filter[].states", reference, List.of(states)));
    }
}
