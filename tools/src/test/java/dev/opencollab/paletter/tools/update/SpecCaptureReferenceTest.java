package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.client.CaptureWriter;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator;
import dev.opencollab.paletter.tools.data.BundledFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bundled palette the spec capture resolves block hashes with, on the creative block ids BDS 1.26.45.1 sent in a
 * spec capture (fixture {@code bds-1.26.45.1/spec-creative-block-ids.json}: those matching no state of 26.40 or 26.60).
 * That build speaks protocol 2169, one above the first protocol of its data version 26.40; resolved against the newest
 * release (26.60) instead, the stairs, fences, panes, bars and chorus plant (whose states changed in 26.50 and 26.60)
 * matched no state and failed the leg.
 */
class SpecCaptureReferenceTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static JsonObject fixture() throws IOException {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(SpecCaptureReferenceTest.class.getResourceAsStream(
            "/bds-1.26.45.1/spec-creative-block-ids.json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<String> unresolved(VanillaData palette, JsonObject fixture) {
        Set<Integer> hashes = new HashSet<>();
        palette.blockStates().forEach(state -> hashes.add(BlockHashes.hash(state)));
        List<String> unresolved = new ArrayList<>();
        fixture.getAsJsonObject("creativeBlockIds").entrySet().forEach(entry -> {
            if (!hashes.contains(Integer.parseUnsignedInt(entry.getKey()))) {
                unresolved.add(entry.getValue().getAsString());
            }
        });
        return unresolved;
    }

    @Test
    void aBuildAboveTheFirstProtocolOfItsDataVersionResolvesWithThatVersion() throws IOException {
        JsonObject fixture = fixture();
        VanillaData reference = SpecCapture.reference(ROOT, fixture.get("protocol").getAsInt());
        assertEquals("26.40", reference.version().name());
        // What still matches no state are the y_2026_drop_3 wool slabs and stairs: 26.40 marks them as experiment content
        // without states, so the capture keeps their ids apart as unverified instead of failing on them
        Set<String> experimentBlocks = CaptureWriter.experimentBlocksWithoutStates(reference);
        List<String> unresolved = unresolved(reference, fixture);
        assertEquals(32, unresolved.size());
        assertTrue(experimentBlocks.containsAll(unresolved), unresolved::toString);
    }

    @Test
    void theNewestReleaseLacksTheirStates() throws IOException {
        JsonObject fixture = fixture();
        VanillaData newest = BundledFiles.read(ROOT.resolve(ConstantsGenerator.DATA).resolve("26.60.nbt"));
        List<String> unresolved = unresolved(newest, fixture);
        // The 122 ids of the failed leg: 65 stairs, 17 panes, 16 wool stairs, 14 fences, 9 bars and the chorus plant
        assertEquals(122, unresolved.size());
        assertTrue(unresolved.contains("minecraft:oak_stairs") && unresolved.contains("minecraft:oak_fence"), unresolved::toString);
        assertEquals(Set.of(), CaptureWriter.experimentBlocksWithoutStates(newest));
    }
}
