package org.geysermc.paletter.recipes;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItems;
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
}
