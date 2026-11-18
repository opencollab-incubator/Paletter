package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.ItemDisplayName;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A custom item of a layer, set up as in the Bedrock server example, survives {@link Palette#encode} and {@link Palette#decode}. */
class CustomItemRoundTripTest {
    @Test
    void customItemOfALayerRoundTrips() {
        CustomItem ruby = CustomItem.builder("example:ruby")
            .component(Icon.of("ruby"))
            .component(ItemDisplayName.builder().value("item.example.ruby.name").build())
            .component(MaxStackSize.of(16))
            .build();
        Paletter paletter = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.POPLAR_BOAT, BedrockItems.OAK_BOAT).build())
            .layer(PaletteLayer.builder("server").item(ruby).build())
            .build();
        Palette palette = paletter.palette(paletter.latest().protocolVersion());

        NbtMap tag = NbtMap.builder().putString("custom", "value").build();
        BedrockItemStack stack = new BedrockItemStack(ruby.type().variant(), 3, tag, null);
        ItemData data = palette.encode(stack);
        assertEquals("example:ruby", data.getDefinition().getIdentifier());

        BedrockItemStack decoded = palette.decode(data);
        assertNotNull(decoded);
        assertEquals(ruby.type(), decoded.type());
        assertEquals(ruby.type().variant(), decoded.variant());
        assertEquals(3, decoded.count());
        assertEquals(tag, decoded.nbt());
        assertEquals(stack, decoded);
        // Decoding hands out the palette's own handle, the same one every time
        assertSame(decoded.variant(), palette.decode(data).variant());
    }
}
