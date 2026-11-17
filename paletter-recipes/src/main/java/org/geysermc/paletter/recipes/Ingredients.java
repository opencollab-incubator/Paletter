package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Helpers to build {@link Ingredient}s for a palette.
 *
 * @since 1.0.0
 */
public final class Ingredients {
    private Ingredients() {
    }

    /**
     * Finds the item tag of a palette that stands for exactly these items, e.g. to send a Java ingredient as a Bedrock tag
     * instead of one entry per item. Tags are the palette's {@link Palette#itemTags()}, layers included.
     * <p>
     * A tag matches if its members and {@code items} are the same set, after leaving out the items the palette does not have
     * (an older version lacks newer items, and none of its tags can have them) and the items {@code unobtainable} accepts (items
     * a player can never hold, which a tag may or may not list). A set with no items left matches no tag. If several tags
     * match, the one with the fewest members wins, then the first by name.
     * <p>
     * This looks through every tag of the palette on each call.
     *
     * @param palette the palette whose tags to search
     * @param items the items the ingredient accepts
     * @param unobtainable whether an item is left out of the comparison, on both sides
     * @return the tag as an ingredient with a count of 1, or null if no tag matches
     */
    public static Ingredient.@Nullable Tag tagOf(@NonNull Palette palette, @NonNull Set<BedrockItemType> items, @NonNull Predicate<BedrockItemType> unobtainable) {
        Set<BedrockItemType> wanted = new HashSet<>();
        for (BedrockItemType item : items) {
            if (palette.itemDefinition(item) != null && !unobtainable.test(item)) {
                wanted.add(item);
            }
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
            if (members.equals(wanted) && (best == null || members.size() < bestSize || members.size() == bestSize && tag.getKey().compareTo(best) < 0)) {
                best = tag.getKey();
                bestSize = members.size();
            }
        }
        return best == null ? null : new Ingredient.Tag(best, 1);
    }
}
