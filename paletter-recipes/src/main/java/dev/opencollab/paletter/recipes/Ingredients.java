package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Lookup;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.ItemKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Helpers to build {@link Ingredient}s for a palette.
 *
 * @since 1.0.0
 */
public final class Ingredients {
    /**
     * The tag members and item variants of each palette asked about, built on the first call for it. Weak keys: a palette
     * that is no longer used drops its table (the table holds item types and names, never the palette).
     */
    private static final Map<Palette, TagTable> TABLES = Collections.synchronizedMap(new WeakHashMap<>());

    private Ingredients() {
    }

    /**
     * Finds the item tag of a palette that stands for exactly these items, e.g. to send a Java ingredient as a Bedrock tag
     * instead of one entry per item. Tags are the palette's {@link Palette#itemTags()}, layers included.
     * <p>
     * A tag lists item types, and a listed type stands for every variant (aux value) of it. So a {@link BedrockItemType} in
     * {@code items} means all its variants, and a {@link BedrockItemVariant} only that variant. A type is covered if
     * {@code items} holds the type itself or every variant the type has in this palette; a type with only some of its variants
     * is not covered, and then no tag matches, as a tag would accept the other variants too.
     * <p>
     * A tag matches if its members and the covered types are the same set, after leaving out the items the palette does not have
     * (an older version lacks newer items, and none of its tags can have them) and the types {@code unobtainable} accepts (items
     * a player can never hold, which a tag may or may not list). A set with no items left matches no tag. If several tags
     * match, the one with the fewest members (counting those left out) wins, then the first by name.
     * <p>
     * The tags and item variants of a palette are indexed on the first call for it; later calls only look at the tags that
     * have one of the items.
     *
     * @param palette the palette whose tags to search
     * @param items the items the ingredient accepts: types for all their variants, variants for just those
     * @param unobtainable whether an item type is left out of the comparison, on both sides
     * @return the tag as an ingredient with a count of 1, or null if no tag matches
     */
    public static Ingredient.@Nullable Tag tagOf(@NonNull Palette palette, @NonNull Set<? extends ItemKey> items, @NonNull Predicate<BedrockItemType> unobtainable) {
        Set<BedrockItemType> wanted = new HashSet<>();
        Map<BedrockItemType, Set<Integer>> partial = null;
        for (ItemKey item : items) {
            BedrockItemType type = item.type();
            if (palette.itemDefinition(type) == null || unobtainable.test(type)) {
                continue;
            }
            if (item instanceof BedrockItemVariant variant) {
                if (partial == null) {
                    partial = new HashMap<>();
                }
                partial.computeIfAbsent(type, key -> new HashSet<>()).add(variant.aux());
            } else {
                wanted.add(type);
            }
        }
        TagTable table = TABLES.computeIfAbsent(palette, TagTable::of);
        if (partial != null) {
            for (Map.Entry<BedrockItemType, Set<Integer>> entry : partial.entrySet()) {
                if (wanted.contains(entry.getKey())) {
                    continue;
                }
                // Only covered if every variant the type has in this palette is there
                if (!entry.getValue().containsAll(table.variants.getOrDefault(entry.getKey(), Set.of()))) {
                    return null;
                }
                wanted.add(entry.getKey());
            }
        }
        if (wanted.isEmpty()) {
            return null;
        }
        // A matching tag has every wanted type, so the tags of any one of them are the candidates
        String best = null;
        int bestSize = 0;
        for (Tag tag : table.byMember.getOrDefault(wanted.iterator().next(), List.of())) {
            if (tag.matches(wanted, unobtainable)
                && (best == null || tag.size < bestSize || tag.size == bestSize && tag.name.compareTo(best) < 0)) {
                best = tag.name;
                bestSize = tag.size;
            }
        }
        return best == null ? null : new Ingredient.Tag(best, 1);
    }

    /**
     * @param name the tag
     * @param size the number of identifiers the tag lists
     * @param members the types of the listed identifiers
     */
    private record Tag(String name, int size, Set<BedrockItemType> members) {
        /** Whether the members, without the unobtainable ones, are exactly {@code wanted} (which has no unobtainable type). */
        boolean matches(Set<BedrockItemType> wanted, Predicate<BedrockItemType> unobtainable) {
            int count = 0;
            for (BedrockItemType member : members) {
                if (unobtainable.test(member)) {
                    continue;
                }
                if (!wanted.contains(member)) {
                    return false;
                }
                count++;
            }
            return count == wanted.size();
        }
    }

    /**
     * @param byMember the tags that list each type
     * @param variants the auxes each type has in the palette (of the variants the palette knows and finds exactly)
     */
    private record TagTable(Map<BedrockItemType, List<Tag>> byMember, Map<BedrockItemType, Set<Integer>> variants) {
        static TagTable of(Palette palette) {
            Map<BedrockItemType, List<Tag>> byMember = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : palette.itemTags().entrySet()) {
                Set<BedrockItemType> members = new HashSet<>();
                for (String identifier : entry.getValue()) {
                    members.add(BedrockItemType.fromIdentifier(identifier));
                }
                Tag tag = new Tag(entry.getKey(), entry.getValue().size(), Set.copyOf(members));
                for (BedrockItemType member : members) {
                    byMember.computeIfAbsent(member, key -> new ArrayList<>(2)).add(tag);
                }
            }
            Map<BedrockItemType, Set<Integer>> variants = new HashMap<>();
            for (BedrockItemVariant variant : palette.itemVariants()) {
                if (palette.itemVariant(variant.type().identifier(), variant.aux(), Lookup.EXACT) != null) {
                    variants.computeIfAbsent(variant.type(), key -> new HashSet<>()).add(variant.aux());
                }
            }
            return new TagTable(byMember, variants);
        }
    }
}
