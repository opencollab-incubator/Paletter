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
        entry("block:minecraft:item_visual:geometry.bone_visibility", "itemVisualBoneVisibility"),
        entry("block:minecraft:item_visual:geometry.uv_lock", "itemVisualUvLock"),
        entry("block:minecraft:item_visual:material_instances", "materialInstancesPacked"),
        entry("block:minecraft:material_instances:", "materialInstances"),
        // states as a list of {state, type, value}: another layout than the descriptor rule's states compound
        entry("block:minecraft:placement_filter:conditions[].block_filter[].states", "blockDescriptorStates"),
        entry("block:minecraft:selection_box:", "selectionBox"),
        entry("block:permutations:[].components", "permutationComponents"),
        entry("block:permutations:[].condition", "permutationComponents"),
        // items
        // the name is resolved against the registry and sent as "" for a vanilla block this version lacks: the leaf is no function of the value
        entry("item:minecraft:block_placer:block", "blockName"),
        // follows two fields and the item's own identifier
        entry("item:minecraft:block_placer:replace_block_item", "replaceBlockItem"),
        entry("item:minecraft:dyeable:default_color", "rgbColor"),
        entry("item:minecraft:hover_text_color:value", "formattingCode"),
        entry("item:minecraft:icon:", "iconTextures"),
        // item descriptors and Molang that change shape with the protocol, in one list the entries of which are dropped by the registry
        entry("item:minecraft:repairable:repair_items", "itemDescriptorList"),
        // the API holds the item of a map form as null, which BDS sends as {} while an omitted item sends nothing: no rule over null fits both
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
