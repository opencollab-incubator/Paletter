package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.CommittedBuilds;
import dev.opencollab.paletter.tools.model.addon.AddonWriter;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.data.DataVersion;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paletter's default creative ordering against what BDS sent (see docs/wire-notes.md): the creative inventory of the
 * creative order probe (every kind of menu category placement) of every build that has one (the preview's layout has items only
 * the preview has), and of the large probe (700 blocks, so BDS's block map has more than 512 buckets), and the block ids and block
 * map order of the spec add-on of every build, which BDS iterates for the start game block properties and for the block pass alike.
 */
class CreativeOrderCheckTest {
    private static final Path CAPTURES = Path.of("../data/bds/1.26.63.1");
    private static final Path DATA = Path.of("../paletter/src/main/resources/paletter");

    /** The builds that have the capture, with the protocol of their spec capture. */
    private static Stream<Arguments> builds(String capture, int customStacks) {
        return CommittedBuilds.all().stream().filter(b -> Files.exists(CommittedBuilds.directory(b).resolve(capture).resolve("creative_items.json.gz")))
            .map(b -> Arguments.of(b, protocol(b), capture, customStacks));
    }

    static Stream<Arguments> creativeCaptures() {
        return Stream.concat(builds("creative-probe", 30), builds("creative-large", 700));
    }

    static Stream<Arguments> specCaptures() {
        return CommittedBuilds.all().stream().filter(b -> Files.exists(CommittedBuilds.directory(b).resolve("spec").resolve("capture.json")))
            .map(b -> Arguments.of(b, protocol(b)));
    }

    private static int protocol(String build) {
        try {
            return JsonParser.parseString(Files.readString(CommittedBuilds.directory(build).resolve("spec").resolve("capture.json")))
                .getAsJsonObject().get("protocol").getAsInt();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @ParameterizedTest
    @MethodSource("creativeCaptures")
    void bdsOrderingMatchesTheCapture(String build, int protocol, String capture, int customStacks) throws Exception {
        Path directory = CommittedBuilds.directory(build).resolve(capture);
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(protocol, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"));
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertTrue(result.customStacks() >= customStacks, "only " + result.customStacks() + " custom stacks compared");
    }

    /**
     * Every spec capture of a build: BDS numbers the custom blocks and puts them in the block pass in Paletter's order. The
     * blocks of the engine packs come from packs after the main one in the world's pack list, as in the spec stage of update.
     */
    @ParameterizedTest
    @MethodSource("specCaptures")
    void blockMapOrderMatchesTheSpecCaptures(String build, int protocol) throws Exception {
        Path directory = CommittedBuilds.directory(build).resolve("spec");
        CreativeOrderCheck.Result result = CreativeOrderCheck.blockOrder(protocol, DATA, directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"), AddonWriter.enginePacks().stream().map(AddonWriter.EnginePack::identifier).toList());
        // A hotfix build of a protocol that no data version starts with has no layout to compare with (1.26.45.1)
        org.junit.jupiter.api.Assumptions.assumeTrue(result.skipped() == null, result.skipped());
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertTrue(result.customStacks() >= 1000, "only " + result.customStacks() + " custom blocks compared");
    }

    @Test
    void blockMapOrderOfAnUnbundledProtocolIsSkipped() throws Exception {
        Path directory = CAPTURES.resolve("spec");
        CreativeOrderCheck.Result result = CreativeOrderCheck.blockOrder(2226, DATA, directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"), List.of());
        assertNotNull(result.skipped());
    }

    @Test
    void appendOrderingDoesNot() throws Exception {
        Path directory = CAPTURES.resolve("creative-probe");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(2225, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"), CreativeOrdering.APPEND);
        assertFalse(result.matches());
    }

    @Test
    void previewProtocolIsComparedWithThePreviewLayout(@TempDir Path directory) throws Exception {
        Paletter previews = CreativeOrderCheck.withPreviews(DATA, CreativeOrdering.BDS, PaletteLayer.builder("none").build());
        DataVersion preview = previews.versions().getLast();
        assertTrue(preview.protocolVersion() > 2225, "no preview bundled");
        Path creative = directory.resolve("creative_items.json");
        Files.writeString(creative, creativeItems(previews.palette(preview.protocolVersion())));
        Path empty = directory.resolve("empty.nbt");
        Nbt.writeGzip(empty, NbtMap.builder().putList("items", NbtType.COMPOUND).putList("blocks", NbtType.COMPOUND).build());
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(preview.protocolVersion(), DATA, creative, empty, empty);
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertFalse(CreativeOrderCheck.check(2225, DATA, creative, empty, empty).matches(), "the 26.60 layout should differ");
    }

    @Test
    void unbundledProtocolIsSkipped() throws Exception {
        Path directory = CAPTURES.resolve("creative-probe");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(2226, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"));
        assertNotNull(result.skipped());
        assertTrue(result.differences().isEmpty());
    }

    /** What BDS would send for a world with no add-ons: the palette's creative inventory as {@code creative_items.json}. */
    private static String creativeItems(Palette palette) {
        JsonArray groups = new JsonArray();
        for (CreativeItemGroup group : palette.creative().groups()) {
            JsonObject icon = new JsonObject();
            icon.addProperty("id", group.getIcon() == null || group.getIcon().getDefinition() == null
                || group.getIcon().getDefinition().getRuntimeId() == 0 ? "minecraft:air" : group.getIcon().getDefinition().getIdentifier());
            JsonObject json = new JsonObject();
            json.addProperty("category", java.util.Arrays.stream(CreativeCategory.values())
                .filter(category -> category.protocol() == group.getCategory()).findFirst().orElseThrow().bedrockName());
            json.addProperty("name", group.getName());
            json.add("icon", icon);
            groups.add(json);
        }
        JsonArray items = new JsonArray();
        for (CreativeItemData item : palette.creative().items()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", item.getItem().getDefinition().getIdentifier());
            if (item.getItem().getDamage() != 0) {
                json.addProperty("damage", item.getItem().getDamage());
            }
            json.addProperty("groupId", item.getGroupId());
            items.add(json);
        }
        JsonObject root = new JsonObject();
        root.add("groups", groups);
        root.add("items", items);
        return root.toString();
    }
}
