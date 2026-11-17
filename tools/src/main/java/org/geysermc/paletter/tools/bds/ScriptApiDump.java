package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.data.BundledFiles;
import org.geysermc.paletter.tools.data.VariantNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Runs the Script API dump add-on ({@code data/addons/dump}) on an extracted BDS. The add-on finds the aux value of a
 * tipped arrow through loot tables, one {@code set_potion} table per potion effect, so this first runs BDS's own
 * documentation generator ({@link BdsDocGen}, {@code vanilladata_modules/mojang-potion-effects.json}) and installs the tables with
 * the add-on. No effect is listed by hand.
 */
public final class ScriptApiDump {
    private static final String EFFECTS_FILE = "vanilladata_modules/mojang-potion-effects.json";

    private ScriptApiDump() {
    }

    /**
     * Runs the add-on; its console goes to {@code log}.
     *
     * @return BDS's {@code docs} directory (still in the server), or null if the documentation generator failed
     */
    public static Path run(Path root, Path server, Path log, int timeout, int port) throws IOException, InterruptedException {
        Path docs = null;
        List<String> effects;
        try {
            docs = BdsDocGen.generate(server, log.resolveSibling("bds-docgen.log"), timeout, port);
            effects = potionEffects(docs);
        } catch (IOException | RuntimeException e) {
            // The dump still runs: without the loot tables the add-on reports an error per effect, and tipped arrows stay unnamed
            System.out.println("No potion effect list from BDS's documentation generator, so no tipped arrows are dumped: " + e.getMessage());
            effects = List.of();
        }
        new BdsRunner(root.resolve(Layout.DUMP_ADDON)).withPackFiles(lootTables(effects)).run(server, log, timeout, port, false);
        return docs != null && Files.isDirectory(docs) ? docs : null;
    }

    /**
     * Writes the dump of a console log as {@code data/bds/<build>/dump.json.gz}, and its variant names into
     * {@code data/model/variant_names.txt} for the bundled version it belongs to, if there is one (what {@code update} does as
     * part of a run).
     *
     * @param build the BDS build, e.g. {@code 1.26.63.1} (a preview prints another version than its download name)
     * @param dataVersion the name of the bundled version of the build, e.g. {@code 26.60}
     */
    public static Path write(Path root, Path log, String build, String dataVersion) throws IOException {
        String[] json = BdsDump.json(log);
        BdsDump dump = BdsDump.parse(json[1], build);
        Path file = root.resolve(Layout.BDS).resolve(build).resolve("dump.json.gz");
        Files.createDirectories(file.getParent());
        try (java.io.OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(BdsDump.canonicalJson(json[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path data = root.resolve("paletter/src/main/resources/paletter");
        if (Files.exists(data.resolve(dataVersion + ".nbt"))) {
            Path names = root.resolve(VariantNames.FILE);
            Map<String, List<VariantNames.Entry>> all = VariantNames.read(names);
            VariantNames.update(all, dataVersion, dump, BundledFiles.read(data.resolve(dataVersion + ".nbt")));
            Files.writeString(names, VariantNames.render(all));
        }
        return file;
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

    /** The potion effect identifiers in the documentation BDS generated. */
    static List<String> potionEffects(Path docs) throws IOException {
        List<String> effects = new ArrayList<>();
        String json = Files.readString(docs.resolve(EFFECTS_FILE));
        for (JsonElement item : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data_items")) {
            effects.add(item.getAsJsonObject().get("name").getAsString());
        }
        return effects;
    }
}
