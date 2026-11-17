package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.rule.Leaf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The spec captures of BDS ({@code data/bds/<build>/spec}): the variants BDS was given, what it sent for each and the
 * content log lines it wrote about them, and which capture stands for each data version window.
 */
final class SpecCaptures {
    private SpecCaptures() {
    }

    /**
     * @param linked the other fields the variant sets because BDS ties them to the varied one (add-on generator links)
     */
    record Variant(String identifier, String kind, String file, String component, String role, String path, String label,
                   JsonElement value, JsonElement json, List<String> linked) {
        static Variant of(JsonObject o) {
            return new Variant(o.get("identifier").getAsString(), o.get("kind").getAsString(), o.get("file").getAsString(),
                o.get("component").getAsString(), o.get("role").getAsString(), o.get("path").getAsString(), o.get("label").getAsString(),
                o.has("value") ? o.get("value") : null, o.has("json") ? o.get("json") : null,
                o.has("linked") ? o.getAsJsonArray("linked").asList().stream().map(JsonElement::getAsString).toList() : List.of());
        }
    }

    /** A variant is accepted when BDS sent it and logged no error for it (items that fail to parse are still sent, without the component). */
    record Obs(Variant variant, Map<String, Leaf> wire, List<String> log) {
        boolean accepted() {
            return wire != null && log.stream().noneMatch(line -> line.startsWith("ERROR"));
        }
    }

    /**
     * @param definitions {@code <kind> <identifier>} -> what BDS sent for it, flattened: the item's {@code components}, the
     *                    block's property data as sent (components not lifted, {@code block_id} left out)
     */
    record Capture(String name, String bdsVersion, int protocol, List<Variant> variants, Map<String, Map<String, Leaf>> wire,
                   Map<String, List<String>> log, List<String> unattributed, Feedback feedback, Map<String, Map<String, Leaf>> definitions,
                   CustomBlockIds customBlockIds) {
    }

    /**
     * How BDS numbers custom blocks in a capture: {@code vanilla_block_data.block_id} counts up from {@code first}, and the
     * item of a custom block has the runtime id {@code itemIdPlusBlockId - block_id}.
     */
    record CustomBlockIds(int first, int itemIdPlusBlockId) {
        /**
         * @param ownItems the identifiers of the item variants: a block a link gave an item's identifier has no block item
         * @return the numbering, or null for a capture without custom blocks
         */
        static CustomBlockIds of(String capture, List<NbtMap> blocks, List<NbtMap> items, Set<String> ownItems) {
            Map<String, Integer> blockIds = new TreeMap<>();
            for (NbtMap block : blocks) {
                blockIds.put(block.getString("name"), block.getCompound("properties").getCompound("vanilla_block_data").getInt("block_id"));
            }
            if (blockIds.isEmpty()) {
                return null;
            }
            int first = blockIds.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
            if (!new TreeSet<>(blockIds.values()).equals(new TreeSet<>(Stream.iterate(first, i -> i + 1).limit(blockIds.size()).toList()))) {
                throw new IllegalStateException(capture + ": the custom block ids are not " + first + ".." + (first + blockIds.size() - 1));
            }
            Set<Integer> sums = new TreeSet<>();
            for (NbtMap item : items) {
                Integer blockId = ownItems.contains(item.getString("name")) ? null : blockIds.get(item.getString("name"));
                if (blockId != null) {
                    sums.add(((Number) item.get("id")).intValue() + blockId);
                }
            }
            if (sums.isEmpty()) {
                return null; // only blocks that items' links defined: nothing to number
            }
            if (sums.size() != 1) {
                throw new IllegalStateException(capture + ": block item id + block_id is not one constant but " + sums);
            }
            return new CustomBlockIds(first, sums.iterator().next());
        }
    }

    /**
     * The capture that defines a data version window, the other kept builds of the window with the same spec data
     * ({@code replaces}) and those with other data ({@code differs}, each a gap: it needs a data version of its own), both
     * oldest first.
     */
    record Window(Capture capture, List<String> replaces, List<String> differs) {
    }

    /**
     * The capture of each window (by {@code window}, the data protocol of a capture's protocol): the newest build that a
     * history check did not keep, which is the build the window's data version was imported from; the newest of all when
     * every one was. The others are compared with it ({@link SpecDiff#compare} and {@link SpecDiff#acceptance}): the same
     * data and it stands for them, other data and they are a gap, never the window's capture.
     *
     * @param directories the spec capture directory of each build
     * @param historyKept the builds whose captures a history check kept ({@code data/bds/builds.json})
     * @throws IllegalStateException when two captures of a window were made with different spec add-ons, so nothing
     *                               could be said about them: recapture
     */
    static Map<Integer, Window> windowCaptures(List<Capture> captures, java.util.function.IntUnaryOperator window,
                                               Map<String, Path> directories, Set<String> historyKept) throws IOException {
        Map<Integer, List<Capture>> byWindow = new TreeMap<>();
        for (Capture capture : captures) {
            byWindow.computeIfAbsent(window.applyAsInt(capture.protocol()), w -> new ArrayList<>()).add(capture);
        }
        Map<Integer, Window> windows = new TreeMap<>();
        List<String> unpaired = new ArrayList<>();
        for (Map.Entry<Integer, List<Capture>> entry : byWindow.entrySet()) {
            List<Capture> builds = new ArrayList<>(entry.getValue());
            builds.sort(java.util.Comparator.comparing(Capture::bdsVersion, org.geysermc.paletter.tools.Versions.ORDER));
            Capture chosen = builds.stream().filter(capture -> !historyKept.contains(capture.bdsVersion())).reduce((a, b) -> b)
                .orElse(builds.getLast());
            List<String> replaces = new ArrayList<>();
            List<String> differs = new ArrayList<>();
            for (Capture other : builds) {
                if (other == chosen) {
                    continue;
                }
                SpecDiff.Pairing pairing = SpecDiff.pairing(other, chosen);
                if (!pairing.same()) {
                    unpaired.add(other.bdsVersion() + " vs " + chosen.bdsVersion() + ": " + pairing.mismatch());
                    continue;
                }
                Path before = directories.get(other.bdsVersion());
                Path after = directories.get(chosen.bdsVersion());
                boolean same = SpecDiff.compare(before, after).isEmpty() && SpecDiff.acceptance(before, after).isEmpty();
                (same ? replaces : differs).add(other.bdsVersion());
            }
            windows.put(entry.getKey(), new Window(chosen, replaces, differs));
        }
        if (!unpaired.isEmpty()) {
            throw new IllegalStateException("Spec captures of one data version window cannot be compared: " + String.join("; ", unpaired));
        }
        return windows;
    }

    static Capture load(Path dir) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("capture.json"))).getAsJsonObject();
        List<Variant> variants = new ArrayList<>();
        JsonParser.parseString(Files.readString(dir.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()
            .forEach(e -> variants.add(Variant.of(e.getAsJsonObject())));
        Map<String, Map<String, Leaf>> wire = new LinkedHashMap<>();
        Map<String, Map<String, Leaf>> definitions = new LinkedHashMap<>();
        NbtMap items = Nbt.read(dir.resolve("custom_item_registry.nbt"));
        for (NbtMap item : items.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            NbtMap data = item.getCompound("data");
            if (item.getBoolean("componentBased") || !data.isEmpty()) {
                wire.put("item " + item.getString("name"), Wire.flatten(data.getCompound("components")));
                definitions.put("item " + item.getString("name"), wire.get("item " + item.getString("name")));
            }
        }
        NbtMap blocks = Nbt.read(dir.resolve("custom_block_properties.nbt"));
        for (NbtMap block : blocks.getList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            Map<String, Leaf> flat = Wire.flatten(block.getCompound("properties"));
            flat.remove("vanilla_block_data.block_id");
            definitions.put("block " + block.getString("name"), flat);
            // Block components sit under "components"; lifted to the top level like item components
            Map<String, Leaf> leaves = new TreeMap<>();
            flat.forEach((key, leaf) -> leaves.put(key.startsWith("components.") ? key.substring("components.".length()) : key, leaf));
            wire.put("block " + block.getString("name"), leaves);
        }
        Map<String, Map<String, Leaf>> byVariant = new LinkedHashMap<>();
        for (Variant v : variants) {
            Map<String, Leaf> leaves = wire.get(v.kind() + " " + v.identifier());
            if (leaves != null) {
                byVariant.put(v.identifier(), leaves);
            }
        }
        Map<String, List<String>> log = new TreeMap<>();
        List<String> unattributed = new ArrayList<>();
        Feedback feedback = new Feedback();
        Pattern blockFile = Pattern.compile("blocks/([a-z0-9_]+)\\.json");
        Pattern itemFile = Pattern.compile("items/([a-z0-9_]+)\\.json");
        Pattern itemName = Pattern.compile("'(paletter:[a-z0-9_]+)'");
        Pattern bare = Pattern.compile("(paletter:[a-z0-9_]+)");
        Map<String, Variant> byIdentifier = new LinkedHashMap<>();
        variants.forEach(v -> byIdentifier.put(v.identifier(), v));
        for (String line : Files.readAllLines(dir.resolve("content-log.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String id = null;
            Matcher m;
            if ((m = blockFile.matcher(line)).find() || (m = itemFile.matcher(line)).find()) {
                id = "paletter:" + m.group(1);
            } else if ((m = itemName.matcher(line)).find() || (m = bare.matcher(line)).find()) {
                id = m.group(1);
            }
            feedback.add(line.contains("[Item]") ? "item" : "block", line, id == null ? null : byIdentifier.get(id));
            if (id == null) {
                unattributed.add(line);
            } else {
                log.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
            }
        }
        Set<String> ownItems = new TreeSet<>();
        variants.stream().filter(v -> v.kind().equals("item")).forEach(v -> ownItems.add(v.identifier()));
        CustomBlockIds customBlockIds = CustomBlockIds.of(dir.toString(), blocks.getList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND),
            items.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND), ownItems);
        return new Capture(dir.getFileName().toString(), meta.get("bdsVersion").getAsString(), meta.get("protocol").getAsInt(),
            variants, byVariant, log, unattributed, feedback, definitions, customBlockIds);
    }

    static JsonElement jsonAt(JsonElement root, String path) {
        if (root == null || root.isJsonNull()) {
            return null;
        }
        JsonElement current = root;
        for (String token : AddonGenerator.tokens(path)) {
            if (current == null) {
                return null;
            }
            if (token.equals("[]")) {
                current = current.isJsonArray() && !current.getAsJsonArray().isEmpty() ? current.getAsJsonArray().get(0) : null;
            } else if (token.equals("{}")) {
                current = current.isJsonObject() && !current.getAsJsonObject().isEmpty()
                    ? current.getAsJsonObject().entrySet().iterator().next().getValue() : null;
            } else {
                current = current.isJsonObject() ? current.getAsJsonObject().get(token) : null;
            }
        }
        return current;
    }

    /** The part of a content log line after the file and JSON path, e.g. "the input is outside of the accepted range [1, 54]". */
    static String errorText(String line) {
        String text = line.replaceFirst("^(ERROR|WARN) ", "");
        int arrow = text.lastIndexOf("-> ");
        if (arrow >= 0) {
            text = text.substring(arrow + 3);
        }
        return text.replaceAll("paletter:[a-z0-9_]+", "<id>").replace("'", "").trim();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
