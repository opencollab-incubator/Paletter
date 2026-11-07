package org.geysermc.paletter.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.block.BlockStates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of the Paletter BDS addon ({@code bds-addon/}) from a BDS console log.
 * <p>
 * Script API code cannot write files, so the addon prints its JSON in numbered chunks:
 * {@code PALETTER <index>/<total> <slice>}. Everything else in the log is ignored.
 * The JSON is {@code {format, version, item_tags: {tag: [id]}, block_defaults: {id: {state: value}}}}.
 */
record BdsDump(String version, Map<String, List<String>> itemTags, Map<String, NbtMap> blockDefaults) {
    private static final Pattern CHUNK = Pattern.compile("PALETTER (\\d+)/(\\d+) (.*)$");

    static BdsDump read(Path log) throws IOException {
        Map<Integer, String> chunks = new TreeMap<>();
        int total = -1;
        for (String line : Files.readAllLines(log)) {
            Matcher matcher = CHUNK.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int index = Integer.parseInt(matcher.group(1));
            int count = Integer.parseInt(matcher.group(2));
            if (index == 0) {
                // A new dump started (e.g. the server was restarted into the same log); keep only the last one
                chunks.clear();
            }
            total = count;
            chunks.put(index, matcher.group(3));
        }
        if (total < 0 || chunks.size() != total) {
            throw new IOException("Incomplete Paletter dump in " + log + ": got " + chunks.size() + " of " + total
                + " chunks. Did the addon finish? Check the log for script errors");
        }
        JsonObject root = JsonParser.parseString(String.join("", chunks.values())).getAsJsonObject();

        Map<String, List<String>> tags = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("item_tags").entrySet()) {
            List<String> items = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(item -> items.add(item.getAsString()));
            items.sort(String::compareTo);
            tags.put(entry.getKey(), items);
        }

        Map<String, NbtMap> defaults = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("block_defaults").entrySet()) {
            NbtMapBuilder states = NbtMap.builder();
            for (Map.Entry<String, JsonElement> state : entry.getValue().getAsJsonObject().entrySet()) {
                states.put(state.getKey(), toNbt(state.getValue().getAsJsonPrimitive()));
            }
            defaults.put(entry.getKey(), BlockStates.of(entry.getKey(), states.build()));
        }
        String version = root.has("version") ? root.get("version").getAsString() : "unknown";
        System.out.println("  BDS dump of " + version + ": " + tags.size() + " item tags, " + defaults.size() + " block defaults");
        return new BdsDump(version, tags, defaults);
    }

    /**
     * Script API state values are booleans, numbers and strings; in block state NBT they are bytes, ints and strings.
     */
    private static Object toNbt(JsonPrimitive value) {
        if (value.isBoolean()) {
            return (byte) (value.getAsBoolean() ? 1 : 0);
        }
        if (value.isNumber()) {
            return value.getAsInt();
        }
        return value.getAsString();
    }
}
