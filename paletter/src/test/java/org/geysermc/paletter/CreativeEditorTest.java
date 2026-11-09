package org.geysermc.paletter;

import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeEntry;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeItem;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemType;
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
        return BedrockItemStack.of(BedrockItemType.of(identifier));
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
        assertEquals(1, editor.replace(ItemMatcher.id("test:b"), stack -> stack.withVariant(stack.type().variantOf(3))));
        assertEquals(List.of(B.withVariant(B.type().variantOf(3)), A), editor.build().group("g1").orElseThrow().stacks());
    }

    @Test
    void placementPrefersAnchorThenGroupThenLoose() {
        CreativeEditor editor = layout().edit();
        CreativePlacement.group(CreativeCategory.ITEMS, "g1").after(ItemMatcher.id("test:c")).apply(editor, NEW);
        assertEquals(List.of(C, NEW), editor.build().group("g2").orElseThrow().stacks());

        BedrockItemStack other = stack("test:other");
        CreativePlacement.group(CreativeCategory.NATURE, "new_group").after(ItemMatcher.id("test:missing")).apply(editor, other);
        assertEquals(List.of(other), editor.build().group("new_group").orElseThrow().stacks());

        BedrockItemStack third = stack("test:third");
        CreativePlacement.loose(CreativeCategory.ITEMS).apply(editor, third);
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
