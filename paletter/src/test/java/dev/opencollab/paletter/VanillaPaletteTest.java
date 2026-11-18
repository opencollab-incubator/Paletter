package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemCategory;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeContent;
import dev.opencollab.paletter.creative.CreativeEntry;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.ExperimentContent;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.item.RegisteredItem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaPaletteTest {
    static final Paletter PALETTER = Paletter.bundled();

    @Test
    void bundledVersionsLoad() {
        List<DataVersion> versions = PALETTER.versions();
        assertEquals(List.of("26.30", "26.40", "26.50", "26.60"), versions.stream().map(DataVersion::name).toList());
        for (DataVersion version : versions) {
            Palette palette = PALETTER.vanilla(version);
            assertNotNull(palette.blocks().air());
            assertTrue(palette.items().definitions().size() > 1900, palette.toString());
            assertTrue(palette.creative().size() > 1800, palette.toString());
        }
    }

    @Test
    void protocolVersionsResolveToTheNewestDataNotNewerThanThem() {
        assertEquals("26.30", PALETTER.version(1001).name());
        assertEquals("26.30", PALETTER.version(2167).name());
        assertEquals("26.40", PALETTER.version(2168).name());
        // 26.45 has its own protocol, but no new palette
        assertEquals("26.40", PALETTER.version(2169).name());
        assertEquals("26.60", PALETTER.version(9999).name());
        assertThrows(IllegalArgumentException.class, () -> PALETTER.version(1));
    }

    @Test
    void definitionsAreRegisteredByIdentity() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        RegisteredItem apple = palette.items().getDefinition("minecraft:apple");
        assertNotNull(apple);
        assertSame(apple, palette.items().getDefinition(apple.getRuntimeId()));
        assertTrue(palette.items().isRegistered(apple));
        assertTrue(palette.blocks().isRegistered(palette.blocks().air()));
    }

    @Test
    void compiledCreativeContentFollowsProtocolRules() {
        CreativeContent content = PALETTER.vanilla(PALETTER.latest()).creative();
        List<CreativeItemGroup> groups = content.groups();

        // Categories appear in client order, each in one contiguous run
        List<CreativeItemCategory> order = groups.stream().map(CreativeItemGroup::getCategory).distinct().toList();
        assertEquals(List.of(CreativeItemCategory.CONSTRUCTION, CreativeItemCategory.NATURE, CreativeItemCategory.EQUIPMENT, CreativeItemCategory.ITEMS), order);
        for (int i = 1; i < groups.size(); i++) {
            assertTrue(groups.get(i - 1).getCategory().ordinal() <= groups.get(i).getCategory().ordinal());
        }

        // No empty groups, items sorted by group, net ids sequential from 1
        int[] counts = new int[groups.size()];
        int previousGroup = 0;
        for (int i = 0; i < content.items().size(); i++) {
            CreativeItemData item = content.items().get(i);
            assertEquals(i + 1, item.getNetId());
            assertTrue(item.getGroupId() >= previousGroup);
            previousGroup = item.getGroupId();
            counts[item.getGroupId()]++;
        }
        for (int i = 0; i < counts.length; i++) {
            assertTrue(counts[i] > 0, "empty group " + groups.get(i));
            CreativeItemGroup group = groups.get(i);
            // Unnamed groups are runs of loose stacks, and use an air icon
            assertEquals(group.getName().isEmpty(), group.getIcon() == ItemData.AIR, group.toString());
        }
    }

    @Test
    void creativeEntriesMapBackToTheirStacks() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        CreativeContent content = palette.creative();
        CreativeEntry entry = content.entry(1);
        assertNotNull(entry);
        assertEquals(content.items().getFirst().getItem().getDefinition(), entry.item().getDefinition());
        assertEquals(CreativeCategory.CONSTRUCTION, entry.category());
        assertNull(content.entry(0));
        assertNull(content.entry(content.size() + 1));
    }

    @Test
    void blockItemsKnowTheirBlock() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        RegisteredItem planks = palette.items().getDefinition("minecraft:oak_planks");
        RegisteredItem apple = palette.items().getDefinition("minecraft:apple");
        assertNotNull(planks);
        assertNotNull(apple);
        assertTrue(palette.items().isBlockItem(planks));
        assertEquals("minecraft:oak_planks", palette.items().defaultBlock(planks).identifier());
        assertFalse(palette.items().isBlockItem(apple));

        // The client sends junk block definitions for non block items (runtime id 0 is a real block on Bedrock);
        // they must not influence matching
        ItemData appleWithJunk = ItemData.builder().definition(apple).count(1).blockDefinition(palette.blocks().byRuntimeId(0)).build();
        assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:apple")), Ids.match(palette, appleWithJunk));
    }

    @Test
    void exactBlockLookupIgnoresDumpOnlyKeys() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        NbtMap withExtras = BlockStates.of("minecraft:air").toBuilder().putInt("version", 1).putInt("network_id", 5).build();
        assertSame(palette.blocks().air(), palette.blocks().exact(withExtras));
    }

    // The version files hold no item components, so items are compared by identifier, id, version and kind
    private static List<String> identifiers(VanillaData data) {
        return data.items().stream().map(item -> item.identifier() + " " + item.runtimeId() + " " + item.version() + " " + item.componentBased()).toList();
    }

    @Test
    void dataCodecRoundTrips() throws Exception {
        // The item components are not in the version files: the property store has them (PropertyStoreTest)
        VanillaData data = PALETTER.vanillaData(PALETTER.latest());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PaletteDataCodec.write(data, out);
        VanillaData read = PaletteDataCodec.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(data.version(), read.version());
        assertEquals(data.blockStates(), read.blockStates());
        assertEquals(identifiers(data), identifiers(read));
        assertEquals(data.creative(), read.creative());
        assertEquals(data.itemTags(), read.itemTags());
        assertEquals(data.fallbacks(), read.fallbacks());
    }

    @Test
    void experimentMarksRoundTripAndAreOptional() throws Exception {
        VanillaData data = PALETTER.vanillaData(PALETTER.latest());
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        PaletteDataCodec.write(data.withExperiments(java.util.Map.of()), plain);
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        PaletteDataCodec.write(PaletteDataCodec.read(new ByteArrayInputStream(plain.toByteArray())), again);
        // Without marks nothing is written: data captured without experiments keeps its bytes
        assertEquals(java.util.Arrays.toString(plain.toByteArray()), java.util.Arrays.toString(again.toByteArray()));

        ExperimentContent drop = new ExperimentContent(List.of("minecraft:ice_ball", "minecraft:icicle"), List.of("minecraft:icicle"),
            List.of("minecraft:arrow:48", "minecraft:ice_ball"));
        ByteArrayOutputStream marked = new ByteArrayOutputStream();
        PaletteDataCodec.write(data.withExperiments(java.util.Map.of("y_2026_drop_4", drop)), marked);
        VanillaData read = PaletteDataCodec.read(new ByteArrayInputStream(marked.toByteArray()));
        assertEquals(java.util.Map.of("y_2026_drop_4", drop), read.experiments());
        assertEquals(List.of("y_2026_drop_4"), read.experimentsOfBlock("minecraft:icicle"));
        assertEquals(List.of("y_2026_drop_4"), read.experimentsOfItem("minecraft:ice_ball"));
        assertEquals(List.of(), read.experimentsOfItem("minecraft:stone"));
        assertEquals(identifiers(data), identifiers(read));
    }

    @Test
    void creativeCategoryMappingsAreConsistent() {
        // The protocol enum starts with ALL, so its ordinals are offset by one from the creative tabs.
        // Using ordinal() of a 0-based category enum to index it is an easy, silent off by one
        assertEquals(CreativeItemCategory.CONSTRUCTION, CreativeCategory.CONSTRUCTION.protocol());
        assertEquals(CreativeItemCategory.ITEMS, CreativeCategory.ITEMS.protocol());
        for (CreativeCategory category : CreativeCategory.values()) {
            assertEquals(category, CreativeCategory.fromProtocol(category.protocol()));
            assertEquals(category, CreativeCategory.fromBedrockName(category.bedrockName()));
        }
    }

    private static boolean hasNote(Palette palette) {
        return palette.report().entries().stream().anyMatch(entry -> entry.level() == PaletteReport.Level.INFO
            && entry.message().contains("newer than the newest bundled data"));
    }

    @Test
    void aProtocolNewerThanTheDataIsReported() {
        Paletter paletter = Paletter.bundled();
        int latest = paletter.latest().protocolVersion();
        assertTrue(paletter.palette(latest).report().warnings().isEmpty());
        Palette newer = paletter.palette(latest + 1000);
        assertEquals(paletter.latest(), newer.version());
        assertTrue(newer.report().warnings().isEmpty());
        assertTrue(hasNote(newer));
        assertSame(newer, paletter.palette(latest + 1000));
        assertTrue(hasNote(newer.extend(PaletteLayer.builder("session").build())));
        assertFalse(hasNote(paletter.palette(latest)));
    }
}
