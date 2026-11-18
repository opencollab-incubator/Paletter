package dev.opencollab.paletter;

import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeEditor;
import dev.opencollab.paletter.creative.CreativeEntry;
import dev.opencollab.paletter.creative.CreativeGroup;
import dev.opencollab.paletter.creative.CreativeLayout;
import dev.opencollab.paletter.creative.CreativeItem;
import dev.opencollab.paletter.creative.CreativeNode;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.creative.Placements;
import dev.opencollab.paletter.creative.ItemMatcher;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeEditorTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final BedrockItemStack A = stack("test:a");
    private static final BedrockItemStack B = stack("test:b");
    private static final BedrockItemStack C = stack("test:c");
    private static final BedrockItemStack LOOSE = stack("test:loose");
    private static final BedrockItemStack NEW = stack("test:new");

    private static BedrockItemStack stack(String identifier) {
        return BedrockItemStack.of(BedrockItemType.fromIdentifier(identifier));
    }

    /**
     * ITEMS: [group "g1" {a, b}], loose, [group "g2" {c}]
     */
    private static CreativeLayout layout() {
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        categories.put(CreativeCategory.ITEMS, List.of(
            new CreativeGroup("g1", null, List.of(A, B)),
            new CreativeItem(LOOSE),
            new CreativeGroup("g2", C, List.of(C))));
        return CreativeLayout.of(categories);
    }

    @Test
    void insertAfterAnAnchorStaysInItsGroup() {
        CreativeEditor editor = layout().edit();
        assertTrue(editor.insert(NEW).after(ItemMatcher.id("test:a")));
        CreativeGroup g1 = editor.build().group("g1").orElseThrow();
        assertEquals(List.of(A, NEW, B), g1.stacks());
    }

    @Test
    void insertBeforeALooseAnchorIsLoose() {
        CreativeEditor editor = layout().edit();
        assertTrue(editor.insert(NEW).before(ItemMatcher.id("test:loose")));
        assertEquals(new CreativeItem(NEW), editor.view(CreativeCategory.ITEMS).get(1));
        assertTrue(editor.contains(ItemMatcher.id("test:new")));
        assertNull(editor.groupOf(ItemMatcher.id("test:new")));
        assertEquals("g1", editor.groupOf(ItemMatcher.id("test:a")));
    }

    @Test
    void missingAnchorsReturnFalseSoFallbacksChain() {
        CreativeEditor editor = layout().edit();
        CreativeEditor.Insertion insertion = editor.insert(NEW);
        assertFalse(insertion.after(ItemMatcher.id("test:missing")));
        assertTrue(insertion.after(ItemMatcher.id("test:missing")) || insertion.intoGroup("g2"));
        // A placed insertion does nothing more
        assertFalse(insertion.loose(CreativeCategory.NATURE));
        assertEquals(List.of(C, NEW), editor.build().group("g2").orElseThrow().stacks());
    }

    @Test
    void moveRemovesFromTheOldPosition() {
        CreativeEditor editor = layout().edit();
        assertTrue(editor.move(ItemMatcher.id("test:a")).after(ItemMatcher.id("test:c")));
        CreativeLayout result = editor.build();
        assertEquals(List.of(B), result.group("g1").orElseThrow().stacks());
        assertEquals(List.of(C, A), result.group("g2").orElseThrow().stacks());
    }

    @Test
    void moveCannotAnchorToItself() {
        CreativeEditor editor = layout().edit();
        assertFalse(editor.move(ItemMatcher.id("test:a")).after(ItemMatcher.id("test:a")));
        // Nothing was removed
        assertEquals(List.of(A, B), editor.build().group("g1").orElseThrow().stacks());
    }

    @Test
    void groupsCanBeCreatedAndPlacedRelativeToOthers() {
        CreativeEditor editor = layout().edit();
        editor.createGroup(CreativeCategory.ITEMS, "g3").add(NEW).placeAfterGroup("g1");
        List<CreativeNode> nodes = editor.view(CreativeCategory.ITEMS);
        assertEquals("g3", ((CreativeGroup) nodes.get(1)).name());
        assertThrows(IllegalArgumentException.class, () -> editor.createGroup(CreativeCategory.NATURE, "g1"));
    }

    @Test
    void groupPlacementByItemAnchor() {
        CreativeEditor editor = layout().edit();
        assertTrue(editor.group("g2").orElseThrow().placeBefore(ItemMatcher.id("test:b")));
        // Placed before the group containing b
        assertEquals("g2", ((CreativeGroup) editor.view(CreativeCategory.ITEMS).getFirst()).name());
    }

    @Test
    void removingAnIconResetsItToTheDefault() {
        CreativeEditor editor = layout().edit();
        assertEquals(1, editor.remove(ItemMatcher.id("test:c")));
        assertNull(editor.build().group("g2").orElseThrow().icon());
    }

    @Test
    void dissolveKeepsStacksInPlace() {
        CreativeEditor editor = layout().edit();
        assertTrue(editor.dissolveGroup("g1"));
        assertEquals(List.of(new CreativeItem(A), new CreativeItem(B), new CreativeItem(LOOSE)), editor.view(CreativeCategory.ITEMS).subList(0, 3));
    }

    @Test
    void sortAndReplace() {
        CreativeEditor editor = layout().edit();
        editor.group("g1").orElseThrow().sort(Comparator.comparing((BedrockItemStack stack) -> stack.type().identifier()).reversed());
        assertEquals(1, editor.replace(ItemMatcher.id("test:b"), stack -> stack.withVariant(stack.type().variant(3))));
        assertEquals(List.of(B.withVariant(B.type().variant(3)), A), editor.build().group("g1").orElseThrow().stacks());
    }

    @Test
    void replaceNullChecksTheReplacementOfGroupIconsToo() {
        CreativeEditor editor = layout().edit();
        assertThrows(NullPointerException.class, () -> editor.replace(ItemMatcher.id("test:c"), stack -> null));
    }

    @Test
    void placementPrefersAnchorThenGroupThenLoose() {
        CreativeEditor editor = layout().edit();
        Placements.apply(CreativePlacement.after(ItemMatcher.id("test:c")), editor, NEW, CreativeCategory.ITEMS, "g1", CreativeOrdering.APPEND);
        assertEquals(List.of(C, NEW), editor.build().group("g2").orElseThrow().stacks());

        BedrockItemStack other = stack("test:other");
        assertFalse(Placements.apply(CreativePlacement.after(ItemMatcher.id("test:missing")), editor, other, CreativeCategory.NATURE, "new_group",
            CreativeOrdering.APPEND), "a missing anchor is told");
        assertEquals(List.of(other), editor.build().group("new_group").orElseThrow().stacks());

        BedrockItemStack third = stack("test:third");
        assertTrue(Placements.apply(CreativePlacement.DEFAULT, editor, third, CreativeCategory.ITEMS, null, CreativeOrdering.APPEND));
        assertEquals(new CreativeItem(third), editor.view(CreativeCategory.ITEMS).getLast());
    }

    @Test
    void editsDoNotChangeTheOriginalLayout() {
        CreativeLayout original = layout();
        CreativeEditor editor = original.edit();
        editor.remove(ItemMatcher.id("test:a"));
        assertEquals(List.of(A, B), original.group("g1").orElseThrow().stacks());
    }

    @Test
    void vanillaLayoutIsMadeOfStacksThatEncodeToTheEntries() {
        Palette palette = PALETTER.palette(2225);
        List<BedrockItemStack> layoutStacks = palette.creativeLayout().stacks().toList();
        assertTrue(layoutStacks.stream().anyMatch(stack -> stack.blockState() != null), "block items keep their state");
        for (int netId = 1; netId <= palette.creative().size(); netId += 97) {
            CreativeEntry entry = palette.creative().entry(netId);
            assertEquals(entry.item().getDefinition(), palette.encode(entry.stack()).getDefinition());
            assertEquals(entry.item().getBlockDefinition(), palette.encode(entry.stack()).getBlockDefinition());
        }
    }

    @Test
    void containsAndGroupOfLookThroughTheLayout() {
        CreativeLayout layout = layout();
        assertTrue(layout.contains(ItemMatcher.id("test:b")));
        assertFalse(layout.contains(ItemMatcher.id("test:missing")));
        assertEquals("g2", layout.groupOf(ItemMatcher.id("test:c")));
        assertNull(layout.groupOf(ItemMatcher.id("test:loose")));
    }
}
