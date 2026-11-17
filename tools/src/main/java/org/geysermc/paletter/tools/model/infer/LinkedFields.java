package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Obs;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Variant;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The fields whose value no rule follows (nothing on the wire does, or only their presence) while the messages of their
 * variants name another field: what BDS sent says nothing about them, as the other field decided it (status
 * {@code linked}). The add-on generator varies them with that field ({@code bds-feedback.json} links).
 */
final class LinkedFields {
    private final Set<String> found = new TreeSet<>();

    /** @return the linked fields found, for {@code check-mappings --strict} and {@code update}, which fail on them */
    List<String> list() {
        return List.copyOf(found);
    }

    /**
     * Adds a field of status {@code linked}.
     *
     * @param field    its result ({@code linkedTo}: the fields its variants' messages name)
     * @param variants its variants
     */
    void add(String componentId, String path, String bdsVersion, JsonObject field, List<Obs> variants) {
        boolean tried = variants.stream().anyMatch(o -> o.accepted() && !o.variant().linked().isEmpty());
        found.add(componentId + " " + path + " (BDS " + bdsVersion + "): no rule follows its value, and"
            + " its variants' messages name " + String.join(", ", field.getAsJsonArray("linkedTo").asList().stream().map(JsonElement::getAsString).toList())
            + (tried ? "; BDS accepted variants linked with it, which no rule explains: it needs a rule or a hand transform"
            : "; vary them together (bds-feedback.json links) and capture again"));
    }

    /**
     * Per field, the wire leaves its own variants move against the reference (generic paths): what a variant that sets the
     * field only because BDS ties it to the varied one ({@link Variant#linked}) moves besides the varied field.
     */
    static Map<String, Set<String>> moved(Map<String, List<Obs>> byField, Obs reference) {
        Map<String, Set<String>> moved = new TreeMap<>();
        byField.forEach((path, observations) -> {
            Set<String> leaves = new TreeSet<>();
            observations.stream().filter(o -> o.accepted() && o.variant().linked().isEmpty())
                .forEach(o -> Wire.diff(reference.wire(), o.wire()).keySet().forEach(leaf -> leaves.add(Wire.generic(leaf))));
            moved.put(path, leaves);
        });
        return moved;
    }

    /**
     * The other fields the messages of a field's unlinked variants name ({@link Feedback#otherFields}).
     *
     * @param exceptTried without the ones BDS accepted a linked variant with: those were varied together
     */
    static List<String> linkedTo(Component component, String path, List<Obs> variants, boolean exceptTried) {
        Set<String> others = new TreeSet<>();
        for (Obs o : variants) {
            if (o.variant().linked().isEmpty()) {
                o.log().forEach(line -> others.addAll(Feedback.otherFields(component, path, o.variant().json(), Feedback.message(line))));
            }
        }
        if (exceptTried) {
            variants.stream().filter(Obs::accepted).forEach(o -> o.variant().linked().forEach(others::remove));
        }
        return List.copyOf(others);
    }
}
