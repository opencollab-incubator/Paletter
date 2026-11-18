package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a component BDS rejects now is accounted for from what the format sweep found. */
class OldFormatsTest {
    private static JsonObject formats(String formats) {
        return JsonParser.parseString("{\"formats\": " + formats + "}").getAsJsonObject();
    }

    @Test
    void anUpgradeIsReplacedOnlyWhenTheSuccessorIsVerified() {
        JsonObject upgraded = formats("{\"1.16.100\": {\"wire\": [\"minecraft:light_dampening\"], \"upgradedTo\": [\"block:minecraft:light_dampening\"]}}");
        assertEquals(OldFormats.REPLACED, OldFormats.classify(upgraded, false, Set.of("block:minecraft:light_dampening")));
        assertNull(OldFormats.classify(upgraded, false, Set.of()), "a successor nothing compared is no account");
    }

    @Test
    void theOtherAccounts() {
        assertEquals(OldFormats.OLD_STYLE_ITEM, OldFormats.classify(formats("{\"1.10.0\": {\"wire\": [\"minecraft:seed\"], \"legacyEnvelope\": true}}"), false, Set.of()));
        assertEquals(OldFormats.SERVER_ONLY, OldFormats.classify(formats("{\"1.20.30\": {\"wire\": []}}"), false, Set.of()));
        assertEquals(OldFormats.SERVER_ONLY, OldFormats.classify(new JsonObject(), true, Set.of()), "the white-box says it is not sent");
        assertEquals(OldFormats.REJECTED_EVERYWHERE, OldFormats.classify(formats("{}"), false, Set.of()));
    }

    @Test
    void aWireOfItsOwnAtAnOldFormatFitsNoClassAndMissingDataIsPending() {
        assertNull(OldFormats.classify(formats("{\"1.16.0\": {\"wire\": [\"minecraft:breathability\"]}}"), false, Set.of()));
        assertEquals(OldFormats.PENDING_SWEEP, OldFormats.classify(new JsonObject(), false, Set.of()));
        assertEquals(OldFormats.PENDING_SWEEP, OldFormats.classify(null, false, Set.of()));
    }

    @Test
    void theWhiteboxFlagIsReadPerKind() {
        JsonObject whitebox = JsonParser.parseString("{\"blockComponents\": {\"minecraft:queued_ticking\": {\"network\": false}, \"minecraft:x\": {\"network\": true}},"
            + " \"itemComponents\": {}}").getAsJsonObject();
        assertTrue(OldFormats.notSent(whitebox, "block:minecraft:queued_ticking"));
        assertEquals(false, OldFormats.notSent(whitebox, "block:minecraft:x"));
        assertEquals(false, OldFormats.notSent(whitebox, "item:minecraft:queued_ticking"));
        assertEquals(false, OldFormats.notSent(null, "block:minecraft:x"));
    }
}
