package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bundled preview ({@code <name> <protocol> preview} in {@code versions.txt}) is served only by a Paletter built with
 * {@code previews(true)}: then its protocol gets the preview's palette, else the newest release's. It is a setting of
 * the instance, so Paletters with different settings coexist.
 */
class PreviewDataTest {
    private final Paletter releases = Paletter.builder().build();
    private final Paletter withPreviews = Paletter.builder().previews(true).build();

    private DataVersion preview() {
        return withPreviews.versions().stream().filter(DataVersion::isPreview).findFirst().orElseThrow();
    }

    @Test
    void previewsAreOffByDefault() {
        DataVersion preview = preview();
        assertFalse(releases.versions().contains(preview));
        assertTrue(releases.versions().stream().noneMatch(DataVersion::isPreview));
        assertFalse(Paletter.bundled().versions().contains(preview));
        assertTrue(releases.palette(preview.protocolVersion()).version().protocolVersion() < preview.protocolVersion());
    }

    @Test
    void previewsAreServedOnRequest() {
        DataVersion preview = preview();
        assertEquals(preview, withPreviews.latest());
        assertEquals(preview, withPreviews.palette(preview.protocolVersion()).version());
        assertEquals(releases.versions(), withPreviews.versions().stream().filter(version -> !version.isPreview()).toList());
    }

    @Test
    void instancesWithDifferentSettingsCoexist() {
        DataVersion preview = preview();
        // Interleaved: neither the other instance nor the order of use changes what an instance serves
        Paletter first = Paletter.builder().previews(true).build();
        Paletter second = Paletter.builder().previews(false).build();
        assertEquals(preview, first.palette(preview.protocolVersion()).version());
        assertNotEquals(preview, second.palette(preview.protocolVersion()).version());
        assertEquals(preview, first.latest());
        assertNotEquals(preview, second.latest());
    }

    @Test
    void previewConstantsResolveThroughFallbacks() {
        // BRITTLE_ICE is only in the 26.70 preview, and the constants are linked to the newest release
        assertFalse(BedrockBlocks.BRITTLE_ICE.isAvailable());
        assertNotEquals(BedrockBlocks.BRITTLE_ICE, BedrockBlocks.BRITTLE_ICE.resolved());
    }
}
