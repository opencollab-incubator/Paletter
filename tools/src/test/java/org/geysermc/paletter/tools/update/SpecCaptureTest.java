package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static void pack(Path directory, String uuid) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"), "{\"format_version\": 2, \"header\": {\"uuid\": \"" + uuid + "\"}}");
    }
}
