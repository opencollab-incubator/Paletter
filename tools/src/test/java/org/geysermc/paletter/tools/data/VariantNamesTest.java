package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** How BDS's item variants of the Script API dump become constant names, and how the bundled file keeps them. */
class VariantNamesTest {
    private static BdsDump.Variant variant(String key, String potion, String delivery) {
        return new BdsDump.Variant(key, potion, delivery);
    }

    @Test
    void potionsAreNamedByTheirEffect() {
        assertEquals("POTION_LONG_NIGHTVISION", VariantNames.name("minecraft:potion",
            variant("%potion.nightVision.name", "minecraft:long_nightvision", "Consume")));
        assertEquals("SPLASH_POTION_STRONG_HARMING", VariantNames.name("minecraft:splash_potion",
            variant("%potion.harm.splash.name", "minecraft:strong_harming", "ThrownSplash")));
        assertEquals("LINGERING_POTION_WATER", VariantNames.name("minecraft:lingering_potion",
            variant("%potion.water.linger.name", "minecraft:water", "ThrownLingering")));
        // The delivery of the potion component must be the one of the item
        assertThrows(IllegalStateException.class, () -> VariantNames.name("minecraft:potion",
            variant("%potion.water.name", "minecraft:water", "ThrownSplash")));
    }

    @Test
    void arrowsAreNamedByTheEffectOfTheirLootTable() {
        assertEquals("ARROW_LONG_POISON", VariantNames.name("minecraft:arrow", variant("tipped_arrow.effect.poison", "minecraft:long_poison", null)));
        // Several effects give the plain arrow, so it has none
        assertNull(VariantNames.name("minecraft:arrow", variant("item.arrow.name", null, null)));
    }

    @Test
    void colouredItemsAreNamedByTheirLocalizationKey() {
        assertEquals("BED_SILVER", VariantNames.name("minecraft:bed", variant("item.bed.silver.name", null, null)));
        assertEquals("BED_LIGHT_BLUE", VariantNames.name("minecraft:bed", variant("item.bed.lightBlue.name", null, null)));
        assertEquals("BANNER_BLACK", VariantNames.name("minecraft:banner", variant("item.banner.black.name", null, null)));
        // A key of another item names nothing
        assertNull(VariantNames.name("minecraft:bed", variant("item.banner.black.name", null, null)));
    }

    @Test
    void theDumpHasTheVariants() {
        BdsDump dump = BdsDump.parse("""
            {"format": 3, "item_tags": {}, "items": {}, "block_defaults": {}, "item_variants": {
              "minecraft:potion": {"6": {"key": "%potion.nightVision.name", "potion": "minecraft:long_nightvision", "delivery": "Consume"}},
              "minecraft:bed": {"8": {"key": "item.bed.silver.name"}}}}
            """, "1.0.0.0");
        assertEquals(new BdsDump.Variant("item.bed.silver.name", null, null), dump.itemVariants().get("minecraft:bed").get(8));
        assertEquals("minecraft:long_nightvision", dump.itemVariants().get("minecraft:potion").get(6).potion());
        // A dump from before format 3 has none
        assertEquals(Map.of(), BdsDump.parse("{\"item_tags\": {}, \"items\": {}, \"block_defaults\": {}}", "1.0.0.0").itemVariants());
    }

    @Test
    void theFileKeepsEveryVersion(@TempDir Path directory) throws IOException {
        VariantNames.update(directory, "26.60", List.of(new VariantNames.Entry("minecraft:potion", 6, "POTION_LONG_NIGHTVISION"),
            new VariantNames.Entry("minecraft:bed", 0, "BED_WHITE")));
        VariantNames.update(directory, "26.70", List.of(new VariantNames.Entry("minecraft:potion", 49, "POTION_FREEZING")));
        VariantNames.update(directory, "26.60", List.of(new VariantNames.Entry("minecraft:bed", 0, "BED_WHITE")));
        assertEquals(Map.of("26.60", List.of(new VariantNames.Entry("minecraft:bed", 0, "BED_WHITE")),
            "26.70", List.of(new VariantNames.Entry("minecraft:potion", 49, "POTION_FREEZING"))), VariantNames.read(directory));
        assertEquals(true, Files.readString(directory.resolve(VariantNames.FILE)).startsWith("# "));

        VariantNames.remove(directory, "26.70");
        assertEquals(List.of("26.60"), List.copyOf(VariantNames.read(directory).keySet()));
    }
}
