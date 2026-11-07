package org.geysermc.paletter;

import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeLocation;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.creative.ItemMatcher;
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
    private static final CreativeStack A = CreativeStack.of("test:a");
    private static final CreativeStack B = CreativeStack.of("test:b");
    private static final CreativeStack C = CreativeStack.of("test:c");
    private static final CreativeStack LOOSE = CreativeStack.of("test:loose");
    private static final CreativeStack NEW = CreativeStack.of("test:new");

    /**
     * ITEMS: [group "g1" {a, b}], loose, [group "g2" {c}]
     */
    private static CreativeLayout layout() {
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        categories.put(CreativeCategory.ITEMS, List.of(
            new CreativeGroup("g1", null, List.of(A, B)),
            LOOSE,
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
        assertEquals(new CreativeLocation(CreativeCategory.ITEMS, null, 1), editor.locate(ItemMatcher.id("test:new")).orElseThrow());
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
        assertEquals(List.of(A, B, LOOSE), editor.view(CreativeCategory.ITEMS).subList(0, 3));
    }

    @Test
    void sortAndReplace() {
        CreativeEditor editor = layout().edit();
        editor.group("g1").orElseThrow().sort(Comparator.comparing(CreativeStack::identifier).reversed());
        assertEquals(1, editor.replace(ItemMatcher.id("test:b"), stack -> stack.withDamage(3)));
        assertEquals(List.of(B.withDamage(3), A), editor.build().group("g1").orElseThrow().stacks());
    }

    @Test
    void placementPrefersAnchorThenGroupThenLoose() {
        CreativeEditor editor = layout().edit();
        CreativePlacement.group(CreativeCategory.ITEMS, "g1").after(ItemMatcher.id("test:c")).apply(editor, NEW);
        assertEquals(List.of(C, NEW), editor.build().group("g2").orElseThrow().stacks());

        CreativeStack other = CreativeStack.of("test:other");
        CreativePlacement.group(CreativeCategory.NATURE, "new_group").after(ItemMatcher.id("test:missing")).apply(editor, other);
        assertEquals(List.of(other), editor.build().group("new_group").orElseThrow().stacks());

        CreativeStack third = CreativeStack.of("test:third");
        CreativePlacement.loose(CreativeCategory.ITEMS).apply(editor, third);
        assertEquals(third, editor.view(CreativeCategory.ITEMS).getLast());
    }

    @Test
    void editsDoNotChangeTheOriginalLayout() {
        CreativeLayout original = layout();
        CreativeEditor editor = original.edit();
        editor.remove(ItemMatcher.id("test:a"));
        assertEquals(List.of(A, B), original.group("g1").orElseThrow().stacks());
    }
}
