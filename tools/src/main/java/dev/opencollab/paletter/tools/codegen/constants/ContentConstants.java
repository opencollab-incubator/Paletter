package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.CONSTANT;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.HISTORY;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.addName;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.constantName;

/**
 * The constants of {@code BedrockBlocks} and {@code BedrockItems}: names, {@code since}, the Bedrock release, deprecated
 * aliases and the history entries.
 */
final class ContentConstants {
    private final Generation g;

    ContentConstants(Generation g) {
        this.g = g;
    }

    /**
     * A generated constant.
     *
     * @param identifier the identifier it resolves to (for a removed one: the one it had)
     * @param replacement for a deprecated alias: the constant it points to (never another alias)
     * @param deprecated the version it was deprecated in, or null
     * @param bedrock the bundled Bedrock release it first appears in without an experiment, or null (preview constants, and
     *                content of the baseline that the oldest bundled release has: it is older than any bundled release)
     * @param education whether BDS only offers it with Education Edition features on
     * @param gating the experiment toggles that gate it in bundled releases, e.g. {@code `y_2026_drop_3` in 26.40}, or null
     * @param preview the bundled preview that alone has it ({@code @SincePreview}; not in the history), or null
     */
    record Constant(String name, String identifier, String since, String deprecated, String replacement,
                    String bedrock, boolean education, String gating, String preview) {
        Constant withReplacement(String replacement) {
            return new Constant(name, identifier, since, deprecated, replacement, bedrock, education, gating, preview);
        }
    }

    /**
     * The states each block constant the newest release lacks carries, by constant name: a preview block's, and a
     * removed or dropped block's last known ones. Filled by {@link #constants} for blocks.
     */
    final Map<String, ConstantsGenerator.PreviewStates> states = new HashMap<>();

    /** Where a block constant the newest release lacks gets its states. */
    interface StatesSource {
        /**
         * @param old its history entry, or null
         * @return the states, or null if nothing knows them (a block whose states no full product describes)
         */
        ConstantsGenerator.PreviewStates of(String identifier, JsonObject old);
    }

    /**
     * @param previewIdentifiers what only the bundled preview has: constants marked with the preview, and recorded in the
     *                           history with it, so a constant a newer preview drops stays, deprecated, like removed content
     * @param statesSource for blocks: the states of a constant the newest release lacks; null for items
     */
    List<Constant> constants(String kind, List<String> identifiers, List<String> previewIdentifiers, String className,
                             StatesSource statesSource) {
        Map<String, String> overrides = g.names.overrides(kind);
        JsonObject previous = ConstantsHistory.object(g.history, kind);
        Set<String> stillPreview = new java.util.HashSet<>(previewIdentifiers);

        previous.entrySet().forEach(entry -> ConstantsHistory.entry(entry, kind));
        Map<String, String> current = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        for (String identifier : identifiers) {
            String name = overrides.getOrDefault(identifier, constantName(identifier));
            if (overrides.containsKey(identifier)) {
                g.usedNames.add(kind + " " + identifier);
            }
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException(identifier + " gives the constant name " + name + ", which is not a Java constant; "
                    + addName(kind, identifier, "<NAME>"));
            }
            String existing = current.put(name, identifier);
            if (existing != null) {
                throw new IllegalStateException("Duplicate constant " + className + "." + name + " for " + existing + " and " + identifier
                    + "; give one a name: " + addName(kind, identifier, "<NAME>"));
            }
            nameOf.put(identifier, name);
        }

        Map<String, Constant> constants = new TreeMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            JsonObject old = previous.has(entry.getKey()) ? previous.getAsJsonObject(entry.getKey()) : null;
            String since = old != null ? ConstantsHistory.required(old, "since", kind + "." + entry.getKey()) : g.version;
            if (old == null) {
                g.added.add(className + "." + entry.getKey() + " = " + entry.getValue());
            }
            String bedrock = bedrock(old, kind, entry.getValue());
            boolean education = old != null && old.has("education") && old.get("education").getAsBoolean() || g.sources.isEducation(kind, entry.getValue());
            constants.put(entry.getKey(), new Constant(entry.getKey(), entry.getValue(), since, null, null, bedrock, education,
                g.sources.gating(kind, entry.getValue()), null));
        }
        // Constants the data no longer has under that name stay, deprecated
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(previous.asMap()).entrySet()) {
            String name = entry.getKey();
            if (constants.containsKey(name)) {
                continue;
            }
            JsonObject old = ConstantsHistory.entry(entry, kind);
            String identifier = ConstantsHistory.required(old, "identifier", kind + "." + name);
            String droppedPreview = old.has("preview") ? old.get("preview").getAsString() : null;
            if (droppedPreview != null && stillPreview.contains(identifier)) {
                // Still only the preview's: made below
                continue;
            }
            String since = ConstantsHistory.required(old, "since", kind + "." + name);
            String bedrock = bedrock(old, kind, identifier);
            boolean education = old.has("education") && old.get("education").getAsBoolean() || g.sources.isEducation(kind, identifier);
            String deprecatedSince = old.has("deprecated") ? old.get("deprecated").getAsString() : g.version;
            String target = nameOf.get(identifier);
            if (target == null && g.names.renamed(kind, identifier) != null) {
                target = nameOf.get(g.names.renamed(kind, identifier));
            }
            if (target == null && old.has("replacement")) {
                target = old.get("replacement").getAsString();
                if (!current.containsKey(target) && !previous.has(target)) {
                    throw new IllegalStateException(HISTORY + ": " + kind + "." + name + " is an alias of " + target
                        + ", which is neither generated nor in the history");
                }
            }
            if (!old.has("deprecated")) {
                g.deprecated.add(className + "." + name + " (" + identifier + (target != null ? ", now " + target
                    : droppedPreview != null ? ", dropped by the preview; resolves through the fallbacks" : ", removed; resolves through the fallbacks") + ")");
            }
            constants.put(name, new Constant(name, identifier, since, deprecatedSince, target, bedrock, education, g.sources.gating(kind, identifier),
                target == null ? droppedPreview : null));
            if (target == null && statesSource != null) {
                ConstantsGenerator.PreviewStates own = statesSource.of(identifier, old);
                if (own != null) {
                    states.put(name, own);
                }
            }
        }
        // An alias points at the constant at the end of its chain: a static field cannot refer to one declared after it
        for (String name : new ArrayList<>(constants.keySet())) {
            Constant alias = constants.get(name);
            Set<String> seen = new LinkedHashSet<>(List.of(name));
            String target = alias.replacement();
            while (target != null && constants.containsKey(target) && constants.get(target).replacement() != null) {
                if (!seen.add(target)) {
                    throw new IllegalStateException(className + "." + name + " is part of a cycle of aliases: " + seen);
                }
                target = constants.get(target).replacement();
            }
            if (target != null && !target.equals(alias.replacement())) {
                constants.put(name, alias.withReplacement(target));
            }
        }
        // Aliases point at constants that exist
        for (Constant constant : constants.values()) {
            if (constant.replacement() != null && !constants.containsKey(constant.replacement())) {
                throw new IllegalStateException(className + "." + constant.name() + " is deprecated for " + constant.replacement() + ", which does not exist");
            }
        }

        for (String identifier : previewIdentifiers) {
            String name = overrides.getOrDefault(identifier, constantName(identifier));
            if (overrides.containsKey(identifier)) {
                g.usedNames.add(kind + " " + identifier);
            }
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException("Preview " + identifier + " gives the constant name " + name + "; " + addName(kind, identifier, "<NAME>"));
            }
            if (constants.containsKey(name)) {
                throw new IllegalStateException("Duplicate constant " + className + "." + name + " for " + constants.get(name).identifier()
                    + " and the preview's " + identifier + "; give one a name: " + addName(kind, identifier, "<NAME>"));
            }
            JsonObject old = previous.has(name) ? previous.getAsJsonObject(name) : null;
            String since = old != null && old.has("since") ? old.get("since").getAsString() : g.version;
            constants.put(name, new Constant(name, identifier, since, null, null, null, false, null, g.previewName()));
            if (statesSource != null) {
                ConstantsGenerator.PreviewStates own = statesSource.of(identifier, old);
                if (own != null) {
                    states.put(name, own);
                }
            }
        }

        JsonObject out = new JsonObject();
        for (Constant constant : constants.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", constant.identifier());
            entry.addProperty("since", constant.since());
            if (constant.bedrock() != null) {
                entry.addProperty("bedrock", constant.bedrock());
            }
            if (constant.education()) {
                entry.addProperty("education", true);
            }
            if (constant.deprecated() != null) {
                entry.addProperty("deprecated", constant.deprecated());
                if (constant.replacement() != null) {
                    entry.addProperty("replacement", constant.replacement());
                }
            }
            if (constant.preview() != null) {
                // Recorded, so a newer preview that drops it keeps the constant (deprecated) instead of removing the field
                entry.addProperty("preview", constant.preview());
            }
            ConstantsGenerator.PreviewStates own = states.get(constant.name());
            if (own != null) {
                // The release lacks the block: its states are kept for when no bundled data has them anymore
                entry.add("states", own.toJson());
            }
            out.add(constant.name(), entry);
        }
        g.newHistory.add(kind, out);
        return List.copyOf(constants.values());
    }

    /**
     * The bundled release a constant first appears in. It is derived on every generation, so it follows the data (a release
     * back-filled or ungating the content moves it); only a recorded one stays that cannot be derived anymore: its release
     * is no longer bundled, or no bundled release has the identifier. Null for content of the baseline (see
     * {@link ConstantsGenerator.Sources#withBaseline}) that the oldest bundled release has: it is older than the bundled data says.
     */
    private String bedrock(JsonObject old, String kind, String identifier) {
        String recorded = old != null && old.has("bedrock") ? old.get("bedrock").getAsString() : null;
        if (recorded != null && !(g.sources.isRelease(recorded) && g.sources.has(kind, identifier))) {
            return recorded;
        }
        String first = g.sources.firstVersion(kind, identifier);
        return g.sources.inBaseline(kind, identifier) && first.equals(g.sources.oldest()) ? null : first;
    }
}
