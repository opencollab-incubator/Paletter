package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.creative.RawNode;
import dev.opencollab.paletter.internal.creative.RawStack;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VersionFallbacks;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.BuildNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How BDS's item variants of the Script API dump become constant names, and how the names file keeps them. */
class VariantNamesTest {
    private static final Path ROOT = Path.of("..");

    private static BdsDump.Variant variant(String key, String potion) {
        return new BdsDump.Variant(key, potion);
    }

    /** A dump of a potion (aux 5 and 6), a bed (aux 8) and the plain arrow. */
    private static BdsDump dump() {
        return BdsDump.parse("""
            {"format": 3, "item_tags": {}, "items": {}, "block_defaults": {}, "item_variants": {
              "minecraft:potion": {"5": {"key": "%potion.nightVision.name", "potion": "minecraft:nightvision"},
                                   "6": {"key": "%potion.nightVision.name", "potion": "minecraft:long_nightvision"}},
              "minecraft:arrow": {"0": {"key": "item.arrow.name"}},
              "minecraft:bed": {"8": {"key": "item.bed.silver.name"}}}}
            """, "1.0.0.0");
    }

    private static VanillaData creative(String... stacks) {
        Map<CreativeCategory, List<RawNode>> creative = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            creative.put(category, List.of());
        }
        List<RawNode> nodes = new ArrayList<>();
        for (String stack : stacks) {
            int at = stack.indexOf('@');
            nodes.add(RawStack.of(stack.substring(0, at)).withDamage(Integer.parseInt(stack.substring(at + 1))));
        }
        creative.put(CreativeCategory.ITEMS, nodes);
        return new VanillaData(new DataVersion("1.0", 100), List.of(), List.of(), List.of(), RawLayout.of(creative), Map.of(),
            VersionFallbacks.NONE, Map.of());
    }

    @Test
    void potionsAreNamedByTheirEffect() {
        assertEquals("POTION_LONG_NIGHTVISION", VariantNames.name("minecraft:potion", variant("%potion.nightVision.name", "minecraft:long_nightvision")));
        assertEquals("SPLASH_POTION_STRONG_HARMING", VariantNames.name("minecraft:splash_potion", variant("%potion.harm.splash.name", "minecraft:strong_harming")));
        assertEquals("LINGERING_POTION_WATER", VariantNames.name("minecraft:lingering_potion", variant("%potion.water.linger.name", "minecraft:water")));
    }

    @Test
    void arrowsAreNamedByTheEffectOfTheirLootTable() {
        assertEquals("ARROW_LONG_POISON", VariantNames.name("minecraft:arrow", variant("tipped_arrow.effect.poison", "minecraft:long_poison")));
        // Several effects give the plain arrow, so it has none
        assertNull(VariantNames.name("minecraft:arrow", variant("item.arrow.name", null)));
    }

    @Test
    void colouredItemsAreNamedByTheirLocalizationKey() {
        assertEquals("BED_SILVER", VariantNames.name("minecraft:bed", variant("item.bed.silver.name", null)));
        assertEquals("BED_LIGHT_BLUE", VariantNames.name("minecraft:bed", variant("item.bed.lightBlue.name", null)));
        assertEquals("BANNER_BLACK", VariantNames.name("minecraft:banner", variant("item.banner.black.name", null)));
        // A key of another item names nothing
        assertNull(VariantNames.name("minecraft:bed", variant("item.banner.black.name", null)));
    }

    @Test
    void theDumpHasTheVariants() {
        assertEquals(new BdsDump.Variant("item.bed.silver.name", null), dump().itemVariants().get("minecraft:bed").get(8));
        assertEquals("minecraft:long_nightvision", dump().itemVariants().get("minecraft:potion").get(6).potion());
        // A dump from before format 3 has none
        assertEquals(Map.of(), BdsDump.parse("{\"item_tags\": {}, \"items\": {}, \"block_defaults\": {}}", "1.0.0.0").itemVariants());
    }

    @Test
    void onlyTheVariantsOfTheCreativeInventoryAreNamed() {
        // Aux 6 is not in the creative inventory of the version, so it has no constant there
        assertEquals(List.of(new VariantNames.Entry("minecraft:potion", 5, "POTION_NIGHTVISION"), new VariantNames.Entry("minecraft:bed", 8, "BED_SILVER")),
            sorted(VariantNames.of(dump(), creative("minecraft:potion@5", "minecraft:arrow@0", "minecraft:bed@8", "minecraft:apple@0"))));
    }

    private static List<VariantNames.Entry> sorted(List<VariantNames.Entry> entries) {
        return entries.stream().sorted(java.util.Comparator.comparing(VariantNames.Entry::identifier).reversed()).toList();
    }

    @Test
    void twoVariantsWithOneNameFail() {
        BdsDump dump = BdsDump.parse("""
            {"item_tags": {}, "items": {}, "block_defaults": {}, "item_variants": {
              "minecraft:bed": {"1": {"key": "item.bed.red.name"}, "2": {"key": "item.bed.red.name"}}}}
            """, "1.0.0.0");
        assertEquals("BDS names minecraft:bed@1 and minecraft:bed@2 both BED_RED", assertThrows(IllegalStateException.class,
            () -> VariantNames.of(dump, creative("minecraft:bed@1", "minecraft:bed@2"))).getMessage());
    }

    @Test
    void aCreativeVariantWithoutANameFailsInsteadOfDroppingTheConstant() {
        // Aux 7 of the potion is in the creative inventory, but the dump has no such variant: it is incomplete
        assertEquals("The dump names no creative variant [minecraft:potion@7] of the items it covers: it is incomplete",
            assertThrows(IllegalStateException.class, () -> VariantNames.of(dump(), creative("minecraft:potion@5", "minecraft:potion@7"))).getMessage());
        // Items the dump does not cover are none of its business
        assertEquals(1, VariantNames.of(dump(), creative("minecraft:potion@5", "minecraft:banner@0", "minecraft:arrow@0")).size());
    }

    @Test
    void aDumpWithoutVariantsLeavesTheNamesAsTheyAre() {
        Map<String, List<VariantNames.Entry>> all = new TreeMap<>();
        all.put("1.0", List.of(new VariantNames.Entry("minecraft:bed", 8, "BED_SILVER")));
        BdsDump older = BdsDump.parse("{\"item_tags\": {}, \"items\": {}, \"block_defaults\": {}}", "1.0.0.0");
        VariantNames.update(all, "1.0", older, creative("minecraft:bed@8"));
        assertEquals(List.of(new VariantNames.Entry("minecraft:bed", 8, "BED_SILVER")), all.get("1.0"));

        VariantNames.update(all, "1.0", dump(), creative("minecraft:potion@5"));
        assertEquals(List.of(new VariantNames.Entry("minecraft:potion", 5, "POTION_NIGHTVISION")), all.get("1.0"));
    }

    private static final List<String> VERSIONS = List.of("26.50", "26.60", "26.70");

    @Test
    void theFileKeepsEachVariantOnceWithItsRange(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("variant_names.txt");
        VariantNames.Entry white = new VariantNames.Entry("minecraft:bed", 0, "BED_WHITE");
        VariantNames.Entry nightvision = new VariantNames.Entry("minecraft:potion", 6, "POTION_LONG_NIGHTVISION");
        VariantNames.Entry freezing = new VariantNames.Entry("minecraft:potion", 49, "POTION_FREEZING");
        VariantNames.Entry gap = new VariantNames.Entry("minecraft:potion", 50, "POTION_GAP");
        Map<String, List<VariantNames.Entry>> all = new TreeMap<>();
        all.put("26.50", List.of(gap, white));
        all.put("26.60", List.of(white, nightvision));
        all.put("26.70", List.of(freezing, white, nightvision, gap));
        String text = VariantNames.render(all, VERSIONS);
        Files.writeString(file, text);
        // One line per variant: through the newest version is open, the others close; a gap splits a range
        assertEquals(List.of(
            "26.50.. minecraft:bed 0 BED_WHITE",
            "26.60.. minecraft:potion 6 POTION_LONG_NIGHTVISION",
            "26.70.. minecraft:potion 49 POTION_FREEZING",
            "26.50..26.50 minecraft:potion 50 POTION_GAP",
            "26.70.. minecraft:potion 50 POTION_GAP"), text.lines().filter(l -> !l.startsWith("#")).toList());
        assertTrue(text.startsWith("# "));
        Map<String, List<VariantNames.Entry>> read = VariantNames.read(file, VERSIONS);
        assertEquals(all.keySet(), read.keySet());
        all.forEach((version, entries) -> assertEquals(new java.util.HashSet<>(entries), new java.util.HashSet<>(read.get(version)), version));
        assertEquals(text, VariantNames.render(VariantNames.read(file, VERSIONS), VERSIONS));
        assertEquals(Map.of(), VariantNames.read(directory.resolve("missing.txt"), VERSIONS));
    }

    @Test
    void aNewVersionThatKeepsAVariantChangesNoLine() {
        VariantNames.Entry white = new VariantNames.Entry("minecraft:bed", 0, "BED_WHITE");
        Map<String, List<VariantNames.Entry>> all = new TreeMap<>();
        all.put("26.60", List.of(white));
        String before = VariantNames.render(all, List.of("26.60"));
        all.put("26.70", List.of(white));
        assertEquals(before, VariantNames.render(all, List.of("26.60", "26.70")));
        // A new version without it closes the range
        all.remove("26.70");
        assertTrue(VariantNames.render(all, List.of("26.60", "26.70")).contains("26.60..26.60 minecraft:bed 0 BED_WHITE"));
    }

    @Test
    void rangesOfTheFileAreChecked(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("variant_names.txt");
        Files.writeString(file, "26.70..26.60 minecraft:bed 0 BED_WHITE\n");
        assertTrue(assertThrows(IllegalStateException.class, () -> VariantNames.read(file, VERSIONS)).getMessage().contains("ends before it starts"));
        Files.writeString(file, "26.99.. minecraft:bed 0 BED_WHITE\n");
        assertTrue(assertThrows(IllegalStateException.class, () -> VariantNames.read(file, VERSIONS)).getMessage().contains("26.99"));
        // The old format, one line per version, is not read
        Files.writeString(file, "26.60 minecraft:bed 0 BED_WHITE\n");
        assertThrows(IllegalStateException.class, () -> VariantNames.read(file, VERSIONS));
        Map<String, List<VariantNames.Entry>> stray = Map.of("26.80", List.of());
        assertThrows(IllegalStateException.class, () -> VariantNames.render(stray, VERSIONS));
    }

    /** The committed names are what the committed dumps and bundled data give: nothing was edited or lost. */
    @Test
    void theCommittedNamesComeFromTheCommittedDumps() throws IOException {
        Map<String, List<VariantNames.Entry>> committed = VariantNames.read(ROOT.resolve(VariantNames.FILE), VariantNames.versions(ROOT.resolve("paletter/src/main/resources/paletter")));
        assertTrue(!committed.isEmpty());
        try (Stream<Path> builds = Files.list(ROOT.resolve("data/bds"))) {
            for (Path build : builds.sorted().toList()) {
                Path dumpFile = build.resolve("dump.json.gz");
                if (!Files.exists(dumpFile)) {
                    continue;
                }
                String text;
                try (InputStream in = new GZIPInputStream(Files.newInputStream(dumpFile))) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                BdsDump dump = BdsDump.parse(text, build.getFileName().toString());
                String version = BuildNumber.parse(build.getFileName().toString()).dataVersion();
                Path data = ROOT.resolve("paletter/src/main/resources/paletter/" + version + ".nbt");
                if (dump.itemVariants().isEmpty() || !Files.exists(data)) {
                    continue;
                }
                VanillaData bundled;
                try (InputStream in = Files.newInputStream(data)) {
                    bundled = PaletteDataCodec.read(in);
                }
                assertEquals(new java.util.HashSet<>(committed.get(version)), new java.util.HashSet<>(VariantNames.of(dump, bundled)), version);
            }
        }
    }
}
