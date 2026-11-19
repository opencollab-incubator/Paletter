package dev.opencollab.paletter.tools.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecReportTest {
    @Test
    void reportNamesTheCodecOfADecodedCapture() {
        Report report = new Report("t");
        report.header.put("Protocol codec", CaptureStage.codecNote(2259, 2225));
        String markdown = report.render();
        assertTrue(markdown.contains("- **Protocol codec**: captures of protocol 2259 decoded with the 2225 codec"), markdown);
    }

    @Test
    void aNativeCodecHasNoNote() {
        assertNull(CaptureStage.codecNote(2225, 2225));
    }
}
