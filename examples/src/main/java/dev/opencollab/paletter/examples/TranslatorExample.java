package dev.opencollab.paletter.examples;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.ItemDisplayName;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlocks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A Java {@code <->} Bedrock translator: its mappings are int tables written once against the newest Bedrock version, then
 * turned into runtime ids per client version. Run:
 * {@code ./gradlew :examples:run -PmainClass=dev.opencollab.paletter.examples.TranslatorExample}.
 */
public final class TranslatorExample {
    public static void main(String[] args) {
        // The translator's custom content, built with the generated component API
        CustomItem ruby = CustomItem.builder("example:ruby")
            .component(Icon.of("ruby"))
            .component(ItemDisplayName.builder().value("item.example.ruby.name").build())
            .menuCategory(CreativeCategory.ITEMS)
            .build();
        CustomBlock lamp = CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .component(Geometry.of("minecraft:geometry.full_block"))
            .menuCategory(CreativeCategory.CONSTRUCTION)
            .build();
        PaletteLayer translator = PaletteLayer.builder("translator").item(ruby).block(lamp).build();

        // Hashed block ids: a state has the same id in every version, and custom blocks can also come per session
        Paletter paletter = Paletter.builder().layer(translator).build();

        // Java block state id -> Bedrock block handle, once (0 air, 1 stone, 2 lamp). Handles are the same in every version
        Palette shared = paletter.palette(paletter.latest().protocolVersion());
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

        // Items, both directions: variant <-> the translator's own item table. Decoding only gives variants the palette
        // knows, so a map keyed by them stays bounded whatever a client sends; an aux no variant has stays on the stack
        Map<BedrockItemVariant, String> bedrockToJava = new HashMap<>();
        bedrockToJava.put(ruby.type().variant(), "java:ruby");
        ItemData toClient = shared.encode(BedrockItemStack.of(ruby.type(), 2));
        System.out.println("from client: " + bedrockToJava.get(shared.decode(toClient).variant()));
        BedrockItemStack odd = shared.decode(toClient.toBuilder().damage(12345).build());
        System.out.println("from client with aux " + odd.aux() + ": " + bedrockToJava.get(odd.variant()));

        // A session: a short lived layer on top of the shared palette; what it does not touch stays identical
        CustomItem sessionItem = CustomItem.builder("example:session_item").component(Icon.of("session_item")).build();
        Palette session = shared.extend(PaletteLayer.builder("session").item(sessionItem).build());
        System.out.println("session sends the same stone block: " + (session.blockDefinition(BedrockBlocks.STONE.defaultState()) == shared.blockDefinition(BedrockBlocks.STONE.defaultState())));
        System.out.println("session adds item ids: " + (session.itemDefinitions().size() - shared.itemDefinitions().size()));
    }
}
