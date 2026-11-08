package org.geysermc.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.block.component.MenuCategory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Checks {@link CreativeOrdering#BDS} against a capture: the custom items and blocks BDS sent (with their menu
 * categories, item runtime ids and block ids, i.e. BDS's registration order) are compiled through Paletter as one
 * layer, and the resulting creative inventory must show exactly what BDS sent. See capture/CREATIVE-ORDER.md.
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

    public record Result(int customStacks, List<String> differences) {
        public boolean matches() {
            return differences.isEmpty();
        }
    }

    private CreativeOrderCheck() {
    }

    /**
     * @param creativeItems the full {@code creative_items.json} of the capture (gzipped or not)
     * @param customItems {@code custom_item_registry.nbt} of the capture
     * @param customBlocks {@code custom_block_properties.nbt} of the capture
     */
    public static Result check(Paletter paletter, int protocol, Path creativeItems, Path customItems, Path customBlocks) throws IOException {
        return check(paletter, protocol, creativeItems, customItems, customBlocks, CreativeOrdering.BDS);
    }

    public static Result check(Paletter paletter, int protocol, Path creativeItems, Path customItems, Path customBlocks,
                        CreativeOrdering ordering) throws IOException {
        PaletteLayer layer = layer(readList(customItems, "items"), readList(customBlocks, "blocks"), ordering);
        Palette palette = paletter.palette(protocol, layer);
        Palette vanilla = paletter.vanilla(palette.version());

        Set<String> vanillaStacks = new HashSet<>();
        vanilla.creative().items().forEach(entry -> vanillaStacks.add(stack(entry.getItem())));
        List<Node> expected = bds(readJson(creativeItems), vanillaStacks);
        List<Node> actual = paletter(palette);
        int custom = (int) expected.stream().flatMap(node -> node.stacks().stream()).filter(id -> !id.startsWith("minecraft:")).count();
        return new Result(custom, differences(expected, actual));
    }

    /**
     * The custom content as Paletter definitions, in BDS's registration order: blocks by block id, items by runtime id.
     */
    static PaletteLayer layer(List<NbtMap> items, List<NbtMap> blocks, CreativeOrdering ordering) {
        Map<String, NbtMap> dataDriven = new LinkedHashMap<>();
        items.stream().filter(item -> item.getBoolean("componentBased"))
            .sorted(Comparator.comparingInt(item -> item.getShort("id")))
            .forEach(item -> dataDriven.put(item.getString("name"), item));
        PaletteLayer.Builder layer = PaletteLayer.builder("bds-capture").creativeOrdering(ordering);
        Set<String> blockNames = new HashSet<>();
        blocks.stream()
            .sorted(Comparator.comparingInt(block -> block.getCompound("properties").getCompound("vanilla_block_data").getInt("block_id")))
            .forEach(block -> {
                String name = block.getString("name");
                blockNames.add(name);
                NbtMap menu = block.getCompound("properties").getCompound("menu_category");
                CustomBlock.Builder builder = CustomBlock.builder(name);
                NbtMap item = dataDriven.get(name);
                if (item != null) {
                    // A data driven item of the same name replaces the block item; its menu category counts
                    builder.item(CustomBlock.BlockItem.dataDriven());
                    CreativePlacement placement = placement(item);
                    if (placement != null) {
                        builder.creative(placement);
                    }
                } else {
                    String category = menu.getString("category", "none");
                    if (!category.equals(CreativeCategory.NONE_NAME)) {
                        builder.menuCategory(MenuCategory.builder().category(MenuCategory.Category.fromValue(category))
                            .group(menu.getString("group", "")).hiddenInCommands(menu.getBoolean("is_hidden_in_commands")).build());
                    }
                }
                layer.block(builder.build());
            });
        dataDriven.forEach((name, item) -> {
            if (!blockNames.contains(name)) {
                layer.item(CustomItem.builder(name).creative(placement(item)).build());
            }
        });
        return layer.build();
    }

    private static CreativePlacement placement(NbtMap item) {
        NbtMap properties = item.getCompound("data").getCompound("components").getCompound("item_properties");
        int category = properties.getInt("creative_category", 5);
        if (category < 1 || category > 4) {
            return null;
        }
        CreativeCategory creativeCategory = CreativeCategory.values()[category - 1];
        String group = properties.getString("creative_group", "");
        return group.isEmpty() ? CreativePlacement.loose(creativeCategory) : CreativePlacement.group(creativeCategory, group);
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

    static JsonObject readJson(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            }
        }
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
