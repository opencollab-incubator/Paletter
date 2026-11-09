package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.component.ItemComponent;
import org.geysermc.paletter.item.component.MenuCategory;

import java.util.List;

/**
 * Compiles a custom item to the item component data of one protocol: the item properties BDS sends for every custom
 * item, the description's menu category, then each component through its generated encoder.
 * <p>
 * The item properties are one window: the same keys and values in every spec capture (protocols 1001 to 2225), which
 * {@code EnvelopeCapturesTest} checks; a capture that differs fails it and needs a window here.
 */
public final class ItemCompiler {
    private ItemCompiler() {
    }

    /**
     * @param menu the description's menu category, or null to derive it from {@code creative} (else Bedrock's default)
     * @return the full component data of a data driven item definition, as sent in the item component packet
     */
    public static @NonNull NbtMap componentData(@NonNull String identifier, int runtimeId, @NonNull List<ItemComponent> components,
                                                @Nullable MenuCategory menu, @Nullable CreativePlacement creative,
                                                @NonNull WireContext ctx) {
        WireNode out = new WireNode();
        // What BDS 1.26.63.1 sends in item_properties for every custom item (capture/WIRE-NOTES.md, Common item shape)
        WireNode p = out.child("item_properties");
        p.put("allow_off_hand", (byte) 0);
        p.put("can_destroy_in_creative", (byte) 1);
        p.put("creative_category", 4);
        p.put("creative_group", "");
        p.put("damage", 0);
        p.put("enchantable_slot", "none");
        p.put("enchantable_value", 0);
        p.put("foil", (byte) 0);
        p.put("frame_count", 1);
        p.put("hand_equipped", (byte) 0);
        p.put("hidden_in_commands", (byte) 2);
        p.put("liquid_clipped", (byte) 0);
        p.put("max_stack_size", 64);
        p.put("mining_speed", 1f);
        p.put("should_despawn", (byte) 1);
        p.put("stacked_by_data", (byte) 0);
        p.put("use_animation", 0);
        p.put("use_duration", 0);
        out.put("item_tags", Wire.EMPTY_LIST);
        menu(p, menu, creative);
        write(out, components, ctx.subject(identifier));
        return NbtMap.builder()
            .putString("name", identifier)
            .putInt("id", runtimeId)
            .putCompound("components", out.build())
            .build();
    }

    /**
     * Writes components over existing component data, e.g. a vanilla item's: keys a component writes replace the
     * original ones, everything else stays.
     *
     * @param original the {@code {name, id, components}} item component data
     */
    public static @NonNull NbtMap override(@NonNull NbtMap original, @NonNull List<ItemComponent> components, @NonNull WireContext ctx) {
        WireNode out = WireNode.of(original.getCompound("components"));
        write(out, components, ctx.subject(original.getString("name")));
        return original.toBuilder().putCompound("components", out.build()).build();
    }

    private static void write(WireNode out, List<ItemComponent> components, WireContext ctx) {
        for (ItemComponent component : components) {
            ItemWire.write(component, ctx, out);
        }
    }

    private static void menu(WireNode properties, @Nullable MenuCategory menu, @Nullable CreativePlacement creative) {
        if (menu == null && creative == null) {
            // BDS 1.26.63.1: an item without menu_category (or with an empty one) is in no category
            properties.put("creative_category", 5);
        }
        if (menu != null) {
            // BDS 1.26.63.1: a menu_category without a category puts the item in none
            properties.put("creative_category", menu.category() == null ? 5 : categoryId(menu.category()));
            if (menu.group() != null) {
                properties.put("creative_group", namespaced(menu.group()));
            }
            if (menu.hiddenInCommands() != null) {
                // 2 is "not set", the default
                properties.put("hidden_in_commands", (byte) (menu.hiddenInCommands() ? 1 : 0));
            }
        } else if (creative != null) {
            properties.put("creative_category", creative.category().itemPropertyId());
            if (creative.group() != null) {
                properties.put("creative_group", namespaced(creative.group()));
            }
        }
    }

    /** Like BDS: the group as add-on JSON names it, always namespaced (vanilla groups are minecraft:). */
    private static String namespaced(String group) {
        return group.indexOf(':') < 0 ? "minecraft:" + group : group;
    }

    static int categoryId(MenuCategory.Category category) {
        return switch (category) {
            case CONSTRUCTION -> 1;
            case NATURE -> 2;
            case EQUIPMENT -> 3;
            case ITEMS -> 4;
            case NONE -> 5;
        };
    }
}
