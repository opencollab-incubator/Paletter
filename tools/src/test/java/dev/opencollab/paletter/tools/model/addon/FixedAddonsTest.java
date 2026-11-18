package dev.opencollab.paletter.tools.model.addon;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.tools.data.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated add-ons are the committed ones (data/addons), and the large probe is the one that was captured. */
class FixedAddonsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    /**
     * The format version is kept twice by hand (the fixed packs' constant and fixtures.json): bumping one alone would give the
     * captures of one build two formats. Every committed spec capture was made with the one the build can load.
     */
    @Test
    void theFormatVersionHasOneValueAndEveryCommittedCaptureUsedIt() throws IOException {
        dev.opencollab.paletter.tools.model.Fixtures fixtures = dev.opencollab.paletter.tools.model.Fixtures.read(ROOT.resolve("data/model"));
        assertEquals(FixedAddons.FORMAT_VERSION, fixtures.formatVersion, "FixedAddons.FORMAT_VERSION and fixtures.json formatVersion differ");
        try (Stream<Path> builds = Files.list(ROOT.resolve("data/bds"))) {
            for (Path build : builds.filter(Files::isDirectory).toList()) {
                Path capture = build.resolve("spec/capture.json");
                if (Files.exists(capture)) {
                    String used = com.google.gson.JsonParser.parseString(Files.readString(capture)).getAsJsonObject().get("formatVersion").getAsString();
                    assertEquals(AddonGenerator.formatVersionFor(fixtures.formatVersion, build.getFileName().toString()), used,
                        capture + " was made with another format version than the model's (recapture after a bump)");
                }
            }
        }
    }

    @Test
    void packsOfAnOlderBuildAskForItsFormatVersion(@TempDir Path out) throws IOException {
        FixedAddons.writeCreative(out.resolve("creative"), "1.26.30");
        for (String manifest : List.of("creative/behavior_pack/manifest.json")) {
            String text = Files.readString(out.resolve(manifest));
            assertTrue(text.replaceAll("\\s", "").contains("\"min_engine_version\":[1,26,30]"), manifest + ": " + text);
        }
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path file : walk.filter(f -> List.of("items", "blocks").contains(f.getParent().getFileName().toString())).filter(Files::isRegularFile)
                .filter(f -> f.toString().endsWith(".json") && !f.toString().contains("block_culling")).toList()) {
                String text = Files.readString(file);
                if (text.contains("\"format_version\"") && !file.toString().contains("models") && !file.toString().contains("textures")) {
                    assertTrue(text.contains("\"format_version\": \"1.26.30\""), file + ": " + text.substring(0, 80));
                }
            }
        }
    }

    @Test
    void creativeAddonIsTheCommittedPack(@TempDir Path out) throws IOException {
        FixedAddons.writeCreative(out);
        assertSame(out.resolve("behavior_pack"), ROOT.resolve("data/addons/creative/behavior_pack"));
    }

    @Test
    void probesDoNotShareTheUuidsOfTheSpecAddon(@TempDir Path out) throws IOException {
        // update installs them on the server the spec capture reuses, and a world loads its packs by UUID
        FixedAddons.writeCreative(out.resolve("creative"));
        FixedAddons.writeCreativeLarge(out.resolve("large"));
        for (Path manifest : List.of(out.resolve("creative/behavior_pack/manifest.json"), out.resolve("large/behavior_pack/manifest.json"))) {
            String uuid = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject()
                .getAsJsonObject("header").get("uuid").getAsString();
            assertTrue(!uuid.equals(AddonGenerator.BP_UUID) && !uuid.equals(AddonGenerator.RP_UUID), manifest + ": " + uuid);
        }
    }

    @Test
    void largeProbeHasTheCapturedIdentifiers(@TempDir Path out) throws IOException {
        FixedAddons.writeCreativeLarge(out);
        Path capture = ROOT.resolve("data/bds/1.26.63.1/creative-large");
        java.util.Set<String> unique = new java.util.TreeSet<>();
        for (String file : List.of("custom_block_properties.nbt", "custom_item_registry.nbt")) {
            NbtMap data = Nbt.read(capture.resolve(file));
            data.getList("blocks", NbtType.COMPOUND, List.of()).forEach(b -> unique.add(b.getString("name")));
            data.getList("items", NbtType.COMPOUND, List.of()).forEach(i -> unique.add(i.getString("name")));
        }
        List<String> captured = new ArrayList<>(unique);
        assertEquals(740, captured.size());
        assertEquals(captured, FixedAddons.largeIdentifiers(out.resolve("behavior_pack")));
    }

    private static void assertSame(Path generated, Path committed) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(committed)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> made;
        try (Stream<Path> walk = Files.walk(generated)) {
            made = walk.filter(Files::isRegularFile).sorted().toList();
        }
        assertEquals(files.stream().map(committed::relativize).toList(), made.stream().map(generated::relativize).toList());
        for (Path file : files) {
            assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(generated.resolve(committed.relativize(file))), file.toString());
        }
    }
}
