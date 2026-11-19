package dev.opencollab.paletter.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lines of {@code versions.txt}: {@code <name> <protocol> [preview] [codec=<protocol>]}. */
class VersionIndexTest {
    @Test
    void aLineGivesTheVersionItsPreviewFlagAndCodec() {
        DataVersion release = FileDataSource.indexLine("26.60 2225");
        assertEquals(new DataVersion("26.60", 2225), release);
        assertFalse(release.isPreview());
        assertNull(release.codecProtocol());

        DataVersion preview = FileDataSource.indexLine("26.70 2259 preview codec=2225");
        assertTrue(preview.isPreview());
        assertEquals(2225, preview.codecProtocol());
        // The codec does not identify the version
        assertEquals(new DataVersion("26.70", 2259), preview);

        assertEquals(2259, FileDataSource.indexLine("26.80 2300 codec=2259").codecProtocol());
    }

    @Test
    void unknownOrBrokenFieldsFail() {
        for (String line : List.of("26.60", "26.60 x", "26.60 2225 release", "26.70 2259 codec=", "26.70 2259 codec=1 codec=2",
            "26.70 2259 preview preview")) {
            assertThrows(IllegalArgumentException.class, () -> FileDataSource.indexLine(line), line);
        }
    }

    @Test
    void aDirectorySourceReadsTheCodec(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("versions.txt"), "# comment\n26.60 2225\n26.70 2259 preview codec=2225\n");
        List<DataVersion> versions = PaletteDataSource.directory(directory).versions();
        assertEquals(List.of(new DataVersion("26.60", 2225), new DataVersion("26.70", 2259)), versions);
        assertNull(versions.getFirst().codecProtocol());
        assertEquals(2225, versions.getLast().codecProtocol());

        Files.writeString(directory.resolve("versions.txt"), "26.70 2259 beta\n");
        assertThrows(IllegalStateException.class, () -> PaletteDataSource.directory(directory).versions());
    }
}
