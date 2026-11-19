package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code versions.txt}: its lines, and the codec it records coming from the captures. */
class VersionIndexTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void linesRoundTrip() {
        for (String line : List.of("26.60 2225", "26.70 2259 preview", "26.70 2259 preview codec=2225", "26.80 2300 codec=2259")) {
            assertEquals(line, Importer.IndexEntry.parse(line).line());
        }
        Importer.IndexEntry entry = Importer.IndexEntry.parse("26.70 2259 preview codec=2225");
        assertEquals(new Importer.IndexEntry("26.70", 2259, true, 2225), entry);
        assertNull(Importer.IndexEntry.parse("26.60 2225").codecProtocol());
    }

    @Test
    void unknownOrBrokenFieldsFail() {
        for (String line : List.of("26.60", "26.60 x", "26.60 2225 release", "26.70 2259 codec=", "26.70 2259 codec=1 codec=2",
            "26.70 2259 preview preview")) {
            assertThrows(IllegalArgumentException.class, () -> Importer.IndexEntry.parse(line), line);
        }
    }

    /** The codec comes from the capture's {@code capture.json}: only a capture decoded with another codec records one. */
    @Test
    void theCodecComesFromTheCapture(@TempDir Path temp) throws IOException {
        Path none = Files.createDirectories(temp.resolve("none"));
        assertNull(Importer.codecProtocol(none));
        Path own = Files.createDirectories(temp.resolve("own"));
        Files.writeString(own.resolve("capture.json"), "{\"protocol\": 2225}");
        assertNull(Importer.codecProtocol(own));
        Path other = Files.createDirectories(temp.resolve("other"));
        Files.writeString(other.resolve("capture.json"), "{\"protocol\": 2259, \"codecProtocol\": 2225}");
        assertEquals(2225, Importer.codecProtocol(other));
    }

    /**
     * Every bundled version records the codec its spec captures in {@code data/bds} (those of its own protocol) were
     * decoded with: {@code codec=} where that was another protocol's, nothing where it was its own or there is none. The
     * spec capture is the one of an update run that is kept whole; the other captures of a build may predate
     * {@code codecProtocol} being recorded, so their lack of it says nothing.
     */
    @Test
    void theBundledIndexRecordsTheCodecOfTheCaptures() throws IOException {
        List<Importer.IndexEntry> index = Importer.readIndex(ROOT.resolve(ConstantsGenerator.DATA));
        List<JsonObject> captures;
        try (Stream<Path> builds = Files.list(ROOT.resolve(ConstantsGenerator.BDS))) {
            captures = builds.map(build -> build.resolve("spec/capture.json")).filter(Files::isRegularFile).map(file -> {
                try {
                    return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).filter(json -> json.has("protocol")).toList();
        }
        for (Importer.IndexEntry entry : index) {
            Set<Integer> codecs = new TreeSet<>();
            captures.stream().filter(json -> json.get("protocol").getAsInt() == entry.protocol())
                .forEach(json -> codecs.add(json.has("codecProtocol") ? json.get("codecProtocol").getAsInt() : entry.protocol()));
            assertTrue(codecs.size() <= 1, entry.name() + ": its captures were decoded with different codecs " + codecs);
            Integer expected = codecs.isEmpty() || codecs.contains(entry.protocol()) ? null : codecs.iterator().next();
            assertEquals(expected, entry.codecProtocol(), entry.name() + ": versions.txt does not record the codec of its captures");
        }
    }
}
