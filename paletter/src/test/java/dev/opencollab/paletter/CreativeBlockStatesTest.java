package dev.opencollab.paletter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeContent;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.VerifiedBuild;
import dev.opencollab.paletter.internal.creative.RawGroup;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.creative.RawNode;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creative content sends a block with a stack exactly where BDS does. An item that places a block but has no block of
 * its own (doors, signs, beds, seeds, redstone, hoppers, campfires, ...) is sent without one, in the creative content as
 * everywhere else ({@link Palette#encode}, {@link Palette#blockOf}).
 */
class CreativeBlockStatesTest {
    private static final Path BDS = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");
    private static final Paletter PALETTER = Paletter.builder().previews(true).build();

    /** A creative stack as far as the block goes: item, aux and whether a block is sent with it. */
    private record Sent(String identifier, int damage, boolean block) {
        static Sent of(ItemData item) {
            return new Sent(item.getDefinition().getIdentifier(), item.getDamage(), item.getBlockDefinition() != null);
        }
    }

    private static List<Sent> stacks(CreativeContent creative) {
        return creative.items().stream().map(CreativeItemData::getItem).map(Sent::of).toList();
    }

    /** Every bundled version: the bundled creative stacks are BDS's capture of a world without experiments, blocks included. */
    @TestFactory
    Stream<DynamicTest> everyStackAndIconCarriesABlockExactlyWhereTheBundledCaptureDoes() {
        return PALETTER.versions().stream().map(version -> DynamicTest.dynamicTest(version.name(), () -> {
            Palette palette = PALETTER.vanilla(version);
            Set<String> gated = palette.gating().disabledItems().keySet();
            RawLayout captured = PALETTER.vanillaData(version).creative();
            List<Sent> expected = new ArrayList<>();
            captured.forEachStack(stack -> {
                if (!gated.contains(stack.identifier())) {
                    expected.add(new Sent(stack.identifier(), stack.damage(), stack.blockState() != null));
                }
            });
            assertEquals(expected, stacks(palette.creative()));

            Map<String, Boolean> icons = new HashMap<>();
            for (CreativeCategory category : CreativeCategory.values()) {
                for (RawNode node : captured.nodes(category)) {
                    if (node instanceof RawGroup group && group.icon() != null) {
                        icons.put(group.name(), group.icon().blockState() != null);
                    }
                }
            }
            int compared = 0;
            for (CreativeItemGroup group : palette.creative().groups()) {
                Boolean block = icons.get(group.getName());
                if (block != null) {
                    assertEquals(block, group.getIcon().getBlockDefinition() != null, "icon of " + group.getName());
                    compared++;
                }
            }
            assertTrue(compared > 0, "no group icon compared");
        }));
    }

    /**
     * Every verified build with a capture of a world without experiments ({@code vanilla/capture.json}): as many creative
     * stacks, and as many stacks and group icons with a block, as BDS sent.
     */
    @TestFactory
    Stream<DynamicTest> theCountsEqualTheCapturesWithoutExperiments() {
        List<DynamicTest> tests = new ArrayList<>();
        for (DataVersion version : PALETTER.versions()) {
            for (VerifiedBuild build : PALETTER.verifiedBuilds(version)) {
                Path capture = BDS.resolve(build.bdsVersion()).resolve("vanilla/capture.json");
                if (!Files.exists(capture)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(version.name() + " " + build.bdsVersion(), () -> {
                    JsonObject json;
                    try (Reader reader = Files.newBufferedReader(capture)) {
                        json = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    assertTrue(json.getAsJsonArray("experiments").isEmpty(), "a capture without experiments");
                    CreativeContent creative = PALETTER.vanilla(version).creative();
                    long blocks = creative.items().stream().filter(item -> item.getItem().getBlockDefinition() != null).count()
                        + creative.groups().stream().filter(group -> group.getIcon() != null && group.getIcon().getBlockDefinition() != null).count();
                    assertEquals(json.get("creativeItems").getAsInt(), creative.size());
                    assertEquals(json.get("creativeBlockIdsResolved").getAsInt(), blocks, "creative stacks and group icons with a block");
                    assertEquals(json.get("items").getAsInt(), PALETTER.vanilla(version).itemDefinitions().size(), "item registry");
                }));
            }
        }
        assertFalse(tests.isEmpty(), "no build with a capture");
        return tests.stream();
    }

    /** The builds with a creative probe capture: its vanilla stacks, in order, as the palette of the build's version sends them. */
    @TestFactory
    Stream<DynamicTest> theVanillaStacksEqualTheCreativeProbeCaptures() {
        List<DynamicTest> tests = new ArrayList<>();
        for (DataVersion version : PALETTER.versions()) {
            for (VerifiedBuild build : PALETTER.verifiedBuilds(version)) {
                Path capture = BDS.resolve(build.bdsVersion()).resolve("creative-probe/creative_items.json.gz");
                if (!Files.exists(capture)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(version.name() + " " + build.bdsVersion(), () -> {
                    List<Sent> expected = new ArrayList<>();
                    for (JsonElement element : read(capture).getAsJsonArray("items")) {
                        JsonObject item = element.getAsJsonObject();
                        String identifier = item.get("id").getAsString();
                        if (identifier.startsWith("minecraft:")) {
                            expected.add(new Sent(identifier, item.has("damage") ? item.get("damage").getAsInt() : 0, item.has("block_runtime_id")));
                        }
                    }
                    assertEquals(expected, stacks(PALETTER.vanilla(version).creative()));
                }));
            }
        }
        assertFalse(tests.isEmpty(), "no build with a creative probe capture");
        return tests.stream();
    }

    private static JsonObject read(Path gzip) throws IOException {
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(gzip)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void aDoorIsSentWithoutABlockAndPlanksWithOne() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertNull(palette.blockOf(BedrockItems.WOODEN_DOOR));
        assertNull(palette.encode(BedrockItemStack.of(BedrockItems.WOODEN_DOOR)).getBlockDefinition());
        assertTrue(palette.encode(BedrockItemStack.of(BedrockItems.OAK_PLANKS)).getBlockDefinition() instanceof RegisteredBlockState block
            && block.identifier().equals("minecraft:oak_planks"));
        // The other direction is unchanged: the door block's item is the door
        assertEquals(BedrockItems.WOODEN_DOOR, palette.itemOf(BedrockBlocks.WOODEN_DOOR));
    }
}
