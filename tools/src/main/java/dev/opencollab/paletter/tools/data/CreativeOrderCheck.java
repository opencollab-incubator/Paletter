package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.Json;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.BlockPlacer;
import dev.opencollab.paletter.block.component.BlockMenuCategory;
import dev.opencollab.paletter.internal.access.Access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks {@link CreativeOrdering#BDS} against a capture: the custom items and blocks BDS sent (with their menu
 * categories, item runtime ids and block ids, i.e. BDS's registration order) are compiled through Paletter as one
 * layer, and the resulting creative inventory must show exactly what BDS sent. See docs/wire-notes.md.
 * <p>
 * Compared is what the client shows: per category, the groups and loose runs in order with their stacks (identifier
 * and damage). Two packet details are not compared, since they do not change what the client shows: BDS appends the
 * groups it creates for add-on content to the end of the group list while Paletter keeps groups in category order, and
 * BDS sends air as the icon of such groups while Paletter sends their first stack. Stacks BDS sent that are neither
 * custom nor in Paletter's vanilla layout (content of experiments, which the bundled data does not have) are ignored.
 */
public final class CreativeOrderCheck {

    /** A group or a run of loose stacks as the client shows it. */
    record Node(String category, String group, String icon, List<String> stacks) {
        @Override
        public String toString() {
            return category + " " + (group.isEmpty() ? "(loose)" : group) + (icon == null ? "" : " icon " + icon) + " " + stacks;
        }
    }

    /** @param skipped why nothing was compared, or null */
    public record Result(int customStacks, List<String> differences, String skipped) {
        public boolean matches() {
            return skipped == null && differences.isEmpty();
        }
    }

    private CreativeOrderCheck() {
    }

    /**
     * @param data the bundled data directory; its version with exactly this protocol is compared, previews included
     * @param creativeItems the full {@code creative_items.json} of the capture (gzipped or not)
     * @param customItems {@code custom_item_registry.nbt} of the capture
     * @param customBlocks {@code custom_block_properties.nbt} of the capture
     */
    public static Result check(int protocol, Path data, Path creativeItems, Path customItems, Path customBlocks) throws IOException {
        return check(protocol, data, creativeItems, customItems, customBlocks, CreativeOrdering.BDS);
    }

    public static Result check(int protocol, Path data, Path creativeItems, Path customItems, Path customBlocks,
                        CreativeOrdering ordering) throws IOException {
        PaletteLayer layer = layer(readList(customItems, "items"), readList(customBlocks, "blocks"));
        // The ordering is global policy: compile with a Paletter of its own (on the bundled data, like every caller)
        Paletter capture = withPreviews(data, ordering, layer);
        DataVersion version = capture.versions().stream().filter(v -> v.protocolVersion() == protocol).findFirst().orElse(null);
        if (version == null) {
            return new Result(0, List.of(), "no bundled data version has protocol " + protocol);
        }
        Palette palette = capture.palette(protocol);
        Palette vanilla = capture.vanilla(version);

        Set<String> vanillaStacks = new HashSet<>();
        vanilla.creative().items().forEach(entry -> vanillaStacks.add(stack(entry.getItem())));
        List<Node> expected = bds(Json.object(creativeItems), vanillaStacks);
        List<Node> actual = paletter(palette);
        int custom = (int) expected.stream().flatMap(node -> node.stacks().stream()).filter(id -> !id.startsWith("minecraft:")).count();
        return new Result(custom, differences(expected, actual), null);
    }

    /**
     * The block pass without a creative packet: BDS iterates its map of data driven blocks for the start game block
     * properties as for the block items it adds to the creative inventory, so the custom blocks of a capture are on the
     * wire in block pass order. Paletter, given the capture's custom content, must number the blocks as BDS did and
     * order them as the wire does ({@link CreativeOrdering#blockOrder}). Checked on the spec captures, which hold no
     * creative inventory.
     * <p>
     * BDS numbers the blocks of one behavior pack after those of the packs before it in the world's pack list, each pack's
     * in {@link dev.opencollab.paletter.internal.BlockIdOrder} (spec capture of BDS 1.26.36.1: the main pack's 1338 blocks got
     * 10000 to 11337, the engine packs' {@code paletter:engine_0} and {@code paletter:engine_1}, listed after it, 11338 and 11339,
     * although by name they come first). Paletter, which has no packs, numbers the main pack; the blocks of the packs after it
     * are numbered after it, in the order given.
     *
     * @param data the bundled data directory, as for {@link #check}
     * @param customItems {@code custom_item_registry.nbt} of the capture
     * @param customBlocks {@code custom_block_properties.nbt} of the capture
     * @param laterPacks the blocks of the behavior packs after the main pack, in block id order (pack by pack, in the
     *                   world's pack order)
     * @return the custom blocks compared and the first differences
     */
    public static Result blockOrder(int protocol, Path data, Path customItems, Path customBlocks, List<String> laterPacks) throws IOException {
        List<NbtMap> blocks = readList(customBlocks, "blocks");
        Set<String> later = Set.copyOf(laterPacks);
        Paletter capture = withPreviews(data, CreativeOrdering.BDS, layer(
            readList(customItems, "items").stream().filter(item -> !later.contains(item.getString("name"))).toList(),
            blocks.stream().filter(block -> !later.contains(block.getString("name"))).toList()));
        if (capture.versions().stream().noneMatch(v -> v.protocolVersion() == protocol)) {
            return new Result(0, List.of(), "no bundled data version has protocol " + protocol);
        }
        Palette palette = capture.palette(protocol);
        List<String> differences = new ArrayList<>();
        Map<String, Integer> paletterIds = new LinkedHashMap<>();
        Access.palette().blocks(palette).blockProperties().stream().filter(block -> !block.getName().startsWith("minecraft:"))
            .forEach(block -> paletterIds.put(block.getName(), blockId(block.getProperties())));
        Set<String> captured = new HashSet<>();
        blocks.forEach(block -> captured.add(block.getString("name")));
        for (String block : laterPacks) {
            if (captured.contains(block)) {
                paletterIds.put(block, paletterIds.values().stream().mapToInt(Integer::intValue).max().orElseThrow() + 1);
            }
        }
        for (NbtMap block : blocks) {
            int bds = blockId(block.getCompound("properties"));
            Integer ours = paletterIds.get(block.getString("name"));
            if (!Objects.equals(bds, ours) && differences.size() < 20) {
                differences.add(block.getString("name") + ": block id BDS " + bds + " / Paletter " + ours);
            }
        }
        List<String> wire = blocks.stream().map(block -> block.getString("name")).toList();
        List<String> byId = paletterIds.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
        List<String> order = Access.creative().blockOrder(CreativeOrdering.BDS, byId,
            Access.palette().vanillaData(palette).dataDrivenBlocks().stream().map(block -> block.getName()).toList());
        for (int i = 0; i < Math.max(wire.size(), order.size()) && differences.size() < 20; i++) {
            String bds = i < wire.size() ? wire.get(i) : null;
            String ours = i < order.size() ? order.get(i) : null;
            if (!Objects.equals(bds, ours)) {
                differences.add("block " + i + ": BDS " + bds + " / Paletter " + ours);
            }
        }
        return new Result(blocks.size(), differences, null);
    }

    private static int blockId(NbtMap properties) {
        return properties.getCompound("vanilla_block_data").getInt("block_id");
    }

    /** The captured build is compared with the data of its own version, which may be a preview. */
    static Paletter withPreviews(Path data, CreativeOrdering ordering, PaletteLayer layer) {
        return Paletter.builder().data(PaletteDataSource.directory(data)).previews(true).creativeOrdering(ordering).layer(layer).build();
    }

    /**
     * The custom content as Paletter definitions, in BDS's registration order: blocks by block id, items by runtime id.
     */
    static PaletteLayer layer(List<NbtMap> items, List<NbtMap> blocks) {
        Map<String, NbtMap> dataDriven = new LinkedHashMap<>();
        items.stream().filter(item -> item.getBoolean("componentBased"))
            .sorted(Comparator.comparingInt(item -> item.getShort("id")))
            .forEach(item -> dataDriven.put(item.getString("name"), item));
        PaletteLayer.Builder layer = PaletteLayer.builder("bds-capture");
        Set<String> blockNames = new HashSet<>();
        blocks.stream()
            .sorted(Comparator.comparingInt(block -> block.getCompound("properties").getCompound("vanilla_block_data").getInt("block_id")))
            .forEach(block -> {
                String name = block.getString("name");
                blockNames.add(name);
                // The block's own menu category counts, also if an item replaces its item
                NbtMap menu = block.getCompound("properties").getCompound("menu_category");
                CustomBlock.Builder builder = CustomBlock.builder(name);
                String category = menu.getString("category", "none");
                if (!category.equals("none")) {
                    builder.menuCategory(BlockMenuCategory.builder().category(CreativeCategory.fromBedrockName(category))
                        .group(menu.getString("group", "")).hiddenInCommands(menu.getBoolean("is_hidden_in_commands")).build());
                }
                layer.block(builder.build());
            });
        // Items in BDS's order. One with the identifier of a block replaces the block's item, in the item's own category
        dataDriven.forEach((name, item) -> {
            CustomItem.Builder builder = CustomItem.builder(name).menuCategory(menuCategory(item));
            if (blockNames.contains(name)) {
                builder.component(BlockPlacer.builder().block(name).replaceBlockItem(true).build());
            }
            layer.item(builder.build());
        });
        return layer.build();
    }

    /** @return the item's captured category and group, or null if it is not in the creative inventory */
    static dev.opencollab.paletter.item.component.ItemMenuCategory menuCategory(NbtMap item) {
        NbtMap properties = item.getCompound("data").getCompound("components").getCompound("item_properties");
        int category = properties.getInt("creative_category", 5);
        if (category < 1 || category > 4) {
            return null;
        }
        String group = properties.getString("creative_group", "");
        return dev.opencollab.paletter.item.component.ItemMenuCategory.builder()
            .category(CreativeCategory.values()[category - 1])
            .group(group.isEmpty() ? null : group).build();
    }

    static List<Node> bds(JsonObject creative, Set<String> vanillaStacks) {
        JsonArray groups = creative.getAsJsonArray("groups");
        List<List<String>> stacks = new ArrayList<>();
        groups.forEach(group -> stacks.add(new ArrayList<>()));
        for (JsonElement element : creative.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            String stack = item.get("id").getAsString() + (item.has("damage") ? ":" + item.get("damage").getAsInt() : "");
            if (!item.get("id").getAsString().startsWith("minecraft:") || vanillaStacks.contains(stack)) {
                stacks.get(item.get("groupId").getAsInt()).add(stack);
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (CreativeCategory category : CreativeCategory.values()) {
            for (int i = 0; i < groups.size(); i++) {
                JsonObject group = groups.get(i).getAsJsonObject();
                if (group.get("category").getAsString().equals(category.bedrockName())) {
                    String icon = group.getAsJsonObject("icon").get("id").getAsString();
                    add(nodes, category, group.get("name").getAsString(), icon.equals("minecraft:air") ? null : icon, stacks.get(i));
                }
            }
        }
        return nodes;
    }

    static List<Node> paletter(Palette palette) {
        List<CreativeItemGroup> groups = palette.creative().groups();
        List<List<String>> stacks = new ArrayList<>();
        groups.forEach(group -> stacks.add(new ArrayList<>()));
        for (CreativeItemData item : palette.creative().items()) {
            stacks.get(item.getGroupId()).add(stack(item.getItem()));
        }
        List<Node> nodes = new ArrayList<>();
        for (CreativeCategory category : CreativeCategory.values()) {
            for (int i = 0; i < groups.size(); i++) {
                CreativeItemGroup group = groups.get(i);
                if (group.getCategory() == category.protocol()) {
                    ItemData icon = group.getIcon();
                    add(nodes, category, group.getName(), icon == null || icon.getDefinition() == null
                        || icon.getDefinition().getRuntimeId() == 0 ? null : icon.getDefinition().getIdentifier(), stacks.get(i));
                }
            }
        }
        return nodes;
    }

    private static void add(List<Node> nodes, CreativeCategory category, String group, String icon, List<String> stacks) {
        if (stacks.isEmpty()) {
            return;
        }
        String name = category.bedrockName();
        if (group.isEmpty() && !nodes.isEmpty() && nodes.getLast().group().isEmpty() && nodes.getLast().category().equals(name)) {
            nodes.getLast().stacks().addAll(stacks);
            return;
        }
        nodes.add(new Node(name, group, icon, new ArrayList<>(stacks)));
    }

    private static String stack(ItemData item) {
        return item.getDefinition().getIdentifier() + (item.getDamage() != 0 ? ":" + item.getDamage() : "");
    }

    static List<String> differences(List<Node> expected, List<Node> actual) {
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < Math.max(expected.size(), actual.size()) && differences.size() < 20; i++) {
            Node bds = i < expected.size() ? expected.get(i) : null;
            Node ours = i < actual.size() ? actual.get(i) : null;
            boolean same = bds != null && ours != null && bds.category().equals(ours.category()) && bds.group().equals(ours.group())
                && bds.stacks().equals(ours.stacks()) && (bds.icon() == null || Objects.equals(bds.icon(), ours.icon()));
            if (!same) {
                differences.add("node " + i + ": BDS " + bds + " / Paletter " + ours);
            }
        }
        return differences;
    }

    static List<NbtMap> readList(Path file, String key) throws IOException {
        return Nbt.readGzip(file).getList(key, NbtType.COMPOUND);
    }
}
