package org.geysermc.paletter.tools.update;

import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.Schema;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecCaptureTest {
    @Test
    void anInstalledPackWithTheSpecAddonsUuidIsRemoved(@TempDir Path packs) throws IOException {
        pack(packs.resolve("paletter_capture"), AddonGenerator.BP_UUID);
        pack(packs.resolve("vanilla"), "0575c61f-a5da-4b7f-9961-ffda2908861e");
        Files.createDirectories(packs.resolve("no_manifest"));
        SpecCapture.removePacksWithUuid(packs, AddonGenerator.BP_UUID);
        assertFalse(Files.exists(packs.resolve("paletter_capture")), "BDS would load it for the spec world");
        assertTrue(Files.exists(packs.resolve("vanilla/manifest.json")));
        assertTrue(Files.exists(packs.resolve("no_manifest")));
    }

    /** An error that names no variant belongs to none: it is not guessed from the lines around it. */
    @Test
    void anErrorWithoutAVariantIsAttributedToNone() {
        List<String> console = List.of("ERROR [Item] Error Parsing Item 'paletter:a':",
            "ERROR [Item] Failed to parse field x",
            "ERROR [Json] Damage Source not found: paletter_b",
            "ERROR [Components] failed in blocks/b_one.json");
        Map<String, List<String>> log = SpecCapture.logByVariant(SpecCapture.attribute(console));
        assertEquals(List.of("paletter:a", "paletter:b_one"), List.copyOf(log.keySet()).stream().sorted().toList());
        assertEquals(2, log.get("paletter:a").size(), "the field error carries the item name");
        assertTrue(log.values().stream().flatMap(List::stream).noneMatch(line -> line.contains("Damage Source")));
    }

    /** A capture-only run commits nothing, and a committed schema can be older than the run's: the add-on takes the run's own. */
    @Test
    void theAddonTakesTheSchemaTheRunExtractedNotTheCommittedOne(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("data/model"));
        Files.writeString(model.resolve("fixtures.json"), "{\"formatVersion\": \"1.26.60\", \"filler\": {}}");
        Files.createDirectories(root.resolve("data/bds/1.26.99.1"));
        // The committed one, as an older reader wrote it: no components
        Files.writeString(root.resolve("data/bds/1.26.99.1/schema.json"), "{\"bdsVersion\": \"1.26.99.1\", \"formats\": {}}");
        Schema extracted = Schema.of(JsonParser.parseString("{\"bdsVersion\": \"1.26.99.2\", \"formats\": {\"block\": [\"1.26.60\"]}, \"block\": "
            + "{\"minecraft:box\": [{\"formats\": [\"1.26.60\"], \"root\": {\"types\": [\"object\"]}, \"fields\": {}}]}}").getAsJsonObject());
        assertEquals("1.26.99.2", SpecCapture.fixtures(model, "1.26.99.2", extracted).schemaBuild(), "the run's own schema wins");
    }

    private static void pack(Path directory, String uuid) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"), "{\"format_version\": 2, \"header\": {\"uuid\": \"" + uuid + "\"}}");
    }
}
