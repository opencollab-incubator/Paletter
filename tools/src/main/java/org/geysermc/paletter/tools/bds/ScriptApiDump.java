package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Layout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the Script API dump add-on ({@code data/addons/dump}) on an extracted BDS. The add-on finds the aux value of a
 * tipped arrow through loot tables, one {@code set_potion} table per potion effect, so this first runs BDS's own
 * documentation generator ({@code docs/vanilladata_modules/mojang-potion-effects.json}) and installs the tables with
 * the add-on. No effect is listed by hand.
 */
public final class ScriptApiDump {
    private static final String EFFECTS_FILE = "docs/vanilladata_modules/mojang-potion-effects.json";

    private ScriptApiDump() {
    }

    /** Runs the add-on; its console goes to {@code log}. */
    public static void run(Path root, Path server, Path log, int timeout, int port) throws IOException, InterruptedException {
        List<String> effects = potionEffects(server, log.resolveSibling("bds-docgen.log"), timeout, port);
        new BdsRunner(root.resolve(Layout.DUMP_ADDON)).withPackFiles(lootTables(effects)).run(server, log, timeout, port, false);
    }

    /** The loot tables of the add-on: one arrow with {@code set_potion} per effect, by path inside the pack. */
    static Map<String, String> lootTables(List<String> effects) {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String effect : effects) {
            String path = effect.substring(effect.indexOf(':') + 1);
            tables.put("loot_tables/paletter/arrow_" + path + ".json", "{\"pools\": [{\"rolls\": 1, \"entries\": [{\"type\": \"item\", "
                + "\"name\": \"minecraft:arrow\", \"functions\": [{\"function\": \"set_potion\", \"id\": \"" + path + "\"}]}]}]}");
        }
        return tables;
    }

    /** The potion effect identifiers of BDS's documentation generator ({@code test_config.json}; BDS stops by itself). */
    static List<String> potionEffects(Path server, Path log, int timeout, int port) throws IOException, InterruptedException {
        BdsRunner.configure(server, port);
        Path config = server.resolve("test_config.json");
        Files.writeString(config, "{\"generate_documentation\": true}");
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(log);
            if (bds.await(line -> line.contains("Server stop requested."), timeout) == null) {
                throw new IOException("BDS did not generate its documentation within " + timeout + "s; see " + log);
            }
        } finally {
            // The file makes BDS stop right after it starts
            Files.deleteIfExists(config);
        }
        List<String> effects = new ArrayList<>();
        String json = Files.readString(server.resolve(EFFECTS_FILE));
        for (JsonElement item : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data_items")) {
            effects.add(item.getAsJsonObject().get("name").getAsString());
        }
        return effects;
    }
}
