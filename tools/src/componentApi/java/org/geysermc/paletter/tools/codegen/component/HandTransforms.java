package org.geysermc.paletter.tools.codegen.component;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static java.util.Map.entry;

/**
 * The fields no inferred rule explains, and the method of {@code org.geysermc.paletter.internal.component.Transforms}
 * that encodes each one instead. The generated encoders call that method with the record that holds the field (the
 * component itself for a whole component transform). Why a field needs one, and which captures or BDS behaviour back
 * it, is the Javadoc of the method; protocol differences are checks inside it.
 * <p>
 * Keys are {@code <component>:<path>}; an empty path is the whole component, {@code []} its list form. A key covers its
 * path and everything below it. A field the inference does explain does not belong here.
 */
public final class HandTransforms {
    /** {@code <component>:<path>} -> {@code Transforms} method, sorted by key (the outputs list them in this order). */
    public static final SortedMap<String, String> BY_PATH = Collections.unmodifiableSortedMap(new TreeMap<>(Map.ofEntries(
        // blocks
        entry("block:minecraft:collision_box:[]", "collisionBoxes"),
        entry("block:minecraft:destructible_by_mining:", "destructibleByMining"),
        entry("block:minecraft:embedded_visual:geometry", "embeddedGeometry"),
        entry("block:minecraft:embedded_visual:material_instances", "materialInstancesInline"),
        entry("block:minecraft:geometry:", "geometry"),
        entry("block:minecraft:item_visual:material_instances", "materialInstancesPacked"),
        entry("block:minecraft:material_instances:", "materialInstances"),
        entry("block:minecraft:placement_filter:conditions[].block_filter[].states", "blockDescriptorStates"),
        entry("block:minecraft:selection_box:", "selectionBox"),
        entry("block:permutations:[].components", "permutationComponents"),
        // items
        entry("item:minecraft:block_placer:block", "blockName"),
        entry("item:minecraft:block_placer:use_on[].states", "blockDescriptorStates"),
        entry("item:minecraft:digger:destroy_speeds[].block.states", "blockDescriptorStates"),
        entry("item:minecraft:dyeable:default_color", "rgbColor"),
        entry("item:minecraft:entity_placer:dispense_on[].states", "blockDescriptorStates"),
        entry("item:minecraft:entity_placer:use_on[].states", "blockDescriptorStates"),
        entry("item:minecraft:food:using_converts_to", "itemDescriptorName"),
        entry("item:minecraft:hover_text_color:value", "formattingCode"),
        entry("item:minecraft:icon:", "iconTextures"),
        entry("item:minecraft:repairable:repair_items", "itemDescriptorList"),
        entry("item:minecraft:shooter:ammunition[].item", "ammunitionItemDescriptor"),
        entry("item:minecraft:use_animation:value", "useAnimationId"))));

    private HandTransforms() {
    }

    /**
     * The key of {@code transforms} that covers a field: the field itself, the nearest object or list containing it, or the
     * whole component; null if none does.
     */
    public static String covering(Map<String, String> transforms, String component, String path) {
        String p = path;
        while (true) {
            if (transforms.containsKey(component + ":" + p)) {
                return component + ":" + p;
            }
            int cut = Math.max(p.lastIndexOf('.'), p.lastIndexOf("[]"));
            if (cut < 0) {
                return p.isEmpty() || !transforms.containsKey(component + ":") ? null : component + ":";
            }
            p = p.substring(0, cut);
        }
    }
}
