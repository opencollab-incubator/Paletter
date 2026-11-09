package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.ItemRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CreativeLayout} compiled for one palette: what is sent in the {@link CreativeContentPacket}.
 * <p>
 * Compilation takes care of every protocol rule, so layouts never need to:
 * <ul>
 *     <li>categories are emitted in client order, and groups never leave their category;</li>
 *     <li>each run of loose stacks becomes an unnamed group with an air icon;</li>
 *     <li>stacks whose item does not exist in this version are dropped, and groups left empty are dropped too
 *     (the client crashes on empty groups);</li>
 *     <li>a group without an icon, or whose icon does not exist in this version, uses its first stack;</li>
 *     <li>network ids are assigned sequentially from 1, so {@link #entry(int)} is an array lookup.</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class CreativeContent {
    private final List<CreativeItemGroup> groups;
    private final List<CreativeItemData> items;
    private final CreativeEntry[] entries;

    private CreativeContent(List<CreativeItemGroup> groups, List<CreativeItemData> items, CreativeEntry[] entries) {
        this.groups = List.copyOf(groups);
        this.items = List.copyOf(items);
        this.entries = entries;
    }

    public static @NonNull CreativeContent compile(@NonNull CreativeLayout layout, @NonNull ItemRegistry items, @NonNull PaletteReport report) {
        List<CreativeItemGroup> groups = new ArrayList<>();
        List<CreativeItemData> contents = new ArrayList<>();
        List<CreativeEntry> entries = new ArrayList<>();
        Compiler compiler = new Compiler(items, report, groups, contents, entries);

        for (CreativeCategory category : CreativeCategory.values()) {
            List<BedrockItemStack> loose = new ArrayList<>();
            for (CreativeNode node : layout.nodes(category)) {
                switch (node) {
                    case CreativeItem item -> loose.add(item.stack());
                    case CreativeGroup group -> {
                        compiler.emit(category, "", null, loose);
                        loose.clear();
                        compiler.emit(category, group.name(), group.icon(), group.stacks());
                    }
                }
            }
            compiler.emit(category, "", null, loose);
        }
        return new CreativeContent(groups, contents, entries.toArray(CreativeEntry[]::new));
    }

    public @NonNull List<CreativeItemGroup> groups() {
        return groups;
    }

    public @NonNull List<CreativeItemData> items() {
        return items;
    }

    /**
     * Looks up what the client asked for in a creative action.
     *
     * @param netId the creative item network id from the request
     * @return the entry, or null if the id is out of range
     */
    public @Nullable CreativeEntry entry(int netId) {
        int index = netId - 1;
        return index >= 0 && index < entries.length ? entries[index] : null;
    }

    public int size() {
        return entries.length;
    }

    public @NonNull CreativeContentPacket toPacket() {
        CreativeContentPacket packet = new CreativeContentPacket();
        packet.getGroups().addAll(groups);
        packet.getContents().addAll(items);
        return packet;
    }

    private record Compiler(ItemRegistry items, PaletteReport report, List<CreativeItemGroup> groups,
                            List<CreativeItemData> contents, List<CreativeEntry> entries) {

        void emit(CreativeCategory category, String name, @Nullable BedrockItemStack icon, List<BedrockItemStack> stacks) {
            if (stacks.isEmpty()) {
                return;
            }
            List<BedrockItemStack> resolvedStacks = new ArrayList<>(stacks.size());
            List<ItemData> resolved = new ArrayList<>(stacks.size());
            for (BedrockItemStack stack : stacks) {
                ItemData data = items.toItemData(stack);
                if (data == null) {
                    report.info("creative", "Left out " + stack + (name.isEmpty() ? "" : " from group " + name)
                        + ": the item does not exist in " + items.version());
                    continue;
                }
                resolvedStacks.add(stack);
                resolved.add(data);
            }
            if (resolved.isEmpty()) {
                if (!name.isEmpty()) {
                    report.info("creative", "Left out group " + name + ": none of its stacks exist in " + items.version());
                }
                return;
            }

            ItemData iconData = ItemData.AIR;
            if (!name.isEmpty()) {
                iconData = icon == null ? null : items.toItemData(icon.withCount(1));
                if (iconData == null) {
                    iconData = resolved.getFirst().toBuilder().count(1).netId(0).build();
                }
            }

            int groupId = groups.size();
            groups.add(new CreativeItemGroup(category.protocol(), name, iconData));
            for (int i = 0; i < resolved.size(); i++) {
                int netId = entries.size() + 1;
                ItemData data = resolved.get(i).toBuilder().netId(netId).build();
                contents.add(new CreativeItemData(data, netId, groupId));
                entries.add(new CreativeEntry(netId, category, name.isEmpty() ? null : name, resolvedStacks.get(i), data));
            }
        }
    }
}
