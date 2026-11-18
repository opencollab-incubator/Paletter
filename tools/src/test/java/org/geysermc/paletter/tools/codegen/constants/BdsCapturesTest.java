package org.geysermc.paletter.tools.codegen.constants;

import org.geysermc.paletter.tools.codegen.constants.ConstantsGenerator.Bundle;
import org.geysermc.paletter.tools.codegen.constants.ConstantsGenerator.Sources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the BDS captures: the toggle that gates content in a release, Education Edition content, build ordering. */
class BdsCapturesTest {
    private static final List<Bundle> BUNDLES = List.of(new Bundle("1.26.40", 100, false), new Bundle("1.26.50", 200, true));

    @Test
    void buildsAreOrderedByTheirNumbers() {
        assertTrue(BdsCaptures.compareBuilds("1.26.63.1", "1.26.100.2") < 0);
        assertTrue(BdsCaptures.compareBuilds("1.26.63.1", "1.26.63") > 0);
        assertEquals(0, BdsCaptures.compareBuilds("1.26.63.1", "1.26.63.1"));
        assertThrows(IllegalStateException.class, () -> BdsCaptures.compareBuilds("1.26.x", "1.26.1"));
    }

    @Test
    void aProtocolBelongsToTheNewestBundleNotAboveIt() {
        assertEquals("1.26.40", Sources.bundleOf(BUNDLES, 150).name());
        assertEquals("1.26.50", Sources.bundleOf(BUNDLES, 200).name());
        assertEquals(null, Sources.bundleOf(BUNDLES, 50));
    }

    @Test
    void currentTogglesGateContentOfTheirRelease(@TempDir Path bds) throws IOException {
        Path capture = Files.createDirectories(bds.resolve("1.26.41.1"));
        Files.writeString(capture.resolve("experiments.json"), """
            {"protocol": 120, "toggles": {
              "drop": {"status": "current", "content": {"itemsAdded": ["minecraft:a"], "blocksAdded": ["minecraft:b"]}},
              "old": {"status": "retired", "content": {"itemsAdded": ["minecraft:c"]}}}}
            """, StandardCharsets.UTF_8);
        Files.writeString(capture.resolve("education.json"), "{\"items\": [\"minecraft:e\"], \"blocks\": [\"minecraft:f\"]}", StandardCharsets.UTF_8);

        BdsCaptures captures = BdsCaptures.read(BUNDLES, bds);

        assertEquals(Map.of("1.26.40", Map.of("minecraft:a", "drop")), captures.gatedItems());
        assertEquals(Map.of("1.26.40", Map.of("minecraft:b", "drop")), captures.gatedBlocks());
        assertEquals(Set.of("minecraft:e"), captures.educationItems());
        assertEquals(Set.of("minecraft:f"), captures.educationBlocks());
    }

    @Test
    void aPreviewCaptureSaysNothingOfTheReleaseBeforeIt(@TempDir Path bds) throws IOException {
        Path capture = Files.createDirectories(bds.resolve("1.26.50.1"));
        Files.writeString(capture.resolve("experiments.json"),
            "{\"protocol\": 210, \"toggles\": {\"drop\": {\"status\": \"current\", \"content\": {\"itemsAdded\": [\"minecraft:a\"]}}}}", StandardCharsets.UTF_8);

        assertTrue(BdsCaptures.read(BUNDLES, bds).gatedItems().isEmpty());
        assertTrue(BdsCaptures.read(BUNDLES, null).gatedItems().isEmpty());
    }
}
