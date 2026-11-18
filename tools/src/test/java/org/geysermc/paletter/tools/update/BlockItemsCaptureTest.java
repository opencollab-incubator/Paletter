package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.tools.client.GiveCapture;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed {@code vanilla/block-items.json} of each build ({@link GiveCapture}) against {@code Palette#itemOf} of the data version that serves the build. */
class BlockItemsCaptureTest {
    /**
     * The builds without a {@code block-items.json} in their vanilla capture yet: the recapture's {@code /give} start writes it. A build
     * with the file that is still listed fails, and so does one without it that is not listed.
     */
    private static final Set<String> PENDING = Set.of("1.26.36.1", "1.26.45.1", "1.26.52.3", "1.26.63.1", "1.26.70.24");

    @Test
    void everyBuildHasItsFileOrIsPendingAndAgreesWithItemOf() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", "..")).toAbsolutePath().normalize();
        Path data = root.resolve("data").resolve("bds");
        Set<String> without = new TreeSet<>();
        try (var builds = Files.list(data)) {
            builds.filter(Files::isDirectory).filter(b -> !Files.exists(b.resolve("vanilla").resolve(GiveCapture.FILE)))
                .forEach(b -> without.add(b.getFileName().toString()));
        }
        assertEquals(PENDING, without, "the builds without vanilla/block-items.json must be PENDING exactly");

        // The data version of each build serves its protocol (the spec capture's): the preview build's own preview version, as in the
        // conformance tests
        Paletter paletter = Paletter.builder().previews(true).build();
        try (var builds = Files.list(data)) {
            for (Path build : (Iterable<Path>) builds.filter(Files::isDirectory).sorted()::iterator) {
                Path file = build.resolve("vanilla").resolve(GiveCapture.FILE);
                if (!Files.exists(file)) {
                    continue;
                }
                int protocol = JsonParser.parseString(Files.readString(build.resolve("spec").resolve("capture.json"))).getAsJsonObject().get("protocol").getAsInt();
                Palette palette = paletter.vanilla(paletter.version(protocol));
                GiveCapture.Check check = GiveCapture.check(GiveCapture.read(file), block -> {
                    BedrockItemType item = palette.itemOf(BedrockBlockType.fromIdentifier(block));
                    return item == null ? null : item.identifier();
                });
                assertTrue(check.mismatches().isEmpty(), "BDS " + build.getFileName() + " gave another item than itemOf says: " + check.mismatches());
            }
        }
    }
}
