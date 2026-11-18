package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.data.VariantNames;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.CONSTANT;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.HISTORY;

/** The constants of {@code BedrockItemVariants}. */
final class VariantConstants {
    private final Generation g;

    VariantConstants(Generation g) {
        this.g = g;
    }

    /**
     * An item variant constant: the generated constant of {@code BedrockItemVariants}.
     *
     * @param constant the name, the item's identifier, {@code since} and the marks. It has no {@code bedrock} release: the
     *                 names come from dumps of only the newest builds, which say nothing of older ones
     * @param aux the aux value
     */
    record Variant(ContentConstants.Constant constant, int aux) {
    }

    /**
     * The constants of {@code BedrockItemVariants}: the names of {@code variant_names.txt} for the newest release, with
     * what only the bundled preview has marked {@code @SincePreview}. The history records each name with its identifier and
     * aux: a name is never dropped (one the data no longer has stays, deprecated; one that is now another name for the same
     * variant becomes an alias of it) and never changes meaning (a changed identifier or aux fails generation: older
     * bundled versions keep the old aux, so it would need a split in {@code renames.txt} and a new name).
     */
    List<Variant> variants() {
        JsonObject previous = ConstantsHistory.object(g.history, "variants");
        Map<String, VariantNames.Entry> current = new TreeMap<>();
        g.sources.variantNames(g.release.version().name()).forEach(entry -> current.put(entry.name(), entry));
        if (current.isEmpty() && !previous.isEmpty()) {
            throw new IllegalStateException("The newest release " + g.release.version().name() + " has no variant names in data/model/variant_names.txt, "
                + "but " + HISTORY + " has " + previous.size() + ": that would deprecate them all. Run script-dump on a build of it; names are not carried forward");
        }
        Map<String, String> nameOf = new HashMap<>();
        current.values().forEach(entry -> nameOf.put(entry.identifier() + "@" + entry.aux(), entry.name()));
        Map<String, Variant> variants = new TreeMap<>();
        for (VariantNames.Entry entry : current.values()) {
            checkName(entry);
            JsonObject old = previous.has(entry.name()) ? previous.getAsJsonObject(entry.name()) : null;
            if (old != null) {
                checkUnchanged(entry, old);
            } else {
                g.added.add("BedrockItemVariants." + entry.name() + " = " + entry.identifier() + "@" + entry.aux());
            }
            String since = old != null ? ConstantsHistory.required(old, "since", "variants." + entry.name()) : g.version;
            variants.put(entry.name(), new Variant(new ContentConstants.Constant(entry.name(), entry.identifier(), since, null, null, null, false, null, null),
                entry.aux()));
        }
        // Names the data no longer has stay, deprecated
        for (Map.Entry<String, JsonElement> entry : previous.entrySet()) {
            if (variants.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject old = entry.getValue().getAsJsonObject();
            String name = entry.getKey();
            String identifier = ConstantsHistory.required(old, "identifier", "variants." + name);
            int aux = old.get("aux").getAsInt();
            String deprecatedSince = old.has("deprecated") ? old.get("deprecated").getAsString() : g.version;
            String target = nameOf.get(identifier + "@" + aux);
            if (target == null && old.has("replacement") && previous.has(old.get("replacement").getAsString())) {
                target = old.get("replacement").getAsString();
            }
            if (!old.has("deprecated")) {
                g.deprecated.add("BedrockItemVariants." + name + " (" + identifier + "@" + aux + (target != null ? ", now " + target : ", removed") + ")");
            }
            variants.put(name, new Variant(new ContentConstants.Constant(name, identifier, ConstantsHistory.required(old, "since", "variants." + name), deprecatedSince, target,
                null, false, null, null), aux));
        }
        JsonObject out = new JsonObject();
        for (Variant variant : variants.values()) {
            ContentConstants.Constant constant = variant.constant();
            if (constant.replacement() != null && !variants.containsKey(constant.replacement())) {
                throw new IllegalStateException("BedrockItemVariants." + constant.name() + " is deprecated for " + constant.replacement() + ", which does not exist");
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", constant.identifier());
            entry.addProperty("aux", variant.aux());
            entry.addProperty("since", constant.since());
            if (constant.deprecated() != null) {
                entry.addProperty("deprecated", constant.deprecated());
                if (constant.replacement() != null) {
                    entry.addProperty("replacement", constant.replacement());
                }
            }
            out.add(constant.name(), entry);
        }
        g.newHistory.add("variants", out);
        // What only the preview has: marked, and not in the history
        if (g.preview != null) {
            for (VariantNames.Entry entry : g.sources.variantNames(g.preview.version().name())) {
                if (variants.containsKey(entry.name())) {
                    continue;
                }
                checkName(entry);
                variants.put(entry.name(), new Variant(new ContentConstants.Constant(entry.name(), entry.identifier(), g.version, null, null, null, false, null,
                    g.previewName()), entry.aux()));
            }
        }
        return List.copyOf(variants.values());
    }

    private static void checkName(VariantNames.Entry entry) {
        if (!CONSTANT.matcher(entry.name()).matches()) {
            throw new IllegalStateException(entry.identifier() + "@" + entry.aux() + " gives the variant name " + entry.name() + ", which is not a Java constant");
        }
    }

    private static void checkUnchanged(VariantNames.Entry entry, JsonObject old) {
        String identifier = old.get("identifier").getAsString();
        int aux = old.get("aux").getAsInt();
        if (!identifier.equals(entry.identifier()) || aux != entry.aux()) {
            throw new IllegalStateException("BedrockItemVariants." + entry.name() + " is " + identifier + "@" + aux + " in " + HISTORY + " but BDS now has "
                + entry.identifier() + "@" + entry.aux() + " under that name; a name keeps its meaning, so this needs a split in renames.txt and a new name");
        }
    }
}
