package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.tools.CommittedBuilds;
import dev.opencollab.paletter.tools.client.GiveCapture;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed {@code vanilla/block-items.json} of each build ({@link GiveCapture}) against {@code Palette#itemOf} of the data version that serves the build. */
class BlockItemsCaptureTest {
    @Test
    void everyBuildHasItsFileOrIsPendingAndAgreesWithItemOf() throws IOException {
        CommittedBuilds.assertPending("vanilla/" + GiveCapture.FILE, b -> Files.exists(CommittedBuilds.directory(b).resolve("vanilla").resolve(GiveCapture.FILE)));

        // The data version of each build serves its protocol (the spec capture's): the preview build's own preview version, as in the
        // conformance tests
        Paletter paletter = Paletter.builder().previews(true).build();
        for (String name : CommittedBuilds.all()) {
            Path build = CommittedBuilds.directory(name);
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
            assertTrue(check.mismatches().isEmpty(), "BDS " + name + " gave another item than itemOf says: " + check.mismatches());
        }
    }
}
