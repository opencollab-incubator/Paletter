package org.geysermc.paletter.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockStates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assertions about how BDS numbers blocks, checked against every run (first confirmed by hand on BDS 1.26.63.1):
 * <ul>
 *     <li><b>sequential palette order</b>: with {@code block-network-ids-are-hashes=false} a block's runtime id is its
 *     index in the palette sorted by the FNV-1 64 hash of the block name ({@link BlockPalette#nameHash}, unsigned;
 *     stable, so the states of a block keep their order). Checked by capturing the same world once hashed and once
 *     sequential: every creative stack's state (resolved through its hash) must sit at the sequential id BDS sent;</li>
 *     <li><b>custom block ids</b>: {@code vanilla_block_data.block_id} of custom blocks is 10000, 10001, ... in
 *     <i>descending</i> identifier order (BDS uses descending <i>file name</i> order; the capture add-on names each file
 *     after its block, capture/CREATIVE-ORDER.md), and a custom block's item has runtime id {@code 255 - block_id}.</li>
 * </ul>
 */
final class OrderingCheck {
    final List<String> failures = new ArrayList<>();
    final List<String> passes = new ArrayList<>();
    final List<String> withinBlockOrder = new ArrayList<>();

    /**
     * @param hashed a capture directory of a run with hashed ids
     * @param sequential a capture of the same world with sequential ids
     */
    void sequential(String label, Path hashed, Path sequential) throws IOException {
        List<NbtMap> palette = new ArrayList<>(readGzip(hashed.resolve("block_palette.nbt")).getList("blocks", NbtType.COMPOUND));
        List<NbtMap> sorted = new ArrayList<>(palette);
        sorted.sort(Comparator.comparing((NbtMap state) -> BlockPalette.nameHash(BlockStates.name(state)), Long::compareUnsigned));
        Map<NbtMap, Integer> index = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            index.put(BlockStates.normalize(sorted.get(i)), i);
        }
        JsonArray a = Importer.readJson(hashed.resolve("creative_items.json")).getAsJsonObject().getAsJsonArray("items");
        JsonArray b = Importer.readJson(sequential.resolve("creative_items.json")).getAsJsonObject().getAsJsonArray("items");
        if (a.size() != b.size()) {
            failures.add(label + ": the hashed and sequential runs sent different creative inventories (" + a.size() + " vs " + b.size() + " stacks)");
            return;
        }
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        int unresolved = 0;
        for (int i = 0; i < a.size(); i++) {
            JsonObject x = a.get(i).getAsJsonObject();
            JsonObject y = b.get(i).getAsJsonObject();
            if (!x.get("id").getAsString().equals(y.get("id").getAsString())) {
                failures.add(label + ": creative stack #" + i + " is " + x.get("id") + " hashed but " + y.get("id") + " sequential");
                return;
            }
            if (!y.has("block_runtime_id")) {
                continue;
            }
            if (!x.has("block_state_b64")) {
                unresolved++;
                continue;
            }
            NbtMap state = BlockStates.normalize(readLittleEndian(x.get("block_state_b64").getAsString()));
            long actual = Long.parseLong(y.get("block_runtime_id").getAsString());
            Integer expected = index.get(state);
            checked++;
            if (expected == null || expected != actual) {
                wrong.add(BlockStates.name(state) + " " + DataDiff.compact(BlockStates.states(state)) + ": BDS " + actual + ", expected " + expected
                    + (actual < sorted.size() ? " (BDS id is " + BlockStates.name(sorted.get((int) actual)) + ")" : ""));
            }
        }
        // A wrong id that still names the same block only means the state order within that block is off (generated
        // blocks); the name hash sort itself holds
        List<String> withinBlock = wrong.stream().filter(line -> line.contains("(BDS id is " + line.substring(0, line.indexOf(' ')) + ")")).toList();
        if (!withinBlock.isEmpty() && withinBlock.size() == wrong.size()) {
            passes.add(label + ": " + checked + " sequential creative block ids follow the FNV-1 64 name sort of " + sorted.size()
                + " states; " + wrong.size() + " point at another state of the same block (state order within a generated block): "
                + String.join("; ", wrong.subList(0, Math.min(5, wrong.size()))));
            withinBlockOrder.addAll(wrong);
            return;
        }
        if (checked == 0) {
            failures.add(label + ": no creative block ids to check");
        } else if (wrong.isEmpty()) {
            passes.add(label + ": " + checked + " sequential creative block ids match the FNV-1 64 name sort of " + sorted.size() + " states"
                + (unresolved > 0 ? " (" + unresolved + " not checked: unknown hash)" : ""));
        } else {
            failures.add(label + ": " + wrong.size() + " of " + checked + " sequential block ids do not follow the FNV-1 64 name sort, e.g. "
                + String.join("; ", wrong.subList(0, Math.min(5, wrong.size()))));
        }
    }

    /** The vanilla palette as bundled must already be in sequential (name hash) order. */
    void bundledOrder(String label, List<NbtMap> states) {
        for (int i = 1; i < states.size(); i++) {
            long previous = BlockPalette.nameHash(BlockStates.name(states.get(i - 1)));
            long current = BlockPalette.nameHash(BlockStates.name(states.get(i)));
            if (Long.compareUnsigned(previous, current) > 0) {
                failures.add(label + ": palette not in FNV-1 64 name order at #" + i + " (" + BlockStates.name(states.get(i - 1))
                    + " before " + BlockStates.name(states.get(i)) + ")");
                return;
            }
        }
        passes.add(label + ": " + states.size() + " states in FNV-1 64 name order");
    }

    /** Custom block ids and block item ids of a capture with custom blocks. */
    void customBlockIds(String label, Path capture) throws IOException {
        List<String[]> custom = new ArrayList<>();
        for (NbtMap block : readGzip(capture.resolve("raw_start_game_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            String name = block.getString("name");
            if (!name.startsWith("minecraft:")) {
                NbtMap data = block.getCompound("properties").getCompound("vanilla_block_data");
                custom.add(new String[]{name, String.valueOf(data.getInt("block_id", -1))});
            }
        }
        if (custom.isEmpty()) {
            failures.add(label + ": no custom blocks in the capture");
            return;
        }
        custom.sort(Comparator.comparingInt(entry -> Integer.parseInt(entry[1])));
        List<String> names = custom.stream().map(entry -> entry[0]).toList();
        List<String> descending = new ArrayList<>(names);
        descending.sort(Comparator.reverseOrder());
        boolean contiguous = true;
        for (int i = 0; i < custom.size(); i++) {
            contiguous &= Integer.parseInt(custom.get(i)[1]) == BlockPalette.CUSTOM_BLOCK_ID_START + i;
        }
        if (names.equals(descending) && contiguous) {
            passes.add(label + ": " + custom.size() + " custom block ids are " + BlockPalette.CUSTOM_BLOCK_ID_START
                + "+ in descending identifier order");
        } else {
            int first = 0;
            while (first < names.size() && names.get(first).equals(descending.get(first))) {
                first++;
            }
            failures.add(label + ": custom block ids are not " + BlockPalette.CUSTOM_BLOCK_ID_START + "+ in descending identifier order"
                + (contiguous ? "" : " (not contiguous)") + (first < names.size()
                ? "; first difference at #" + first + ": " + names.get(first) + " has the id of " + descending.get(first) : ""));
        }

        // Block items are the plain (not component based) items; a custom item with the block's identifier renames the
        // block item to <namespace>:item.<name>, or replaces it (block_placer.replace_block_item)
        Map<String, Integer> blockItemIds = new HashMap<>();
        for (JsonElement element : Importer.readJson(capture.resolve("runtime_item_states.json")).getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            if (!item.get("componentBased").getAsBoolean()) {
                blockItemIds.put(item.get("name").getAsString(), item.get("id").getAsInt());
            }
        }
        List<String> wrongItems = new ArrayList<>();
        int withItem = 0;
        for (String[] entry : custom) {
            int colon = entry[0].indexOf(':');
            Integer id = blockItemIds.getOrDefault(entry[0],
                blockItemIds.get(entry[0].substring(0, colon) + ":item." + entry[0].substring(colon + 1)));
            if (id == null) {
                continue;
            }
            withItem++;
            if (id != 255 - Integer.parseInt(entry[1])) {
                wrongItems.add(entry[0] + ": item id " + id + ", block_id " + entry[1]);
            }
        }
        if (wrongItems.isEmpty()) {
            passes.add(label + ": " + withItem + " custom block items have runtime id 255 - block_id");
        } else {
            failures.add(label + ": custom block item ids are not 255 - block_id: " + String.join("; ", wrongItems.subList(0, Math.min(5, wrongItems.size()))));
        }
    }

    static NbtMap readGzip(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            return (NbtMap) input.readTag();
        }
    }

    private static NbtMap readLittleEndian(String base64) throws IOException {
        try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return (NbtMap) input.readTag();
        }
    }
}
