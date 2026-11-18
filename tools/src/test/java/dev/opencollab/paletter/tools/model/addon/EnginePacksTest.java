package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packs with an older {@code min_engine_version} that show whether the {@code molangVersion} follows it. */
class EnginePacksTest {
    private static final String MODEL = "{\"source\": \"t\", \"commit\": \"t\", \"license\": \"t\", \"formatNotes\": [], \"components\": ["
        + "{\"id\": \"block:minecraft:x\", \"kind\": \"block\", \"category\": \"component\", \"name\": \"minecraft:x\", \"status\": \"active\","
        + " \"doc\": \"\", \"page\": \"\", \"experiments\": [], \"notes\": [], \"forms\": [{\"type\": \"object\"}], \"fields\": []}]}";
    private static final String FIXTURES = "{\"formatVersion\": \"1.26.60\", \"filler\": {}, \"errata\": [], \"samples\": {}, \"mapKeys\": {\"default\": \"k\"},"
        + " \"context\": {}, \"skip\": {}, \"requires\": {}}";

    private static AddonGenerator generator(Path root, boolean standard) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Model docs = Model.read(model.resolve("components.json"));
        return standard ? new AddonGenerator(docs, Fixtures.read(model), "1.26.60") : AddonGenerator.empty(docs, Fixtures.read(model), "1.26.60");
    }

    @Test
    void eachEngineVersionIsAPackWithOnlyAControlBlockInTheSameFormat(@TempDir Path root) throws IOException {
        Path out = root.resolve("addon");
        generator(root, true).write(out);
        assertEquals(AddonWriter.ENGINE_VERSIONS.size(), AddonWriter.enginePacks().size());
        for (AddonWriter.EnginePack pack : AddonWriter.enginePacks()) {
            JsonObject manifest = JsonParser.parseString(Files.readString(out.resolve(pack.directory()).resolve("manifest.json"))).getAsJsonObject();
            String[] version = pack.minEngineVersion().split("\\.");
            JsonObject header = manifest.getAsJsonObject("header");
            for (int i = 0; i < 3; i++) {
                assertEquals(Integer.parseInt(version[i]), header.getAsJsonArray("min_engine_version").get(i).getAsInt());
            }
            assertEquals(pack.uuid(), header.get("uuid").getAsString());
            Path block = out.resolve(pack.directory()).resolve("blocks/" + pack.identifier().substring("paletter:".length()) + ".json");
            JsonObject file = JsonParser.parseString(Files.readString(block)).getAsJsonObject();
            assertEquals("1.26.60", file.get("format_version").getAsString(), "the engine version is the only difference to the main pack");
            assertEquals(pack.identifier(), file.getAsJsonObject("minecraft:block").getAsJsonObject("description").get("identifier").getAsString());
            assertTrue(file.getAsJsonObject("minecraft:block").getAsJsonObject("components").isEmpty(), "a control: no component");
        }
        assertEquals(AddonWriter.enginePacks().stream().map(AddonWriter.EnginePack::uuid).distinct().count(), AddonWriter.enginePacks().size());
    }

    @Test
    void theProbesDoNotCarryThem(@TempDir Path root) throws IOException {
        Path out = root.resolve("addon");
        generator(root, false).write(out);
        assertFalse(Files.exists(out.resolve(AddonWriter.enginePacks().get(0).directory())));
    }
}
