package org.geysermc.paletter.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.block.BlockStates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of the Paletter BDS addon ({@code bds-addon/}) from a BDS console log.
 * <p>
 * Script API code cannot write files, so the addon prints its JSON in numbered chunks:
 * {@code PALETTER <index>/<total> <slice>}. The BDS version is taken from the {@code Version: } line BDS prints at
 * startup. Everything else in the log is ignored. The JSON format is documented in {@code bds-addon/scripts/main.js};
 * format 1 dumps (item tags and block defaults only) are still read.
 *
 * @param version the BDS version, e.g. {@code 1.26.63.1}, or {@code unknown}
 * @param itemTags tag to sorted item identifiers
 * @param blockDefaults block identifier to its default state as {@code {name, states}}
 * @param blockStates block identifier to state name to the values valid on that block, as NBT values
 * @param blockTags tag to sorted block identifiers
 * @param blockItems block identifier to the identifier of its item
 * @param items item identifier to what the Script API knows about it
 * @param errors problems the addon reported
 */
record BdsDump(
    String version,
    Map<String, List<String>> itemTags,
    Map<String, NbtMap> blockDefaults,
    Map<String, Map<String, List<Object>>> blockStates,
    Map<String, List<String>> blockTags,
    Map<String, String> blockItems,
    Map<String, Item> items,
    List<String> errors
) {
    private static final Pattern CHUNK = Pattern.compile("PALETTER (\\d+)/(\\d+) (.*)$");
    private static final Pattern VERSION = Pattern.compile("INFO\\] Version: (\\S+)");

    /**
     * @param maxStackSize the maximum stack size
     * @param localizationKey the translation key of the item name
     * @param components identifiers of the Script API item components it has
     * @param maxDurability the maximum durability, or 0 if it has none
     */
    record Item(int maxStackSize, String localizationKey, List<String> components, int maxDurability) {
    }

    static BdsDump read(Path log) throws IOException {
        Map<Integer, String> chunks = new TreeMap<>();
        int total = -1;
        String version = "unknown";
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            Matcher versionMatcher = VERSION.matcher(line);
            if (versionMatcher.find()) {
                version = versionMatcher.group(1);
                continue;
            }
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

        Map<String, NbtMap> defaults = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("block_defaults").entrySet()) {
            NbtMapBuilder states = NbtMap.builder();
            for (Map.Entry<String, JsonElement> state : entry.getValue().getAsJsonObject().entrySet()) {
                states.put(state.getKey(), toNbt(state.getValue().getAsJsonPrimitive()));
            }
            defaults.put(entry.getKey(), BlockStates.of(entry.getKey(), states.build()));
        }

        Map<String, Map<String, List<Object>>> blockStates = new TreeMap<>();
        for (Map.Entry<String, JsonElement> block : object(root, "block_states").entrySet()) {
            Map<String, List<Object>> states = new TreeMap<>();
            for (Map.Entry<String, JsonElement> state : block.getValue().getAsJsonObject().entrySet()) {
                List<Object> values = new ArrayList<>();
                state.getValue().getAsJsonArray().forEach(value -> values.add(toNbt(value.getAsJsonPrimitive())));
                states.put(state.getKey(), values);
            }
            blockStates.put(block.getKey(), states);
        }

        Map<String, String> blockItems = new TreeMap<>();
        object(root, "block_items").entrySet().forEach(entry -> blockItems.put(entry.getKey(), entry.getValue().getAsString()));

        Map<String, Item> items = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : object(root, "items").entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            List<String> components = new ArrayList<>();
            if (item.has("components")) {
                item.getAsJsonArray("components").forEach(component -> components.add(component.getAsString()));
            }
            items.put(entry.getKey(), new Item(item.get("max_stack").getAsInt(),
                item.has("localization_key") ? item.get("localization_key").getAsString() : null,
                List.copyOf(components),
                item.has("max_durability") ? item.get("max_durability").getAsInt() : 0));
        }

        List<String> errors = new ArrayList<>();
        if (root.has("errors")) {
            root.getAsJsonArray("errors").forEach(error -> errors.add(error.getAsString()));
        }

        Map<String, List<String>> itemTags = tagMap(root.getAsJsonObject("item_tags"));
        Map<String, List<String>> blockTags = tagMap(object(root, "block_tags"));
        System.out.println("  BDS dump of " + version + ": " + itemTags.size() + " item tags, " + items.size() + " items, "
            + defaults.size() + " block defaults, " + blockTags.size() + " block tags, " + errors.size() + " addon errors");
        return new BdsDump(version, itemTags, defaults, blockStates, blockTags, blockItems, items, errors);
    }

    /**
     * The Script API also reports legacy alias states that are not part of the network block state (e.g.
     * {@code wood_type} on {@code minecraft:oak_planks}, {@code direction} next to {@code minecraft:cardinal_direction}
     * on doors). Projects the defaults onto the states the block palette has and keeps only those that are palette
     * states; the others are returned in {@code rejected}.
     *
     * @param palette the block palette of the version
     * @param rejected receives a message per default that does not project onto a palette state
     * @return block identifier to default state, for blocks of the palette only
     */
    Map<String, NbtMap> projectDefaults(List<NbtMap> palette, List<String> rejected) {
        Map<String, Set<String>> keys = new HashMap<>();
        Set<NbtMap> states = new HashSet<>(palette);
        for (NbtMap state : palette) {
            keys.computeIfAbsent(BlockStates.name(state), n -> new HashSet<>()).addAll(BlockStates.states(state).keySet());
        }
        Map<String, NbtMap> projected = new TreeMap<>();
        blockDefaults.forEach((name, state) -> {
            Set<String> paletteKeys = keys.get(name);
            if (paletteKeys == null) {
                return;
            }
            NbtMapBuilder builder = NbtMap.builder();
            BlockStates.states(state).forEach((key, value) -> {
                if (paletteKeys.contains(key)) {
                    builder.put(key, value);
                }
            });
            NbtMap candidate = BlockStates.of(name, builder.build());
            if (states.contains(candidate)) {
                projected.put(name, candidate);
            } else {
                rejected.add(name + ": default " + BlockStates.states(candidate) + " is not a palette state");
            }
        });
        return projected;
    }

    private static JsonObject object(JsonObject root, String name) {
        return root.has(name) ? root.getAsJsonObject(name) : new JsonObject();
    }

    private static Map<String, List<String>> tagMap(JsonObject json) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        new TreeMap<>(json.asMap()).forEach((tag, value) -> {
            List<String> identifiers = new ArrayList<>();
            value.getAsJsonArray().forEach(identifier -> identifiers.add(identifier.getAsString()));
            identifiers.sort(String::compareTo);
            tags.put(tag, identifiers);
        });
        return tags;
    }

    /**
     * Script API state values are booleans, numbers and strings; in block state NBT they are bytes, ints and strings.
     */
    static Object toNbt(JsonPrimitive value) {
        if (value.isBoolean()) {
            return (byte) (value.getAsBoolean() ? 1 : 0);
        }
        if (value.isNumber()) {
            return value.getAsInt();
        }
        return value.getAsString();
    }
}
