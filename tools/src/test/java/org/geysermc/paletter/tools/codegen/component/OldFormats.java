package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Why a component has no compared variant in a spec capture, from the data: what BDS did with it at the formats the format sweep
 * asked ({@code formats} of {@code mappings.json}, see {@code FormatInference}) and what the white-box says
 * ({@code network: false}). A component BDS rejects at the current format is accounted for as exactly one of:
 * <ul>
 *     <li>{@link #REPLACED}: BDS upgrades it at an older format into another component (its wire is that component's key). The
 *     successor must be verified: it has a compared variant in the same capture, compiled through the generated API;</li>
 *     <li>{@link #OLD_STYLE_ITEM}: BDS sends it only inside an old-style item (a legacy envelope the API does not write);</li>
 *     <li>{@link #SERVER_ONLY}: BDS accepts it at some format and sends nothing, or the white-box says it is not sent;</li>
 *     <li>{@link #REJECTED_EVERYWHERE}: swept at every known format and rejected at each.</li>
 * </ul>
 * Anything else (accepted with a wire of its own at an old format, say) is not accounted for, and fails. Without sweep data the
 * answer is {@link #PENDING_SWEEP}: the test lists those components explicitly until the capture with the sweep is committed.
 */
final class OldFormats {
    static final String REPLACED = "replaced";
    static final String OLD_STYLE_ITEM = "old-style item";
    static final String SERVER_ONLY = "server-only";
    static final String REJECTED_EVERYWHERE = "rejected at every format";
    static final String PENDING_SWEEP = "pending sweep";

    private OldFormats() {
    }

    /**
     * @param mapping    the component's entry of {@code mappings.json}, or null
     * @param notSent    whether the white-box says BDS does not send the component
     * @param verified   the components with a compared variant in the capture (what a replacement must be among)
     * @return the classification, {@link #PENDING_SWEEP} without sweep data, or null if the data fits none (a failure to explain)
     */
    static String classify(JsonObject mapping, boolean notSent, Set<String> verified) {
        JsonObject formats = mapping != null && mapping.has("formats") ? mapping.getAsJsonObject("formats") : null;
        if (formats != null && !formats.isEmpty()) {
            List<JsonObject> entries = new ArrayList<>();
            formats.asMap().values().forEach(e -> entries.add(e.getAsJsonObject()));
            Set<String> successors = new java.util.TreeSet<>();
            entries.forEach(e -> {
                if (e.has("upgradedTo")) {
                    e.getAsJsonArray("upgradedTo").forEach(s -> successors.add(s.getAsString()));
                }
            });
            if (!successors.isEmpty()) {
                return verified.containsAll(successors) ? REPLACED : null;
            }
            if (entries.stream().anyMatch(e -> e.has("legacyEnvelope"))) {
                return OLD_STYLE_ITEM;
            }
            if (notSent || entries.stream().allMatch(e -> e.getAsJsonArray("wire").isEmpty())) {
                return SERVER_ONLY;
            }
            return null;
        }
        if (notSent) {
            return SERVER_ONLY;
        }
        return formats == null ? PENDING_SWEEP : REJECTED_EVERYWHERE;
    }

    /** Whether the white-box says the component never reaches a client (a {@code network: false} entry). */
    static boolean notSent(JsonObject whitebox, String componentId) {
        String section = componentId.startsWith("item:") ? "itemComponents" : "blockComponents";
        JsonElement entry = whitebox == null || !whitebox.has(section) ? null : whitebox.getAsJsonObject(section).get(componentId.replaceFirst("^[a-z]+:", ""));
        return entry != null && entry.getAsJsonObject().has("network") && !entry.getAsJsonObject().get("network").getAsBoolean();
    }
}
