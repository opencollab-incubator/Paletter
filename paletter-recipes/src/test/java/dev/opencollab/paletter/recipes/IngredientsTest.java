package dev.opencollab.paletter.recipes;

import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.Tags;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
import dev.opencollab.paletter.vanilla.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IngredientsTest {
    private static final List<DataVersion> VERSIONS = VanillaRecipes.versions();
    private static final Palette NEWEST = Paletter.builder().build().palette(VERSIONS.getLast().protocolVersion());

    private static Set<BedrockItemType> members(Palette palette, String tag) {
        Set<BedrockItemType> members = new HashSet<>();
        palette.itemTags().get(tag).forEach(identifier -> members.add(BedrockItemType.fromIdentifier(identifier)));
        return members;
    }

    /** The first tag by name with at least three members. */
    private static String someTag(Palette palette) {
        return palette.itemTags().entrySet().stream().filter(entry -> entry.getValue().size() >= 3).map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
    }

    @Test
    void anExactSetFindsATagWithThoseMembers() {
        String tag = someTag(NEWEST);
        Ingredient.Tag found = Ingredients.tagOf(NEWEST, members(NEWEST, tag), item -> false);
        assertNotNull(found);
        assertEquals(members(NEWEST, tag), members(NEWEST, found.tag()));
        assertEquals(1, found.count());
        assertEquals(found, Ingredients.tagOf(NEWEST, members(NEWEST, tag), item -> false));
    }

    @Test
    void unobtainableItemsAreIgnoredOnBothSides() {
        Set<BedrockItemType> items = members(NEWEST, someTag(NEWEST));
        BedrockItemType left = items.iterator().next();
        items.remove(left);
        assertNull(Ingredients.tagOf(NEWEST, items, item -> false));
        Ingredient.Tag found = Ingredients.tagOf(NEWEST, items, left::equals);
        assertNotNull(found);
        Set<BedrockItemType> foundMembers = members(NEWEST, found.tag());
        foundMembers.remove(left);
        assertEquals(items, foundMembers);
    }

    /** The first tag by name that has the banner type (which has many variants) as a member. */
    private static String bannerTag() {
        return NEWEST.itemTags().entrySet().stream().filter(entry -> entry.getValue().contains("minecraft:banner")).map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
    }

    @Test
    void someVariantsOfATypeMatchNoTagOfThatType() {
        Set<BedrockItemVariant> some = Set.of(BedrockItems.BANNER.variants().get(0), BedrockItems.BANNER.variants().get(1));
        assertNull(Ingredients.tagOf(NEWEST, some, item -> false));
        Set<ItemKey> mixed = new HashSet<>(members(NEWEST, bannerTag()));
        mixed.remove(BedrockItems.BANNER);
        mixed.addAll(some);
        assertNull(Ingredients.tagOf(NEWEST, mixed, item -> false));
    }

    @Test
    void allVariantsOfATypeOrTheTypeMatchLikeTheType() {
        String tag = bannerTag();
        Set<BedrockItemType> expected = members(NEWEST, tag);
        Set<ItemKey> allVariants = new HashSet<>(expected);
        allVariants.remove(BedrockItems.BANNER);
        allVariants.addAll(BedrockItems.BANNER.variants());
        Ingredient.Tag byVariants = Ingredients.tagOf(NEWEST, allVariants, item -> false);
        assertNotNull(byVariants);
        assertEquals(Ingredients.tagOf(NEWEST, expected, item -> false), byVariants);
        assertEquals(expected, members(NEWEST, byVariants.tag()));
    }

    @Test
    void fullTypesMixWithTypesGivenAsTheirVariants() {
        String tag = someTag(NEWEST);
        Set<ItemKey> items = new HashSet<>(members(NEWEST, tag));
        assertEquals(Ingredients.tagOf(NEWEST, members(NEWEST, tag), item -> false), Ingredients.tagOf(NEWEST, items, item -> false));
    }

    @Test
    void noTagMatchesGivesNull() {
        assertNull(Ingredients.tagOf(NEWEST, Set.of(BedrockItems.STICK, BedrockItems.COAL), item -> false));
        assertNull(Ingredients.tagOf(NEWEST, Set.of(), item -> false));
        assertNull(Ingredients.tagOf(NEWEST, Set.of(BedrockItems.STICK), item -> true));
    }

    @Test
    void anOlderPaletteIgnoresItemsItDoesNotHave() {
        Palette oldest = Paletter.builder().build().palette(VERSIONS.getFirst().protocolVersion());
        for (String tag : NEWEST.itemTags().keySet()) {
            Set<BedrockItemType> newer = members(NEWEST, tag);
            Set<BedrockItemType> lacking = new HashSet<>();
            newer.stream().filter(item -> oldest.itemDefinition(item) == null).forEach(lacking::add);
            if (!lacking.isEmpty() && lacking.size() < newer.size() && oldest.itemTags().containsKey(tag)) {
                Ingredient.Tag found = Ingredients.tagOf(oldest, newer, item -> false);
                assertNotNull(found, tag);
                newer.removeAll(lacking);
                assertEquals(newer, members(oldest, found.tag()), tag);
                return;
            }
        }
        throw new AssertionError("No tag of the newest version has an item the oldest lacks");
    }

    private static CustomItem item(String name, String... tags) {
        return CustomItem.builder("example:" + name).component(Icon.of(name)).component(Tags.builder().tags(List.of(tags)).build()).menuCategory(CreativeCategory.ITEMS).build();
    }

    @Test
    void ofSeveralMatchingTagsTheOneWithFewestMembersWinsThenTheName() {
        // b, c and d are {x, y}; a is {x, y, z}
        Palette palette = NEWEST.extend(PaletteLayer.builder("tags")
            .item(item("x", "example:a", "example:b", "example:c", "example:d"))
            .item(item("y", "example:a", "example:b", "example:c", "example:d"))
            .item(item("z", "example:a")).build());
        BedrockItemType x = BedrockItemType.fromIdentifier("example:x");
        BedrockItemType y = BedrockItemType.fromIdentifier("example:y");
        BedrockItemType z = BedrockItemType.fromIdentifier("example:z");
        assertEquals("example:b", Ingredients.tagOf(palette, Set.of(x, y), item -> false).tag(), "tie on 2 members: the first name");
        // With z unobtainable a matches too, and sorts first by name, but has more members
        assertEquals("example:b", Ingredients.tagOf(palette, Set.of(x, y), z::equals).tag());
    }
}
