package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paletter's default creative ordering against what BDS 1.26.63.1 sent for three add-ons (see capture/CREATIVE-ORDER.md):
 * the capture add-on (66 blocks, 84 items, with experiments), the creative order probe (every kind of menu category
 * placement) and the large probe (700 blocks, so BDS's block map has more than 512 buckets).
 */
class CreativeOrderCheckTest {
    private static final Path CAPTURES = Path.of("../capture/captures/1.26.63.1");

    @ParameterizedTest
    @CsvSource({"addon, 149", "creative-probe, 30", "creative-large, 700"})
    void bdsOrderingMatchesTheCapture(String capture, int customStacks) throws Exception {
        Path directory = CAPTURES.resolve(capture);
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(Paletter.bundled(), 2225,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"));
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertTrue(result.customStacks() >= customStacks, "only " + result.customStacks() + " custom stacks compared");
    }

    @Test
    void appendOrderingDoesNot() throws Exception {
        Path directory = CAPTURES.resolve("creative-probe");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(Paletter.bundled(), 2225,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"), CreativeOrdering.APPEND);
        assertFalse(result.matches());
    }
}
