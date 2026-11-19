package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.tools.model.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ids BDS gave the spec add-on's pair of blocks whose hashes collide ({@code hashCollision} in {@code data/model/fixtures.json}),
 * as their creative stacks carry them, against the ids Paletter gives them (docs/wire-notes.md, hash collisions). Paletter compiles
 * the pair alone: which of the two gets the hash depends only on their order among the custom blocks, which is the same.
 */
class HashCollisionCaptureTest {
    /**
     * The builds whose spec capture does not carry the pair yet: the next recapture with the current add-on does. A build whose
     * capture carries it that is still listed fails, and so does one without it that is not listed.
     */
    private static final Set<String> PENDING = Set.of("1.26.36.1", "1.26.45.1", "1.26.52.3", "1.26.63.1", "1.26.70.24");

    @Test
    void everySpecCaptureHasThePairOrIsPendingAndAgreesWithPaletter() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", "..")).toAbsolutePath().normalize();
        List<String> pair = Fixtures.read(root.resolve("data").resolve("model")).hashCollision();
        assertEquals(2, pair.size());
        Paletter paletter = Paletter.builder().previews(true).build();
        PaletteLayer layer = PaletteLayer.builder("hash-collision").block(CustomBlock.builder(pair.get(0)).build())
            .block(CustomBlock.builder(pair.get(1)).build()).build();
        Set<String> without = new TreeSet<>();
        try (var builds = Files.list(root.resolve("data").resolve("bds"))) {
            for (Path build : (Iterable<Path>) builds.filter(Files::isDirectory).sorted()::iterator) {
                Path spec = build.resolve("spec");
                if (!Files.exists(spec.resolve(SpecCapture.CREATIVE_ITEMS))) {
                    continue;
                }
                Map<String, Long> sent = sent(spec.resolve(SpecCapture.CREATIVE_ITEMS));
                if (!sent.keySet().containsAll(pair)) {
                    without.add(build.getFileName().toString());
                    continue;
                }
                int protocol = JsonParser.parseString(Files.readString(spec.resolve("capture.json"))).getAsJsonObject().get("protocol").getAsInt();
                Palette palette = paletter.vanilla(paletter.version(protocol)).extend(layer);
                for (String block : pair) {
                    assertEquals(sent.get(block), Integer.toUnsignedLong(palette.blocks().defaultState(block).getRuntimeId()),
                        "BDS " + build.getFileName() + " gave " + block + " another id than Paletter");
                }
            }
        }
        assertEquals(PENDING, without, "the spec captures without the pair must be PENDING exactly");
    }

    private static Map<String, Long> sent(Path creativeItems) throws IOException {
        Map<String, Long> sent = new HashMap<>();
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(creativeItems)), StandardCharsets.UTF_8)) {
            for (JsonElement e : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("items")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("block_runtime_id")) {
                    sent.putIfAbsent(item.get("id").getAsString(), item.get("block_runtime_id").getAsLong());
                }
            }
        }
        return sent;
    }
}
