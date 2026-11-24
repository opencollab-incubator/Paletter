package dev.opencollab.paletter.recipes;

import dev.opencollab.paletter.Lookup;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            newer.stream().filter(item -> oldest.itemDefinition(item.identifier()) == null).forEach(lacking::add);
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

    /** The search before the per palette index: every tag's members rebuilt and compared on each call. */
    private static Ingredient.Tag scanned(Palette palette, Set<? extends ItemKey> items, Predicate<BedrockItemType> unobtainable) {
        Set<BedrockItemType> wanted = new HashSet<>();
        Map<BedrockItemType, Set<Integer>> partial = new HashMap<>();
        for (ItemKey item : items) {
            BedrockItemType type = item.type();
            if (palette.itemDefinition(type.identifier()) == null || unobtainable.test(type)) {
                continue;
            }
            if (item instanceof BedrockItemVariant variant) {
                partial.computeIfAbsent(type, key -> new HashSet<>()).add(variant.aux());
            } else {
                wanted.add(type);
            }
        }
        partial.keySet().removeAll(wanted);
        Map<BedrockItemType, Set<Integer>> existing = new HashMap<>();
        for (BedrockItemVariant variant : palette.itemVariants()) {
            if (partial.containsKey(variant.type()) && palette.itemVariant(variant.type().identifier(), variant.aux(), Lookup.EXACT) != null) {
                existing.computeIfAbsent(variant.type(), key -> new HashSet<>()).add(variant.aux());
            }
        }
        for (Map.Entry<BedrockItemType, Set<Integer>> entry : partial.entrySet()) {
            if (!entry.getValue().containsAll(existing.getOrDefault(entry.getKey(), Set.of()))) {
                return null;
            }
            wanted.add(entry.getKey());
        }
        if (wanted.isEmpty()) {
            return null;
        }
        String best = null;
        int bestSize = 0;
        for (Map.Entry<String, List<String>> tag : palette.itemTags().entrySet()) {
            Set<BedrockItemType> members = new HashSet<>();
            for (String identifier : tag.getValue()) {
                BedrockItemType member = BedrockItemType.fromIdentifier(identifier);
                if (!unobtainable.test(member)) {
                    members.add(member);
                }
            }
            if (members.equals(wanted) && (best == null || tag.getValue().size() < bestSize || tag.getValue().size() == bestSize && tag.getKey().compareTo(best) < 0)) {
                best = tag.getKey();
                bestSize = tag.getValue().size();
            }
        }
        return best == null ? null : new Ingredient.Tag(best, 1);
    }

    @Test
    void theIndexFindsWhatAScanOfEveryTagFinds() {
        Palette oldest = Paletter.builder().build().palette(VERSIONS.getFirst().protocolVersion());
        int compared = 0;
        for (Palette palette : List.of(NEWEST, oldest)) {
            for (String tag : palette.itemTags().keySet()) {
                Set<BedrockItemType> all = members(palette, tag);
                BedrockItemType first = all.iterator().next();
                Set<BedrockItemType> lessOne = new HashSet<>(all);
                lessOne.remove(first);
                Set<ItemKey> asVariants = new HashSet<>(lessOne);
                asVariants.addAll(first.variants());
                Set<ItemKey> oneVariant = new HashSet<>(lessOne);
                oneVariant.add(first.variants().getFirst());
                for (Set<? extends ItemKey> items : List.of(all, lessOne, asVariants, oneVariant)) {
                    for (Predicate<BedrockItemType> unobtainable : List.<Predicate<BedrockItemType>>of(item -> false, first::equals)) {
                        assertEquals(scanned(palette, items, unobtainable), Ingredients.tagOf(palette, items, unobtainable), tag);
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 100, "compared " + compared);
        // A rough timing, printed for docs/benchmarks.md (no JMH in this module): the last of three rounds over all tags of the newest palette
        for (int round = 0; round < 3; round++) {
            long scan = 0;
            long index = 0;
            int calls = 0;
            for (String tag : NEWEST.itemTags().keySet()) {
                Set<BedrockItemType> all = members(NEWEST, tag);
                long start = System.nanoTime();
                scanned(NEWEST, all, item -> false);
                long middle = System.nanoTime();
                Ingredients.tagOf(NEWEST, all, item -> false);
                index += System.nanoTime() - middle;
                scan += middle - start;
                calls++;
            }
            if (round == 2) {
                System.out.printf("MEASURE tagOf per call: scan of every tag %.1f us, index %.1f us (%d calls)%n", scan / 1e3 / calls, index / 1e3 / calls, calls);
            }
        }
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
