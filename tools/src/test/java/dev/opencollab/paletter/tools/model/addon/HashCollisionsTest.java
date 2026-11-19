package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pair of blocks of the spec add-on whose hashes collide: found by the search, kept in the fixtures, written by the add-on. */
class HashCollisionsTest {
    private static final Path MODEL = Path.of(System.getProperty("paletter.root", "..")).resolve("data").resolve("model");

    @Test
    void theFixturesHoldTheFirstCollidingPair() throws IOException {
        List<String> pair = HashCollisions.firstPair(HashCollisions.PREFIX);
        assertEquals(pair, Fixtures.read(MODEL).hashCollision(), "data/model/fixtures.json hashCollision is the pair the search finds");
        assertEquals(BlockHashes.hash(BlockStates.of(pair.get(0))), BlockHashes.hash(BlockStates.of(pair.get(1))));
    }

    @Test
    void theSearchStopsAtTheFirstCollision() {
        List<String> pair = HashCollisions.firstPair("test:x_");
        int last = Integer.parseInt(pair.get(1).substring("test:x_".length()));
        Set<Integer> hashes = new HashSet<>();
        for (int n = 0; n < last; n++) {
            assertTrue(hashes.add(BlockHashes.hash(BlockStates.of("test:x_" + n))), "an earlier collision at " + n);
        }
        assertFalse(hashes.add(BlockHashes.hash(BlockStates.of(pair.get(1)))));
        assertTrue(hashes.contains(BlockHashes.hash(BlockStates.of(pair.get(0)))));
    }

    private static final String COMPONENTS = "{\"source\": \"t\", \"commit\": \"t\", \"license\": \"t\", \"formatNotes\": [], \"components\": ["
        + "{\"id\": \"block:minecraft:x\", \"kind\": \"block\", \"category\": \"component\", \"name\": \"minecraft:x\", \"status\": \"active\","
        + " \"doc\": \"\", \"page\": \"\", \"experiments\": [], \"notes\": [], \"forms\": [{\"type\": \"object\"}], \"fields\": []}]}";
    private static final String FIXTURES = "{\"formatVersion\": \"1.26.60\", \"filler\": {}, \"errata\": [], \"samples\": {}, \"mapKeys\": {\"default\": \"k\"},"
        + " \"context\": {}, \"skip\": {}, \"requires\": {}, \"hashCollision\": {\"blocks\": [\"paletter:hash_1\", \"paletter:hash_2\"]}}";

    private static AddonGenerator generator(Path root, boolean standard) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), COMPONENTS);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Model docs = Model.read(model.resolve("components.json"));
        return standard ? new AddonGenerator(docs, Fixtures.read(model), "1.26.60") : AddonGenerator.empty(docs, Fixtures.read(model), "1.26.60");
    }

    @Test
    void theAddonWritesThePairAsPlainCreativeBlocks(@TempDir Path root) throws IOException {
        Path out = root.resolve("addon");
        generator(root, true).write(out);
        for (String identifier : List.of("paletter:hash_1", "paletter:hash_2")) {
            Path file = out.resolve("behavior_pack/blocks/" + identifier.substring("paletter:".length()) + ".json");
            JsonObject block = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals("1.26.60", block.get("format_version").getAsString());
            JsonObject body = block.getAsJsonObject("minecraft:block");
            JsonObject description = body.getAsJsonObject("description");
            assertEquals(identifier, description.get("identifier").getAsString());
            assertFalse(description.has("states"), "no properties: one state, whose hash is the one searched");
            assertFalse(description.has("traits"));
            assertEquals("construction", description.getAsJsonObject("menu_category").get("category").getAsString(), "in creative: the stack carries the id");
            assertTrue(body.getAsJsonObject("components").isEmpty());
        }
    }

    @Test
    void theProbesDoNotCarryThem(@TempDir Path root) throws IOException {
        Path out = root.resolve("addon");
        generator(root, false).write(out);
        assertFalse(Files.exists(out.resolve("behavior_pack/blocks/hash_1.json")));
    }
}
