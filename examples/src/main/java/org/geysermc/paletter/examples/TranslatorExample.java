package org.geysermc.paletter.examples;

import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.DisplayName;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockBlocks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A Java <-> Bedrock translator: its mappings are int tables written once against the newest Bedrock version, then
 * turned into runtime ids per client version. Run:
 * {@code ./gradlew :examples:run -PmainClass=org.geysermc.paletter.examples.TranslatorExample}.
 */
public final class TranslatorExample {
    public static void main(String[] args) {
        // The translator's custom content, built with the generated component API
        CustomItem ruby = CustomItem.builder("example:ruby")
            .component(Icon.of("ruby"))
            .component(DisplayName.builder().value("item.example.ruby.name").build())
            .creative(CreativePlacement.loose(CreativeCategory.ITEMS))
            .build();
        CustomBlock lamp = CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .component(Geometry.of("minecraft:geometry.full_block"))
            .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
            .build();
        PaletteLayer translator = PaletteLayer.builder("translator").item(ruby).block(lamp).build();

        // Hashed block ids: a state has the same id in every version, and custom blocks can also come per session
        Paletter paletter = Paletter.builder().blockNetworkIds(BlockNetworkIds.HASHED).layer(translator).build();

        // Java block state id -> Bedrock block handle, once (0 air, 1 stone, 2 lamp). Handles are the same in every version
        Palette shared = paletter.palette(Bedrock_v2225.CODEC);
        BedrockBlockState[] javaToBedrock = {
            BedrockBlocks.AIR.defaultState(),
            BedrockBlocks.STONE.defaultState(),
            lamp.type().defaultState()
        };

        // Per client version: chunk translation is one array lookup per block. With HASHED ids the vanilla entries are equal
        for (int protocol : new int[]{paletter.versions().getFirst().protocolVersion(), paletter.latest().protocolVersion()}) {
            Palette palette = paletter.palette(protocol);
            int[] javaToRuntime = Arrays.stream(javaToBedrock).mapToInt(palette::runtimeId).toArray();
            System.out.println(protocol + ": java -> runtime ids " + Arrays.toString(javaToRuntime));
        }

        // Items, both directions: handle <-> the translator's own item table
        Map<BedrockItemVariant, String> bedrockToJava = new HashMap<>();
        bedrockToJava.put(ruby.type().base(), "java:ruby");
        ItemData toClient = shared.encode(BedrockItemStack.of(ruby.type().base(), 2));
        System.out.println("from client: " + bedrockToJava.get(shared.decode(toClient).variant()));

        // A session: a short lived layer on top of the shared palette; what it does not touch stays identical
        CustomItem sessionItem = CustomItem.builder("example:session_item").component(Icon.of("session_item")).build();
        Palette session = shared.extend(PaletteLayer.builder("session").item(sessionItem).build());
        System.out.println("session shares the block palette: " + (session.blocks() == shared.blocks()));
        System.out.println("session adds item ids: " + (session.items().definitions().size() - shared.items().definitions().size()));
    }
}
