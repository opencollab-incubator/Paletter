package org.geysermc.paletter.palette.item;

import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;

// Source: item_components.nbt - a compound keyed by item identifier, each holding a "components"
// compound of real behavior-pack components (namespaced, e.g. "minecraft:durability") alongside
// two synthetic buckets that aren't real component types: "item_properties" and "item_tags".
public record ItemComponentPalette(List<ItemComponentData> items) {
    public static ItemComponentPalette fromNbt(NbtMap root) {
        List<ItemComponentData> items = new ArrayList<>();
        for (var entry : root.entrySet()) {
            if (entry.getValue() instanceof NbtMap itemMap) {
                items.add(new ItemComponentData(entry.getKey(), itemMap.getCompound("components")));
            }
        }
        return new ItemComponentPalette(items);
    }
}
