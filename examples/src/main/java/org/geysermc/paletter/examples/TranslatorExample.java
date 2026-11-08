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
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.component.DisplayName;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.vanilla.BedrockBlocks;

import java.util.Arrays;

/**
 * A Java <-> Bedrock translator: its mappings are int tables written once against the newest Bedrock version, then
 * turned into runtime ids per client version. Run:
 * {@code ./gradlew :examples:run -PmainClass=org.geysermc.paletter.examples.TranslatorExample}.
 */
public final class TranslatorExample {
    public static void main(String[] args) {
        Paletter paletter = Paletter.bundled();

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

        // Java block state id -> canonical id, once (0 air, 1 stone, 2 lamp). Custom ids are the same in every version
        Palette shared = paletter.palette(Bedrock_v2225.CODEC, BlockNetworkIds.HASHED, translator);
        int[] javaToCanonical = {
            paletter.canonicalBlock(BedrockBlocks.AIR.defaultState()),
            paletter.canonicalBlock(BedrockBlocks.STONE.defaultState()),
            shared.canonicalBlock(lamp.type().defaultState())
        };

        // Per client version: chunk translation is two array lookups. With HASHED ids the vanilla entries are equal
        for (int protocol : new int[]{paletter.versions().getFirst().protocolVersion(), paletter.latest().protocolVersion()}) {
            Palette palette = paletter.palette(protocol, BlockNetworkIds.HASHED, translator);
            int[] javaToRuntime = Arrays.stream(javaToCanonical).map(palette::runtimeId).toArray();
            System.out.println(protocol + ": java -> runtime ids " + Arrays.toString(javaToRuntime));
        }

        // Items, both directions: canonical id <-> the translator's own item table
        String[] canonicalToJava = new String[shared.canonicalItemCount()];
        int rubyId = shared.canonicalItem(ruby.type());
        canonicalToJava[rubyId] = "java:ruby";
        ItemData toClient = shared.item(rubyId).toItemData().count(2).build();
        System.out.println("from client: " + canonicalToJava[shared.match(toClient)]);

        // A session: a short lived layer on top of the shared palette; what it does not touch stays identical
        Palette session = shared.extend(PaletteLayer.builder("session")
            .variant(ItemVariant.of("minecraft:potion", 9999)).build());
        System.out.println("session shares the block palette: " + (session.blocks() == shared.blocks()));
        System.out.println("session adds item ids: " + (session.canonicalItemCount() - shared.canonicalItemCount()));
    }
}
