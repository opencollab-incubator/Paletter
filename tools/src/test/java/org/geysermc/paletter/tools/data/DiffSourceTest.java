package org.geysermc.paletter.tools.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.data.VersionFallbacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffSourceTest {
    private static final String NAME = "26.60";

    @TempDir
    Path directory;

    @Test
    void identical() throws Exception {
        VanillaData data = fixture();
        write(data, directory, true);
        Diff verify = Diff.source(directory, NAME, data, "fixture");
        assertTrue(verify.identical(), verify.markdown(50));
        assertTrue(verify.markdown(50).contains("IDENTICAL"));
    }

    @Test
    void geyserFileNames() throws Exception {
        VanillaData data = fixture();
        write(data, directory, false);
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                Files.move(file, directory.resolve(name.substring(0, dot) + ".26_60" + name.substring(dot)));
            }
        }
        assertTrue(Diff.source(directory, NAME, data, "fixture").identical());
    }

    @Test
    void reorderedStateWithinABlock() throws Exception {
        VanillaData data = fixture();
        List<NbtMap> states = new ArrayList<>(data.blockStates());
        int first = states.indexOf(BlockStates.of("test:lamp", Map.of("lit", (byte) 0, "power", 0)));
        NbtMap swapped = states.get(first);
        states.set(first, states.get(first + 1));
        states.set(first + 1, swapped);
        write(with(data, states, data.items(), data.creative()), directory, true);

        Diff verify = Diff.source(directory, NAME, data, "fixture");
        assertOnly(verify, "State order within blocks");
        String line = verify.section("State order within blocks").differences.get(0);
        assertTrue(line.startsWith("test:lamp: state #0"), line);
    }

    @Test
    void reorderedBlocks() throws Exception {
        VanillaData data = fixture();
        List<NbtMap> states = new ArrayList<>(data.blockStates());
        NbtMap firstState = states.remove(0);
        states.add(firstState);
        write(with(data, states, data.items(), data.creative()), directory, true);

        Diff verify = Diff.source(directory, NAME, data, "fixture");
        assertOnly(verify, "Block order (FNV-1 64 name sort)");
        assertTrue(verify.section("Block order (FNV-1 64 name sort)").info.get(0).contains("not sorted"));
    }

    @Test
    void changedComponentField() throws Exception {
        VanillaData data = fixture();
        List<VanillaItem> items = new ArrayList<>(data.items());
        VanillaItem sword = items.get(2);
        NbtMap components = sword.componentData().toBuilder()
            .putCompound("item_properties", sword.componentData().getCompound("item_properties").toBuilder().putInt("max_stack_size", 64).build())
            .build();
        items.set(2, new VanillaItem(sword.identifier(), sword.runtimeId(), sword.version(), sword.componentBased(), components));
        write(with(data, data.blockStates(), items, data.creative()), directory, true);

        Diff verify = Diff.source(directory, NAME, data, "fixture");
        assertOnly(verify, "Item components");
        List<String> differences = verify.section("Item components").differences;
        assertEquals(1, differences.size(), differences.toString());
        assertTrue(differences.get(0).contains("test:sword: item_properties.max_stack_size: 1 -> 64"), differences.get(0));
    }

    @Test
    void componentTypeChange() throws Exception {
        VanillaData data = fixture();
        List<VanillaItem> items = new ArrayList<>(data.items());
        VanillaItem sword = items.get(2);
        NbtMap components = sword.componentData().toBuilder()
            .putCompound("item_properties", sword.componentData().getCompound("item_properties").toBuilder().putByte("max_stack_size", (byte) 1).build())
            .build();
        items.set(2, new VanillaItem(sword.identifier(), sword.runtimeId(), sword.version(), sword.componentBased(), components));
        write(with(data, data.blockStates(), items, data.creative()), directory, true);
        assertOnly(Diff.source(directory, NAME, data, "fixture"), "Item components");
    }

    @Test
    void creativeMove() throws Exception {
        VanillaData data = fixture();
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            categories.put(category, new ArrayList<>(data.creative().nodes(category)));
        }
        List<CreativeNode> items = categories.get(CreativeCategory.ITEMS);
        items.add(items.remove(0)); // the loose apple goes last
        write(with(data, data.blockStates(), data.items(), CreativeLayout.of(categories)), directory, true);

        Diff verify = Diff.source(directory, NAME, data, "fixture");
        assertOnly(verify, "Creative layout");
        List<String> differences = verify.section("Creative layout").differences;
        assertTrue(differences.stream().anyMatch(line -> line.startsWith("ITEMS moved: test:apple from #0")), differences.toString());
        assertEquals(2, differences.size(), differences.toString());
    }

    /** The key order of a creative stack's block state carries no meaning (the update pipeline writes its own). */
    @Test
    void creativeStateKeyOrderIsIgnored() throws Exception {
        VanillaData data = fixture();
        NbtMap powerFirst = BlockStates.of("test:lamp", NbtMap.builder().putInt("power", 0).putByte("lit", (byte) 0).build());
        NbtMap litFirst = BlockStates.of("test:lamp", NbtMap.builder().putByte("lit", (byte) 0).putInt("power", 0).build());
        VanillaData a = with(data, data.blockStates(), data.items(), lampLayout(data, powerFirst));
        VanillaData b = with(data, data.blockStates(), data.items(), lampLayout(data, litFirst));
        write(b, directory, true);
        assertTrue(Diff.source(directory, NAME, a, "fixture").identical());
    }

    private static CreativeLayout lampLayout(VanillaData data, NbtMap state) {
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            categories.put(category, new ArrayList<>(data.creative().nodes(category)));
        }
        categories.get(CreativeCategory.CONSTRUCTION).add(stack("test:lamp", 0, state, null));
        return CreativeLayout.of(categories);
    }

    /** A capture leaves empty item components out, CloudburstMC/Data keeps them: both import as empty. */
    @Test
    void missingComponentsImportAsEmpty() throws Exception {
        VanillaData data = fixture();
        write(data, directory, true);
        // Like the capture writer: only the non empty components
        NbtMapBuilder nonEmpty = NbtMap.builder();
        data.items().stream().filter(item -> !item.componentData().isEmpty())
            .forEach(item -> nonEmpty.putCompound(item.identifier(), item.componentData()));
        writeGzip(directory.resolve("item_components.nbt"), nonEmpty.build());
        Importer.Source source = Importer.readSource(directory, NAME);
        assertFalse(source.components().containsKey("test:apple"));
        assertTrue(Diff.source(directory, NAME, data, "fixture").identical());
        source.items().stream().filter(item -> !item.componentBased())
            .forEach(item -> assertEquals(NbtMap.EMPTY, item.componentData(), item.identifier()));
    }

    /** runtime_item_states order carries no meaning: a capture lists items in network order. */
    @Test
    void itemListOrderIsIgnored() throws Exception {
        VanillaData data = fixture();
        List<VanillaItem> reversed = new ArrayList<>(data.items());
        java.util.Collections.reverse(reversed);
        write(with(data, data.blockStates(), reversed, data.creative()), directory, true);
        assertTrue(Diff.source(directory, NAME, data, "fixture").identical());
        assertEquals(data.items(), Importer.readSource(directory, NAME).items());
    }

    @Test
    void networkIdMismatch() throws Exception {
        VanillaData data = fixture();
        write(data, directory, true);
        // Corrupt one network_id in the palette file
        List<NbtMap> blocks = new ArrayList<>();
        for (NbtMap state : data.blockStates()) {
            blocks.add(state.toBuilder().putInt("network_id", BlockNetworkIds.hash(state)).putInt("version", 1).build());
        }
        blocks.set(0, blocks.get(0).toBuilder().putInt("network_id", 42).build());
        writeGzip(directory.resolve("block_palette.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, blocks).build());
        assertOnly(Diff.source(directory, NAME, data, "fixture"), "Hashed block network ids");
    }

    /** The bundled data exported into the layout must verify as identical: the importer and verify agree. */
    @Test
    void bundledRoundTrip() throws Exception {
        VanillaData data;
        try (InputStream stream = DiffSourceTest.class.getResourceAsStream("/paletter/" + NAME + ".nbt")) {
            assertNotNull(stream);
            data = PaletteDataCodec.read(stream);
        }
        write(data, directory, true);
        Diff verify = Diff.source(directory, NAME, data, "bundled");
        assertTrue(verify.identical(), verify.markdown(20));
        assertTrue(verify.section("Hashed block network ids").source.contains(data.blockStates().size() + " states carry `network_id`"));
    }

    private static void assertOnly(Diff verify, String section) {
        verify.sections.values().forEach(s -> {
            if (s.title.equals(section)) {
                assertFalse(s.differences.isEmpty(), "expected differences in " + section + "\n" + verify.markdown(50));
            } else {
                assertTrue(s.differences.isEmpty(), "unexpected differences in " + s.title + "\n" + verify.markdown(50));
            }
        });
        assertFalse(verify.identical());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixture

    private static VanillaData fixture() {
        List<NbtMap> states = new ArrayList<>();
        states.add(BlockStates.of("minecraft:air"));
        states.add(BlockStates.of("minecraft:stone"));
        for (int power = 0; power < 3; power++) {
            for (byte lit = 0; lit < 2; lit++) {
                states.add(BlockStates.of("test:lamp", Map.of("lit", lit, "power", power)));
            }
        }
        for (String facing : List.of("north", "south")) {
            states.add(BlockStates.of("test:door", Map.of("facing", facing)));
        }
        states.sort(Comparator.comparing((NbtMap state) -> BlockPalette.nameHash(BlockStates.name(state)), Long::compareUnsigned));

        NbtMap swordComponents = NbtMap.builder()
            .putCompound("components", NbtMap.builder().putString("minecraft:icon", "sword").build())
            .putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", 1).putFloat("speed", 1.5f).build())
            .putList("tags", NbtType.STRING, "minecraft:is_sword")
            .build();
        List<VanillaItem> items = List.of(
            new VanillaItem("test:apple", 1, 0, false, NbtMap.EMPTY),
            new VanillaItem("test:lamp", -3, 0, false, NbtMap.EMPTY),
            new VanillaItem("test:sword", 2, 2, true, swordComponents));

        NbtMap lampTag = NbtMap.builder().putString("Name", "Lamp").putByte("custom", (byte) 1).build();
        Map<CreativeCategory, List<CreativeNode>> creative = new EnumMap<>(CreativeCategory.class);
        creative.put(CreativeCategory.CONSTRUCTION, List.of(
            new CreativeGroup("itemGroup.name.lamps", stack("test:lamp", 0, null, null), List.of(
                stack("test:lamp", 0, BlockStates.of("test:lamp", Map.of("lit", (byte) 0, "power", 0)), null),
                stack("test:lamp", 0, BlockStates.of("test:lamp", Map.of("lit", (byte) 1, "power", 2)), lampTag)))));
        creative.put(CreativeCategory.ITEMS, List.of(
            stack("test:apple", 0, null, null),
            stack("test:apple", 3, null, null),
            new CreativeGroup("itemGroup.name.sword", null, List.of(stack("test:sword", 0, null, null)))));
        List<BlockPropertyData> dataDriven = List.of(new BlockPropertyData("test:lamp", NbtMap.builder()
            .putList("properties", NbtType.COMPOUND, NbtMap.builder().putString("name", "power").putList("enum", NbtType.INT, 0, 1, 2).build())
            .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 9000).build())
            .build()));
        return new VanillaData(new DataVersion(NAME, 2225), states, items, dataDriven, CreativeLayout.of(creative), Map.of(),
            VersionFallbacks.NONE, Map.of());
    }

    private static CreativeStack stack(String id, int damage, NbtMap block, NbtMap tag) {
        return new CreativeStack(id, damage, 1, block, tag);
    }

    private static VanillaData with(VanillaData data, List<NbtMap> states, List<VanillaItem> items, CreativeLayout creative) {
        return new VanillaData(data.version(), states, items, data.dataDrivenBlocks(), creative, data.itemTags(), data.fallbacks(),
            data.blockDefaults());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Export into the CloudburstMC/Data layout (what ProxyPass and the capture client write)

    static void write(VanillaData data, Path directory, boolean networkIds) throws IOException {
        List<NbtMap> blocks = new ArrayList<>();
        for (NbtMap state : data.blockStates()) {
            blocks.add(networkIds ? state.toBuilder().putInt("network_id", BlockNetworkIds.hash(state)).putInt("version", 18168865).build() : state);
        }
        writeGzip(directory.resolve("block_palette.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, blocks).build());

        JsonArray states = new JsonArray();
        NbtMapBuilder components = NbtMap.builder();
        for (VanillaItem item : data.items()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", item.identifier());
            json.addProperty("id", item.runtimeId());
            json.addProperty("version", item.version());
            json.addProperty("componentBased", item.componentBased());
            states.add(json);
            if (item.componentData() != null) {
                components.putCompound(item.identifier(), item.componentData());
            }
        }
        Files.writeString(directory.resolve("runtime_item_states.json"), new Gson().toJson(states));
        writeGzip(directory.resolve("item_components.nbt"), components.build());

        NbtMapBuilder dataDriven = NbtMap.builder();
        data.dataDrivenBlocks().forEach(block -> dataDriven.putCompound(block.getName(), block.getProperties()));
        writeGzip(directory.resolve("data_driven_blocks.nbt"), dataDriven.build());

        JsonArray groups = new JsonArray();
        JsonArray items = new JsonArray();
        for (CreativeCategory category : CreativeCategory.values()) {
            boolean looseGroupOpen = false;
            for (CreativeNode node : data.creative().nodes(category)) {
                if (node instanceof CreativeGroup group) {
                    groups.add(group(group.name(), category, group.icon()));
                    for (CreativeStack stack : group.stacks()) {
                        items.add(stack(stack, groups.size() - 1));
                    }
                    looseGroupOpen = false;
                } else {
                    if (!looseGroupOpen) {
                        groups.add(group("", category, null));
                        looseGroupOpen = true;
                    }
                    items.add(stack((CreativeStack) node, groups.size() - 1));
                }
            }
        }
        JsonObject creative = new JsonObject();
        creative.add("groups", groups);
        creative.add("items", items);
        Files.writeString(directory.resolve("creative_items.json"), new Gson().toJson(creative));
    }

    private static JsonObject group(String name, CreativeCategory category, CreativeStack icon) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("category", category.bedrockName());
        json.add("icon", icon == null ? air() : stack(icon, -1));
        return json;
    }

    private static JsonObject air() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "minecraft:air");
        return json;
    }

    private static JsonObject stack(CreativeStack stack, int groupId) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("id", stack.identifier());
        if (stack.damage() != 0) {
            json.addProperty("damage", stack.damage());
        }
        if (stack.count() != 1) {
            json.addProperty("count", stack.count());
        }
        if (stack.blockState() != null) {
            json.addProperty("block_state_b64", base64(stack.blockState()));
        }
        if (stack.tag() != null) {
            json.addProperty("nbt_b64", base64(stack.tag()));
        }
        if (groupId >= 0) {
            json.addProperty("groupId", groupId);
        }
        return json;
    }

    private static String base64(NbtMap tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(bytes)) {
            output.writeTag(tag);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void writeGzip(Path file, NbtMap tag) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file); NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(tag);
        }
    }
}
