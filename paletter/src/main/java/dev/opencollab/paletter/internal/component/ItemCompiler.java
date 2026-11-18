package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.item.component.ItemComponent;
import dev.opencollab.paletter.item.component.ItemMenuCategory;

import java.util.List;

/**
 * Compiles a custom item to the item component data of one protocol: what BDS sends for every custom item
 * ({@link ItemWire#envelope}), the description's menu category ({@link ItemWire#menuCategory}), then each component
 * through its generated encoder.
 */
public final class ItemCompiler {
    private ItemCompiler() {
    }

    /**
     * @param menu the description's menu category, or null for none
     * @return the full component data of a data driven item definition, as sent in the item component packet
     */
    public static @NonNull NbtMap componentData(@NonNull String identifier, int runtimeId, @NonNull List<ItemComponent> components,
                                                @Nullable ItemMenuCategory menu,
                                                @NonNull WireContext ctx) {
        WireContext subject = ctx.subject(identifier);
        WireNode out = new WireNode();
        ItemWire.envelope(subject, out);
        ItemWire.menuCategory(menu(menu), subject, out);
        write(out, components, subject);
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

    /** The description's menu category with its group namespaced like BDS, or null if there is none. */
    private static @Nullable ItemMenuCategory menu(@Nullable ItemMenuCategory menu) {
        return menu == null || menu.group() == null ? menu : menu.toBuilder().group(namespaced(menu.group())).build();
    }

    /** Like BDS: the group as add-on JSON names it, always namespaced (vanilla groups are minecraft:). */
    private static String namespaced(String group) {
        return group.indexOf(':') < 0 ? "minecraft:" + group : group;
    }
}
