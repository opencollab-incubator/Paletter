package dev.opencollab.paletter.tools.whitebox;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The analysis of one known BDS binary gives the committed facts, and they pass the self-checks. Needs the Linux zip of
 * the build in the BDS cache, which CI does not have: {@code ./gradlew :tools:whiteboxRegression}, never part of
 * {@code check}. Without the zip the test is skipped; it never downloads.
 */
@Tag("binary")
class WhiteboxBinaryTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final String BUILD = "1.26.63.1";

    @Test
    void analysisOfAKnownBuildIsTheCommittedFacts() throws IOException, InterruptedException {
        Path zip = BdsDistribution.defaultCache().resolve("zips").resolve("linux").resolve("bedrock-server-" + BUILD + ".zip");
        assumeTrue(Files.isRegularFile(zip), zip + " is not cached");

        WhiteboxRun run = WhiteboxRun.of(zip, ROOT, BUILD);
        assertEquals(List.of(), run.problems());
        JsonObject committed = Whitebox.parse(ROOT.resolve(Layout.BDS).resolve(BUILD).resolve(Whitebox.FILE));
        assertEquals(Whitebox.json(committed), Whitebox.json(run.facts()));
        assertEquals(Files.readString(ROOT.resolve(Layout.BDS).resolve(BUILD).resolve(WhiteboxCoverage.FILE)), run.coverage());
    }
}
