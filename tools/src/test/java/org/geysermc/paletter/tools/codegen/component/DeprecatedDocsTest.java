package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The table of deprecated components in {@code docs/api.md} names every one the docs deprecate and every one the API retires. */
class DeprecatedDocsTest {
    @Test
    void everyDeprecatedAndRetiredComponentIsInTheTable() throws IOException {
        String docs = Files.readString(ComponentConformanceTest.ROOT.resolve("docs/api.md"), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        JsonObject model = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("components.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : model.getAsJsonArray("components")) {
            JsonObject component = e.getAsJsonObject();
            String status = component.get("status").getAsString();
            if (component.get("category").getAsString().equals("component") && (status.equals("deprecated") || status.equals("legacy"))) {
                check(docs, component.get("id").getAsString(), missing);
            }
        }
        JsonObject api = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("api.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        api.getAsJsonObject("retired").keySet().stream().filter(id -> !id.startsWith("_")).forEach(id -> check(docs, id, missing));
        assertTrue(missing.isEmpty(), "docs/api.md (Deprecated components) has no row for: " + missing);
    }

    private static void check(String docs, String id, List<String> missing) {
        String kind = id.substring(0, id.indexOf(':'));
        String name = "`" + id.substring(id.indexOf(':') + 1) + "`";
        if (!docs.contains("| " + kind + " " + name) && !docs.contains("| block and item " + name)) {
            missing.add(id);
        }
    }
}
