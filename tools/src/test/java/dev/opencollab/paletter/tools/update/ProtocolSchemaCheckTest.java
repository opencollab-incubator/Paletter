package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The fixtures in {@code src/test/resources/protocol-schema/<build>} are unmodified files of BDS's docgen output
 * ({@code json_schemas/protocol}) of 1.26.63.1 (protocol 2225) and 1.26.70.24 (protocol 2259): PlayStatus is the same in both
 * (but for the stamps), NetworkSettings gained {@code SCTP Recovery Profile} (and its type file) in 2259, StartGamePacket.json is
 * only the wrapper (its payload is not a fixture, so it is missing on both sides and not a difference).
 */
class ProtocolSchemaCheckTest {
    private static Path fixture(String build) throws URISyntaxException {
        return Path.of(ProtocolSchemaCheckTest.class.getResource("/protocol-schema/" + build).toURI());
    }

    @Test
    void protocolIsReadFromTheStamp() throws Exception {
        assertEquals(2225, ProtocolSchemaCheck.protocolOf(fixture("1.26.63.1")));
        assertEquals(2259, ProtocolSchemaCheck.protocolOf(fixture("1.26.70.24")));
        assertEquals(-1, ProtocolSchemaCheck.protocolOf(fixture("1.26.63.1").resolve("json_schemas")));
    }

    @Test
    void findPicksTheTreeOfTheProtocol() throws Exception {
        Path cache = fixture("1.26.63.1").getParent();
        assertEquals("1.26.70.24", ProtocolSchemaCheck.find(cache, 2259).getFileName().toString());
        assertEquals(null, ProtocolSchemaCheck.find(cache, 1));
    }

    @Test
    void changedPacketsListTheFilesThatDiffer() throws Exception {
        ProtocolSchemaCheck.Result result = ProtocolSchemaCheck.compare(fixture("1.26.63.1"), fixture("1.26.70.24"));
        assertFalse(result.safe());
        // PlayStatus and the missing-on-both-sides StartGame payload are no differences; the stamps never are
        assertEquals(List.of("NetworkSettingsPacket"), List.copyOf(result.changed().keySet()));
        assertEquals(List.of("NetworkSettingsPacketPayload.json", "SctpRecoveryProfile.json (new)"), result.changed().get("NetworkSettingsPacket"));
        assertTrue(result.notInSchema().contains("CreativeContentPacket"));
        assertFalse(result.notInSchema().contains("PlayStatusPacket"));
    }

    @Test
    void sameTreeIsSafe() throws Exception {
        assertTrue(ProtocolSchemaCheck.compare(fixture("1.26.63.1"), fixture("1.26.63.1")).safe());
    }

    @Test
    void reportNamesTheChangedPackets() throws Exception {
        Report report = new Report("t");
        ProtocolSchemaCheck.report(report, fixture("1.26.63.1").getParent(), 2225, 2259);
        String markdown = report.render();
        assertTrue(markdown.contains("Protocol codec safety"), markdown);
        assertTrue(markdown.contains("NetworkSettingsPacket"), markdown);
        assertTrue(markdown.contains("SctpRecoveryProfile.json (new)"), markdown);
    }

    @Test
    void reportWithoutATreeIsSkippedNotGuessed() throws Exception {
        Report report = new Report("t");
        ProtocolSchemaCheck.report(report, fixture("1.26.63.1").getParent(), 2225, 9999);
        String markdown = report.render();
        assertTrue(markdown.contains("no cached BDS docgen tree for protocol 9999"), markdown);
        assertFalse(markdown.contains("safe:"), markdown);
    }

    /** The real cached trees: prints what the check says for 2225 to 2259. Skipped without both trees; run with {@code :tools:whiteboxRegression --tests ProtocolSchemaCheckTest}. */
    @Test
    @Tag("binary")
    void cachedTreesOfTheCodecAndTheBuild() throws IOException {
        Path cache = BdsDistribution.defaultCache().resolve("docgen");
        Path codec = ProtocolSchemaCheck.find(cache, 2225);
        Path build = ProtocolSchemaCheck.find(cache, 2259);
        assumeTrue(codec != null && build != null, "docgen of protocols 2225 and 2259 is not cached in " + cache);
        ProtocolSchemaCheck.Result result = ProtocolSchemaCheck.compare(codec, build);
        System.out.println("PROTOCOL CHECK 2225 -> 2259 safe=" + result.safe());
        result.changed().forEach((packet, files) -> System.out.println("PROTOCOL CHECK changed " + packet + ": " + files));
        System.out.println("PROTOCOL CHECK without schema: " + result.notInSchema());
        assertTrue(Files.isDirectory(codec));
    }
}
