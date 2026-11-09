package org.geysermc.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.codegen.constants.ConstantsGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * {@code experiment-marks}: the experiment marks of the bundled versions ({@code VanillaData#experiments()}, what content
 * is only there behind a toggle) from the experiment records of the BDS captures,
 * {@code data/bds/<bds version>/experiments.json}. A record belongs to the bundled version of its protocol; of several
 * records of one version the last (by directory name) counts. The marks are the same as the {@code update} pipeline writes
 * for a version it captured itself ({@link #marks}).
 */
public final class ExperimentMarks {
    private ExperimentMarks() {
    }

    /**
     * What the current toggles of a record add: items the toggle adds to the item registry or the Script API, changes, or
     * puts into the creative inventory for the first time; blocks it adds to the Script API or the data driven blocks; and
     * the creative stacks it adds.
     *
     * @param record a parsed {@code experiments.json}
     * @param creativeBefore the identifiers of the creative inventory of the version without any experiment
     * @return toggle to content, only toggles that add something
     */
    public static Map<String, ExperimentContent> marks(JsonObject record, Set<String> creativeBefore) {
        Map<String, ExperimentContent> marks = new TreeMap<>();
        for (Map.Entry<String, JsonElement> toggle : record.getAsJsonObject("toggles").entrySet()) {
            JsonObject value = toggle.getValue().getAsJsonObject();
            if (!value.get("status").getAsString().equals("current") || !value.has("content")) {
                continue;
            }
            JsonObject content = value.getAsJsonObject("content");
            TreeSet<String> items = new TreeSet<>();
            items.addAll(strings(content, "itemsAdded"));
            items.addAll(strings(content, "itemsChanged"));
            items.addAll(strings(content, "scriptItemsAdded"));
            List<String> creative = strings(content, "creativeAdded");
            for (String stack : creative) {
                String id = stack.contains(":") && stack.lastIndexOf(':') > stack.indexOf(':') ? stack.substring(0, stack.lastIndexOf(':')) : stack;
                if (!creativeBefore.contains(id)) {
                    items.add(id);
                }
            }
            TreeSet<String> blocks = new TreeSet<>();
            blocks.addAll(strings(content, "blocksAdded"));
            blocks.addAll(strings(content, "dataDrivenBlocks"));
            ExperimentContent mark = new ExperimentContent(List.copyOf(items), List.copyOf(blocks), creative);
            if (!mark.isEmpty()) {
                marks.put(toggle.getKey(), mark);
            }
        }
        return marks;
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (object.has(key)) {
            JsonArray array = object.getAsJsonArray(key);
            array.forEach(element -> values.add(element.getAsString()));
        }
        return values;
    }

    /**
     * Writes the marks of the records into the bundled version files that differ.
     *
     * @return the names of the versions that changed
     */
    public static List<String> apply(Path root) throws IOException {
        return collect(root, true);
    }

    /**
     * @return the names of the versions whose marks differ from what the records give, without writing anything
     */
    public static List<String> differing(Path root) throws IOException {
        return collect(root, false);
    }

    private static List<String> collect(Path root, boolean write) throws IOException {
        Path data = root.resolve(ConstantsGenerator.DATA);
        List<ConstantsGenerator.Bundle> releases = ConstantsGenerator.readIndex(data).stream().filter(b -> !b.preview()).toList();
        Map<String, JsonObject> records = new TreeMap<>();
        try (Stream<Path> captures = Files.list(root.resolve(Layout.BDS))) {
            for (Path capture : captures.filter(Files::isDirectory).sorted().toList()) {
                Path file = capture.resolve("experiments.json");
                if (Files.exists(file)) {
                    JsonObject record = ConstantsGenerator.readJson(file);
                    String release = ConstantsGenerator.Sources.releaseOf(releases, record.get("protocol").getAsInt());
                    if (release != null) {
                        records.put(release, record);
                    }
                }
            }
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : records.entrySet()) {
            Path file = data.resolve(entry.getKey() + ".nbt");
            byte[] before = Files.readAllBytes(file);
            VanillaData version;
            try (InputStream stream = Files.newInputStream(file)) {
                version = PaletteDataCodec.read(stream);
            }
            Set<String> creativeBefore = new TreeSet<>();
            version.creative().forEachStack(stack -> creativeBefore.add(stack.identifier()));
            Map<String, ExperimentContent> marks = marks(entry.getValue(), creativeBefore);
            if (new TreeMap<>(version.experiments()).equals(marks)) {
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PaletteDataCodec.write(version.withExperiments(marks), bytes);
            if (!Arrays.equals(before, bytes.toByteArray())) {
                if (write) {
                    Files.write(file, bytes.toByteArray());
                    System.out.println("Marks of " + entry.getKey() + ": " + marks.keySet());
                }
                changed.add(entry.getKey());
            }
        }
        return changed;
    }
}
