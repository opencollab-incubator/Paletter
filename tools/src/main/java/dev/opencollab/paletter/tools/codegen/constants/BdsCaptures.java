package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.Bundle;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.Sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the BDS captures in {@code data/bds/<build>} say: which current experiment toggle adds content in a release
 * ({@code experiments.json}), and what only Education Edition features offer ({@code education.json}).
 *
 * @param gatedItems release name to item identifier to the current experiment toggle that adds it in that release
 * @param gatedBlocks the same for blocks
 * @param educationItems the items BDS offers only to a world with Education Edition features
 * @param educationBlocks the same for blocks
 */
record BdsCaptures(Map<String, Map<String, String>> gatedItems, Map<String, Map<String, String>> gatedBlocks,
                   Set<String> educationItems, Set<String> educationBlocks) {
    /**
     * @param bundles the bundled versions, to tell which release a capture belongs to
     * @param bds the {@code data/bds} directory with the captures, or null for none
     */
    static BdsCaptures read(List<Bundle> bundles, Path bds) throws IOException {
        Map<String, Map<String, String>> gatedItems = new TreeMap<>();
        Map<String, Map<String, String>> gatedBlocks = new TreeMap<>();
        Set<String> educationItems = new TreeSet<>();
        Set<String> educationBlocks = new TreeSet<>();
        if (bds != null && Files.isDirectory(bds)) {
            List<Path> captures;
            try (var stream = Files.list(bds)) {
                captures = stream.filter(Files::isDirectory).sorted((a, b) -> compareBuilds(a.getFileName().toString(), b.getFileName().toString())).toList();
            }
            for (Path capture : captures) {
                Path experiments = capture.resolve("experiments.json");
                if (Files.exists(experiments)) {
                    JsonObject json = ConstantsGenerator.readJson(experiments);
                    // A capture of a preview says nothing of the release before it. The capture says whether it is one;
                    // without that, the bundle its protocol belongs to does
                    Bundle bundle = Sources.bundleOf(bundles, json.get("protocol").getAsInt());
                    Boolean flagged = previewFlag(capture);
                    boolean isPreview = flagged != null ? flagged : bundle != null && bundle.preview();
                    String release = bundle == null || isPreview ? null : bundle.name();
                    if (release != null) {
                        for (Map.Entry<String, JsonElement> toggle : json.getAsJsonObject("toggles").entrySet()) {
                            JsonObject value = toggle.getValue().getAsJsonObject();
                            if (value.get("status").getAsString().equals("current") && value.has("content")) {
                                JsonObject content = value.getAsJsonObject("content");
                                for (String item : strings(content, "itemsAdded", "scriptItemsAdded")) {
                                    gatedItems.computeIfAbsent(release, r -> new TreeMap<>()).putIfAbsent(item, toggle.getKey());
                                }
                                for (String block : strings(content, "blocksAdded", "dataDrivenBlocks")) {
                                    gatedBlocks.computeIfAbsent(release, r -> new TreeMap<>()).putIfAbsent(block, toggle.getKey());
                                }
                            }
                        }
                    }
                }
                Path education = capture.resolve("education.json");
                if (Files.exists(education)) {
                    JsonObject json = ConstantsGenerator.readJson(education);
                    educationItems.addAll(strings(json, "items"));
                    educationBlocks.addAll(strings(json, "blocks"));
                }
            }
        }
        return new BdsCaptures(gatedItems, gatedBlocks, educationItems, educationBlocks);
    }

        /** The {@code preview} flag of the capture's {@code spec/capture.json} or {@code capture.json}, or null if neither has one. */
        private static Boolean previewFlag(Path capture) throws IOException {
            for (Path file : List.of(capture.resolve("spec/capture.json"), capture.resolve("capture.json"))) {
                if (Files.exists(file) && ConstantsGenerator.readJson(file).has("preview")) {
                    return ConstantsGenerator.readJson(file).get("preview").getAsBoolean();
                }
            }
            return null;
        }

        /** Orders build names such as {@code 1.26.63.1} by their numbers, so {@code 1.26.100.2} is after it. */
        static int compareBuilds(String a, String b) {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            try {
                for (int i = 0; i < Math.min(x.length, y.length); i++) {
                    int compared = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                    if (compared != 0) {
                        return compared;
                    }
                }
            } catch (NumberFormatException e) {
                throw new IllegalStateException("The directory of a BDS capture is named " + a + " or " + b
                    + " in data/bds; expected a build number of dotted numbers such as 1.26.63.1", e);
            }
            return Integer.compare(x.length, y.length);
        }

        private static List<String> strings(JsonObject object, String... keys) {
            List<String> values = new ArrayList<>();
            for (String key : keys) {
                if (object.has(key)) {
                    object.getAsJsonArray(key).forEach(element -> values.add(element.getAsString()));
                }
            }
            return values;
        }
}
