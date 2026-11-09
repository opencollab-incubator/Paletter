package org.geysermc.paletter.examples;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.Fallbacks;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.DisplayName;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;

/**
 * A Bedrock-only server: one palette per client version, block and item content stored as handles, converted at the
 * network edge. Run: {@code ./gradlew :examples:run}.
 */
public final class BedrockServerExample {
    public static void main(String[] args) {
        // The server's content, described once, valid for every client version
        CustomItem ruby = CustomItem.builder("example:ruby")
            .component(Icon.of("ruby"))
            .component(DisplayName.builder().value("item.example.ruby.name").build())
            .component(MaxStackSize.of(16))
            .build();
        PaletteLayer server = PaletteLayer.builder("server").item(ruby).build();

        // The global policy: one Paletter, one fallback policy, the content of the server as a layer
        Paletter paletter = Paletter.builder()
            .blockNetworkIds(BlockNetworkIds.HASHED)
            .fallbacks(Fallbacks.builder().item(BedrockItems.POPLAR_BOAT, BedrockItems.OAK_BOAT).build())
            .layer(server)
            .build();

        // One palette per client version, compiled once and shared by all its players
        BedrockCodec codec = Bedrock_v2225.CODEC;
        Palette palette = paletter.palette(codec);

        // Login: definition registries for the codec, block properties and the id mode for start game
        palette.applyTo(codec.createHelper());
        StartGamePacket startGame = new StartGamePacket();
        palette.applyTo(startGame);
        System.out.println("hashed block ids: " + startGame.isBlockNetworkIdsHashed());
        System.out.println("items in the component packet: " + palette.itemComponentPacket().getItems().size());
        System.out.println("creative entries: " + palette.creativeContentPacket().getContents().size());

        // Content is held as handles, independent of the client version; a palette turns one into what its client needs
        BedrockBlockState stone = BedrockBlocks.STONE.defaultState();
        System.out.println("stone: runtime id " + palette.runtimeId(stone));

        // Items: handle -> stack for the client, and back
        ItemData stack = palette.encode(BedrockItemStack.of(ruby.type().base(), 3));
        System.out.println("decode(ruby stack) is ruby: " + (palette.decode(stack).variant() == ruby.type().base()));
        ItemData apple = palette.encode(BedrockItemStack.of(BedrockItems.APPLE));
        System.out.println("decode(apple) = " + palette.decode(apple));

        // What an item is made of in this version: its registered item and its properties
        RegisteredItem registered = palette.item(ruby.type());
        System.out.println("ruby properties: " + registered.properties().all().size() + ", origin " + registered.origin());
        System.out.println("properties of an apple in the newest version, no palette needed: "
            + paletter.properties(BedrockItems.APPLE, paletter.latest()).all().size());

        // Debugging: why does this id look the way it does?
        System.out.println(palette.explain(BedrockBlocks.STONE.defaultState()));
    }
}
