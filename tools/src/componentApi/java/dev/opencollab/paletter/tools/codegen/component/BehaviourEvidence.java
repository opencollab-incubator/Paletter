package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Key;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Leaf;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Owner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * What the newest BDS captures under {@code data/bds} have, against which the sources of {@link BehaviourKeys} are checked:
 * the properties of the behaviour dump ({@code behaviour-dump.json.gz}) and those its world check found world-dependent,
 * the members of the behaviour probe capture, and the block and item components of the inventory ({@code inventory.json}).
 *
 * @param dump where the dump was read from, or null if there is none
 * @param blockProperties the block properties of the dump: without a world and placed
 * @param itemProperties the item properties of the dump
 * @param worldDependent the world-dependent properties, each with the blocks that showed it
 * @param probe the members of the probe capture
 * @param blockComponents the JSON names of the block components BDS has
 * @param itemComponents the class names of the item components BDS has
 * @param nativeCapture whether a native capture exists ({@code native.json}); none does yet
 */
record BehaviourEvidence(String dump, Set<String> blockProperties, Set<String> itemProperties, Map<String, Set<String>> worldDependent,
                         String probeReference, Set<String> probe, String inventory, Set<String> blockComponents, Set<String> itemComponents,
                         boolean nativeCapture) {

    static BehaviourEvidence read(Path root) throws IOException {
        Set<String> blocks = new TreeSet<>();
        Set<String> items = new TreeSet<>();
        Map<String, Set<String>> dependent = new TreeMap<>();
        Path dumpFile = newest(root, "behaviour-dump.json.gz");
        if (dumpFile != null) {
            JsonObject dump;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(dumpFile))) {
                dump = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            blocks.addAll(dump.getAsJsonObject("permutation").keySet());
            blocks.addAll(dump.getAsJsonObject("placed").keySet());
            dump.getAsJsonObject("items").entrySet().forEach(item -> items.addAll(item.getValue().getAsJsonObject().keySet()));
            for (Map.Entry<String, JsonElement> property : dump.getAsJsonObject("world").getAsJsonObject("dependent").entrySet()) {
                Set<String> by = new TreeSet<>();
                property.getValue().getAsJsonObject().getAsJsonArray("blocks").forEach(block -> by.add(block.getAsString()));
                dependent.put(property.getKey(), by);
            }
        }
        Path probeFile = newest(root, "behaviour-probe/capture.json");
        Set<String> probe = probeFile == null ? Set.of()
            : JsonParser.parseString(Files.readString(probeFile, StandardCharsets.UTF_8)).getAsJsonObject().keySet();
        Set<String> blockComponents = new TreeSet<>();
        Set<String> itemComponents = new TreeSet<>();
        Path inventoryFile = newest(root, "inventory.json");
        if (inventoryFile != null) {
            JsonObject inventory = JsonParser.parseString(Files.readString(inventoryFile, StandardCharsets.UTF_8)).getAsJsonObject();
            inventory.getAsJsonObject("blockComponents").entrySet().forEach(component -> {
                JsonElement name = component.getValue().getAsJsonObject().get("name");
                if (!name.isJsonNull()) {
                    blockComponents.add(name.getAsString());
                }
            });
            inventory.getAsJsonArray("itemComponents").forEach(component -> itemComponents.add(component.getAsString()));
        }
        return new BehaviourEvidence(relative(root, dumpFile), blocks, items, dependent, relative(root, probeFile), probe,
            relative(root, inventoryFile), blockComponents, itemComponents, newest(root, "native.json") != null);
    }

    private static String relative(Path root, Path file) {
        return file == null ? null : root.relativize(file).toString().replace('\\', '/');
    }

    /** @return the file of the newest BDS build under {@code data/bds} that has it, or null */
    static Path newest(Path root, String file) throws IOException {
        Path bds = root.resolve("data/bds");
        if (!Files.isDirectory(bds)) {
            return null;
        }
        try (Stream<Path> builds = Files.list(bds)) {
            return builds.filter(build -> build.getFileName().toString().matches("[0-9.]+")).map(build -> build.resolve(file))
                .filter(Files::isRegularFile)
                .max(Comparator.comparing(found -> root.resolve("data/bds").relativize(found).getName(0).toString(), Main::compareVersions))
                .orElse(null);
        }
    }

    /**
     * @return what is wrong with the keys against the captures: sources that are not in their capture, world-dependent
     * properties used as the main source, properties of the dump that are neither a source nor ignored, ignored entries the
     * dump does not have, wire components BDS does not have
     */
    List<String> problems(List<Key> keys, Map<String, String> ignored) {
        List<String> problems = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        for (Key key : keys) {
            String where = key.owner().name().toLowerCase(Locale.ROOT) + ":" + key.name();
            if (key.sources().isEmpty()) {
                problems.add(where + ": no BDS source");
            }
            Set<String> dumped = key.owner() == Owner.BLOCK ? blockProperties : itemProperties;
            for (int i = 0; i < key.sources().size(); i++) {
                String source = key.sources().get(i);
                if (source.startsWith("SAPI:")) {
                    for (String property : BehaviourKeys.expand(source.substring(5))) {
                        used.add(property);
                        if (dump != null && !dumped.contains(property)) {
                            problems.add(where + ": " + property + " is not in " + dump);
                        }
                        if (i == 0 && worldDependent.containsKey(property)) {
                            problems.add(where + ": " + property + " is world-dependent (" + dump + ", world check); it can only fill in after another source");
                        }
                    }
                } else if (source.startsWith("PROBE:")) {
                    if (!probe.contains(source.substring(6))) {
                        problems.add(where + ": " + source + " is not in " + probeReference);
                    }
                } else if (!source.startsWith("NATIVE:")) {
                    problems.add(where + ": unknown source " + source);
                }
                // NATIVE sources are checked against the native capture once one exists
            }
            if (inventory != null && key.wire() != null) {
                for (String component : key.wire().components()) {
                    boolean known = key.owner() == Owner.BLOCK ? blockComponents.contains(component)
                        : itemComponents.contains(itemComponentClass(component));
                    if (!known) {
                        problems.add(where + ": BDS has no component " + component + " (" + inventory + ")");
                    }
                }
            }
            if (key.wire() instanceof Leaf leaf && leaf.component().isEmpty()) {
                problems.add(where + ": a wire leaf without a component");
            }
        }
        if (dump == null) {
            return problems;
        }
        Set<String> listed = new TreeSet<>();
        for (Map.Entry<String, String> entry : ignored.entrySet()) {
            if (!entry.getKey().startsWith("SAPI:") || entry.getValue().isBlank()) {
                problems.add("ignored " + entry.getKey() + ": an ignored entry is a SAPI property with a reason");
                continue;
            }
            for (String property : BehaviourKeys.expand(entry.getKey().substring(5))) {
                listed.add(property);
                if (!blockProperties.contains(property) && !itemProperties.contains(property)) {
                    problems.add("ignored " + property + " is not in " + dump + ": remove it");
                }
                if (used.contains(property)) {
                    problems.add("ignored " + property + " is the source of a key");
                }
                if (worldDependent.containsKey(property) && !entry.getValue().startsWith("world-dependent")) {
                    problems.add("ignored " + property + " is world-dependent (" + dump + "): say so in its reason");
                }
            }
        }
        Set<String> all = new TreeSet<>(blockProperties);
        all.addAll(itemProperties);
        for (String property : all) {
            if (!used.contains(property) && !listed.contains(property)) {
                problems.add(property + " (" + dump + ") is neither the source of a key nor ignored: add a key or an IGNORED entry with the reason");
            }
        }
        return problems;
    }

    /** {@code minecraft:max_stack_size} to {@code MaxStackSizeItemComponent}, the class BDS reads the item component into */
    static String itemComponentClass(String component) {
        StringBuilder name = new StringBuilder();
        for (String part : component.substring(component.indexOf(':') + 1).split("_")) {
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name + "ItemComponent";
    }
}
