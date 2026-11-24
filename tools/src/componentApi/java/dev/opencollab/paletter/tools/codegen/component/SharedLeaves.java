package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.rule.Identity;
import dev.opencollab.paletter.tools.model.rule.Rule;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Leaves outside a component's own compound that the value rules of several components of a kind write
 * ({@code item_properties.max_stack_size}: {@code minecraft:max_stack_size} and the slot table of {@code minecraft:wearable}).
 * Components are encoded in the order of the definition, so a plain {@code put} by each would make the result depend on
 * that order. Each such leaf has one owner instead, the component that carries the field's value to it unchanged (an
 * {@code identity} rule); the encoder of the owner writes it with {@code WireNode.own}, the others with
 * {@code putUnowned}, which keeps the owner's value whatever the order.
 * <p>
 * BDS keeps the owner's value as well: max_stack_size and allow_off_hand over wearable's slot table, in both orders of the file
 * (item_properties of the combination variants of the spec captures, 1.26.36.1 to 1.26.70.24).
 */
final class SharedLeaves {
    private SharedLeaves() {
    }

    /**
     * @return leaf -> the id of its owning component, for every leaf of the kind several components write by value
     * @throws GenerationException for a leaf with no single owner
     */
    static Map<String, String> owners(ApiModel model, Kind kind) {
        // leaf -> component id -> whether one of its rules onto the leaf is an identity
        Map<String, Map<String, Boolean>> writers = new TreeMap<>();
        for (Comp c : model.components) {
            if (c.kind != kind || kind == Kind.TRAIT || c.standalone) {
                continue;
            }
            for (Window w : c.windows) {
                if (w.sent()) {
                    for (String section : new String[] {"fields", "forms"}) {
                        JsonObject mappings = w.mapping().has(section) ? w.mapping().getAsJsonObject(section) : new JsonObject();
                        for (Map.Entry<String, JsonElement> m : mappings.entrySet()) {
                            for (Rule r : Rule.all(m.getValue().getAsJsonObject())) {
                                if (r.writesValue() && r.wire != null && !r.wire.startsWith(c.name + ".")) {
                                    writers.computeIfAbsent(r.wire, k -> new TreeMap<>()).merge(c.id, r instanceof Identity, Boolean::logicalOr);
                                }
                            }
                        }
                    }
                }
            }
        }
        Map<String, String> owners = new TreeMap<>();
        writers.forEach((leaf, byComponent) -> {
            if (byComponent.size() < 2) {
                return;
            }
            Set<String> identity = new TreeSet<>();
            byComponent.forEach((id, carries) -> {
                if (carries) {
                    identity.add(id);
                }
            });
            if (identity.size() != 1) {
                throw new GenerationException(leaf + " is written by the value rules of " + byComponent.keySet() + ", and "
                    + (identity.isEmpty() ? "none" : "each of " + identity) + " carries a value to it unchanged: which one owns"
                    + " the leaf is open; capture the components together");
            }
            String owner = identity.iterator().next();
            owners.put(leaf, owner);
            System.out.println("unverified: " + leaf + " is written by " + byComponent.keySet() + "; " + owner
                + " owns it and its value is kept (TODO: capture them together)");
        });
        return owners;
    }
}
