package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Map<String, List<String>> log = SpecCapture.logByVariant(SpecCapture.attribute(console), List.of("paletter:a", "paletter:b_one"));
        assertEquals(List.of("paletter:a", "paletter:b_one"), List.copyOf(log.keySet()).stream().sorted().toList());
        assertEquals(2, log.get("paletter:a").size(), "the field error carries the item name");
        assertTrue(log.values().stream().flatMap(List::stream).noneMatch(line -> line.contains("Damage Source")));
    }

    /** A reworded error that names no variant, or one about a name the add-on lacks, fails the capture with the line quoted. */
    @Test
    void anUnknownOrUnattributedLineFailsTheCapture() {
        List<String> variants = List.of("paletter:a", "paletter:b_one");
        // BDS reworded the item header: the field error after it names no item any more
        List<String> reworded = SpecCapture.attribute(List.of("ERROR [Item] Could not read item 'paletter:a'",
            "ERROR [Item] Failed to parse field ' -> components -> minecraft:icon: invalid string'"));
        SpecCapture.UnattributedLog e = assertThrows(SpecCapture.UnattributedLog.class, () -> SpecCapture.logByVariant(reworded, variants));
        assertTrue(e.getMessage().contains("ERROR [Item] Failed to parse field ' -> components -> minecraft:icon: invalid string'"), e.getMessage());
        assertFalse(e.getMessage().contains("Could not read item"), "the line that names a variant is attributed");
        // A warning in no known phrasing
        assertThrows(SpecCapture.UnattributedLog.class, () -> SpecCapture.logByVariant(List.of("WARN [Blocks] something new"), variants));
        // A line about an identifier that is no variant of the add-on
        e = assertThrows(SpecCapture.UnattributedLog.class, () -> SpecCapture.logByVariant(List.of("ERROR [Components] failed in blocks/c.json"), variants));
        assertTrue(e.getMessage().contains("blocks/c.json"));
    }

    /** The capture does not fail on an unknown line: the line is handed to the report, and the variants' lines are still attributed. */
    @Test
    void anUnknownLineIsCollectedForTheReportNotThrown() {
        List<String> unknown = new ArrayList<>();
        Map<String, List<String>> log = SpecCapture.logByVariant(
            List.of("WARN [Scripting] something new", "ERROR [Components] failed in blocks/a.json"), List.of("paletter:a"), unknown);
        assertEquals(List.of("WARN [Scripting] something new"), unknown);
        assertEquals(List.of("paletter:a"), List.copyOf(log.keySet()));
    }

    /** A BDS start hang is retried once; another failure, or a second hang, is not. */
    @Test
    void aStartHangIsRetriedOnce() throws Exception {
        int[] starts = {0};
        assertEquals("ok", BdsServer.retryOnHang(() -> {
            if (starts[0]++ == 0) {
                throw new IOException("BDS did not start within 1s:\n  log");
            }
            return "ok";
        }));
        assertEquals(2, starts[0]);
        starts[0] = 0;
        assertThrows(IOException.class, () -> BdsServer.retryOnHang(() -> {
            starts[0]++;
            throw new IOException("BDS did not start within 1s");
        }));
        assertEquals(2, starts[0]);
        starts[0] = 0;
        assertThrows(IOException.class, () -> BdsServer.retryOnHang(() -> {
            starts[0]++;
            throw new IOException("other");
        }));
        assertEquals(1, starts[0]);
    }

    /** Every committed spec capture's content log is attributed in full: the known phrasings come from these logs. */
    @Test
    void everyCommittedContentLogIsAttributed() throws IOException {
        Path bds = Path.of("..", "data", "bds");
        int checked = 0;
        try (Stream<Path> logs = Files.walk(bds)) {
            for (Path log : logs.filter(p -> p.getFileName().toString().equals("content-log.txt")
                && p.getParent().getFileName().toString().startsWith("spec")).toList()) {
                List<String> identifiers = new ArrayList<>();
                JsonParser.parseString(Files.readString(log.resolveSibling("variants.json"))).getAsJsonArray()
                    .forEach(v -> identifiers.add(v.getAsJsonObject().get("identifier").getAsString()));
                try {
                    SpecCapture.logByVariant(Files.readAllLines(log), identifiers);
                } catch (SpecCapture.UnattributedLog e) {
                    throw new AssertionError(log + ": " + e.getMessage(), e);
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "no committed spec capture under " + bds.toAbsolutePath());
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
        assertEquals("1.26.99.2", SpecCapture.fixtures(model, "1.26.99.2", extracted, true).schemaBuild(), "the run's own schema wins");
        assertEquals(null, SpecCapture.fixtures(model, "1.26.99.2", extracted, false).schemaBuild(), "with the switch off the run's schema is ignored");
    }

    private static void pack(Path directory, String uuid) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"), "{\"format_version\": 2, \"header\": {\"uuid\": \"" + uuid + "\"}}");
    }
}
