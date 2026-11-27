package dev.opencollab.paletter.tools.codegen.constants;

import dev.opencollab.paletter.tools.codegen.constants.ContentConstants.Constant;
import dev.opencollab.paletter.tools.codegen.constants.VariantConstants.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The getter classes and {@code BedrockPotionTypes} that {@link ConstantsSource} writes for the named variants. */
class NamedVariantsSourceTest {
    private static Variant variant(String name, String identifier, int aux, String potion) {
        return new Variant(new Constant(name, identifier, "1.0.0", null, null, null, false, null, null), aux, potion);
    }

    @Test
    void gettersAreTheNameWithoutTheItem() {
        assertEquals("lightBlue", ConstantsSource.getter(variant("BED_LIGHT_BLUE", "minecraft:bed", 3, null).constant()));
        assertEquals("silver", ConstantsSource.getter(variant("BED_SILVER", "minecraft:bed", 8, null).constant()));
        assertEquals("BedItemType", ConstantsSource.itemTypeClass("minecraft:bed"));
        assertEquals("LingeringPotionItemType", ConstantsSource.itemTypeClass("minecraft:lingering_potion"));
    }

    @Test
    void aGetterThatIsTakenFails() {
        assertThrows(IllegalStateException.class, () -> ConstantsSource.getter(variant("BED_BASE", "minecraft:bed", 1, null).constant()));
        assertThrows(IllegalStateException.class, () -> ConstantsSource.getter(variant("BED_DEFAULT", "minecraft:bed", 1, null).constant()));
        assertThrows(IllegalStateException.class, () -> ConstantsSource.getter(variant("BANNER_RED", "minecraft:bed", 1, null).constant()));
    }

    @Test
    void itemsArePotionLikeOrHaveGetters() {
        Map<String, com.palantir.javapoet.ClassName> types = ConstantsSource.variantTypes(List.of(
            variant("POTION_HEALING", "minecraft:potion", 21, "minecraft:healing"),
            variant("BED_RED", "minecraft:bed", 14, null)));
        assertEquals("PotionItemType", types.get("minecraft:potion").simpleName());
        assertEquals("BedItemType", types.get("minecraft:bed").simpleName());
        assertThrows(IllegalStateException.class, () -> ConstantsSource.variantTypes(List.of(
            variant("POTION_HEALING", "minecraft:potion", 21, "minecraft:healing"),
            variant("POTION_ODD", "minecraft:potion", 99, null))));
    }

    @Test
    void writesGettersAndPotionTypes() {
        String bed = ConstantsSource.itemType("minecraft:bed", "BED", List.of(variant("BED_LIGHT_BLUE", "minecraft:bed", 3, null)), "26.60");
        assertTrue(bed.contains("public final class BedItemType extends BedrockItemType"), bed);
        assertTrue(bed.contains("this.lightBlue = VanillaTypes.variant(this, 3);"), bed);
        assertTrue(bed.contains("public BedrockItemVariant lightBlue()"), bed);

        String potions = ConstantsSource.potionTypes(List.of(
            variant("POTION_HEALING", "minecraft:potion", 21, "minecraft:healing"),
            variant("ARROW_HEALING", "minecraft:arrow", 22, "minecraft:healing")), Map.of("minecraft:potion", "POTION", "minecraft:arrow", "ARROW"), "26.60");
        assertTrue(potions.contains("public static final BedrockPotionType HEALING = new BedrockPotionType(\"minecraft:healing\")"), potions);
        assertTrue(potions.contains(".add(BedrockItems.ARROW, 22)"), potions);
        assertTrue(potions.contains(".add(BedrockItems.POTION, 21)"), potions);
    }
}
