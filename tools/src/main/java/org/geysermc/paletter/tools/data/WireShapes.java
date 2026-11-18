package org.geysermc.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.tools.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What components look like on the wire: the NBT paths and types observed in the item component and block property data
 * of a capture or a bundled palette, per component ({@code item:minecraft:foo}, {@code block:minecraft:bar},
 * {@code block:block_definition} for the block property data around the components). Paths are relative to the
 * component ({@code a.b[].c}); a place that held different types is {@code any}. Compared with {@code ShapeDiff}, and
 * checked against the wire paths of the component model's {@code mappings.json}.
 */
public final class WireShapes {
    private static final String BLOCK_DEFINITION = "block:block_definition";

    private final Map<String, Map<String, String>> components = new TreeMap<>();
    private final Map<String, Map<String, Set<String>>> values = new TreeMap<>();

    public Map<String, Map<String, String>> components() {
        return components;
    }

    /** Per component and path the distinct leaf values observed ({@code 5}, {@code opaque}; lists and compounds have none of their own). */
    public Map<String, Map<String, Set<String>>> values() {
        return values;
    }

    /** The captured add-on data in {@code directory}: {@code custom_block_properties.nbt} and {@code custom_item_registry.nbt}. */
    public static WireShapes ofCapture(Path directory) throws IOException {
        WireShapes shapes = new WireShapes();
        for (String file : List.of("custom_block_properties.nbt", "custom_item_registry.nbt")) {
            Path path = directory.resolve(file);
            if (!Files.exists(path)) {
                continue;
            }
            NbtMap capture = Nbt.read(path);
            for (NbtMap item : capture.getList("items", NbtType.COMPOUND, List.of())) {
                // {name, id, componentBased, version, data: {components}}
                NbtMap data = item.containsKey("data") ? item.getCompound("data") : item;
                if (!data.isEmpty()) {
                    shapes.addItem(data);
                }
            }
            for (NbtMap block : capture.getList("blocks", NbtType.COMPOUND, List.of())) {
                // {name, properties: {block property data}}
                shapes.addBlock(block.containsKey("properties") && block.get("properties") instanceof NbtMap properties ? properties : block);
            }
        }
        return shapes;
    }

    /** The data driven items and blocks of a bundled palette file. */
    public static WireShapes ofPalette(Path file) throws IOException {
        return ofData(BundledFiles.read(file));
    }

    /** The data driven items and blocks of a version's vanilla data. */
    public static WireShapes ofData(VanillaData data) {
        WireShapes shapes = new WireShapes();
        for (VanillaItem item : data.items()) {
            if (item.componentBased() && item.componentData() != null) {
                shapes.addItem(item.componentData());
            }
        }
        for (BlockPropertyData block : data.dataDrivenBlocks()) {
            shapes.addBlock(block.getProperties());
        }
        return shapes;
    }

    public void addItem(NbtMap componentData) {
        NbtMap components = componentData.containsKey("components") ? componentData.getCompound("components") : componentData;
        components.forEach((name, value) -> {
            if (value instanceof NbtMap) {
                observe("item:" + name, "", value);
            } else if (name.equals("item_tags")) {
                // A bare list next to the components
                observe("item:item_tags", "", value);
            }
        });
    }

    public void addBlock(NbtMap properties) {
        Map<String, Object> definition = new TreeMap<>(properties);
        definition.remove("components");
        definition.remove("permutations");
        definition.remove("name");
        observe(BLOCK_DEFINITION, "", NbtMap.fromMap(definition));
        addBlockComponents(properties.getCompound("components", NbtMap.EMPTY));
        for (NbtMap permutation : properties.getList("permutations", NbtType.COMPOUND, List.of())) {
            addBlockComponents(permutation.getCompound("components", NbtMap.EMPTY));
        }
    }

    private void addBlockComponents(NbtMap components) {
        components.forEach((name, value) -> {
            // Legacy tag components (tag:...) have an empty value and carry no shape
            if (value instanceof NbtMap && !name.startsWith("tag:")) {
                observe("block:" + name, "", value);
            }
        });
    }

    private void observe(String component, String path, Object value) {
        Map<String, String> paths = components.computeIfAbsent(component, k -> new TreeMap<>());
        paths.merge(path, typeOf(value), (a, b) -> a.equals(b) ? a : "any");
        if (!(value instanceof NbtMap) && !(value instanceof List<?>)) {
            values.computeIfAbsent(component, k -> new TreeMap<>()).computeIfAbsent(path, k -> new TreeSet<>()).add(leaf(value));
        }
        if (value instanceof NbtMap map) {
            map.forEach((key, child) -> observe(component, path.isEmpty() ? key : path + "." + key, child));
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                observe(component, path + "[]", child);
            }
        }
    }

    private static String leaf(Object value) {
        return switch (value) {
            case byte[] a -> java.util.Arrays.toString(a);
            case int[] a -> java.util.Arrays.toString(a);
            case long[] a -> java.util.Arrays.toString(a);
            default -> String.valueOf(value);
        };
    }

    private static String typeOf(Object value) {
        return switch (value) {
            case Byte b -> "byte";
            case Boolean b -> "byte";
            case Short s -> "short";
            case Integer i -> "int";
            case Long l -> "long";
            case Float f -> "float";
            case Double d -> "double";
            case String s -> "string";
            case byte[] a -> "byte_array";
            case int[] a -> "int_array";
            case long[] a -> "long_array";
            case NbtMap m -> "compound";
            case List<?> l -> "list";
            default -> value.getClass().getSimpleName();
        };
    }

    /**
     * Observed wire paths that the component model cannot produce: not a wire path or envelope leaf of
     * {@code mappings.json}, not below one and not above one. Paths are checked as {@code component.path} (items and blocks share the namespace of the
     * mappings; the block definition's own fields are top level).
     *
     * @return {@code component path} lines, empty when everything is covered
     */
    public List<String> uncovered(JsonObject mappings) {
        Set<String> wire = new TreeSet<>();
        collectWire(mappings, wire);
        // What the generated envelope writes around the components of every item and block
        if (mappings.has("envelope")) {
            mappings.getAsJsonObject("envelope").entrySet().forEach(kind -> kind.getValue().getAsJsonArray()
                .forEach(window -> wire.addAll(window.getAsJsonObject().getAsJsonObject("leaves").keySet())));
        }
        List<String> uncovered = new ArrayList<>();
        components.forEach((component, paths) -> {
            String name = component.substring(component.indexOf(':') + 1);
            paths.keySet().forEach(path -> {
                String full = component.equals(BLOCK_DEFINITION) ? path
                    : name + (path.isEmpty() ? "" : path.startsWith("[") ? path : "." + path);
                if (!full.isEmpty() && !covered(full, wire)) {
                    uncovered.add(component + (path.isEmpty() ? "" : " " + path));
                }
            });
        });
        return uncovered;
    }

    /**
     * {@link #uncovered} and {@link #classify} for the spec capture of every build ({@code <bdsData>/<build>/spec}), oldest
     * build first: a new wire path of an older build is as much a gap as one of the newest.
     *
     * @return build -> reason -> paths, only builds with a spec capture and reasons with paths
     */
    public static Map<String, Map<String, List<String>>> uncoveredByBuild(Path bdsData, JsonObject mappings, Model model) throws IOException {
        Map<String, Map<String, List<String>>> out = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(bdsData)) {
            return out;
        }
        List<Path> builds;
        try (java.util.stream.Stream<Path> files = Files.list(bdsData)) {
            builds = files.filter(f -> Files.exists(f.resolve("spec/custom_item_registry.nbt")))
                .sorted(java.util.Comparator.comparing(f -> f.getFileName().toString(), org.geysermc.paletter.tools.Versions.ORDER)).toList();
        }
        for (Path build : builds) {
            Map<String, List<String>> classified = classify(ofCapture(build.resolve("spec")).uncovered(mappings), mappings, model);
            if (!classified.isEmpty()) {
                out.put(build.getFileName().toString(), classified);
            }
        }
        return out;
    }

    /** What {@link #classify} calls an uncovered path no rule explains: the report lists these for a human to look at. */
    public static final String UNCLASSIFIED = "unclassified";

    /** What {@link #classify} calls a path of a component the creator docs leave without fields. */
    public static final String UNDOCUMENTED = "the creator docs do not describe the component, so the model has no fields for it";

    /**
     * The lines {@code check-mappings --strict} fails on for one build's classified paths: the unclassified ones and
     * those of a component the docs do not describe (both are for a human to look at; coverage.md lists the rest, which
     * have a standing reason).
     */
    public static List<String> unexplained(String build, Map<String, List<String>> classified) {
        List<String> lines = new ArrayList<>();
        classified.getOrDefault(UNCLASSIFIED, List.of()).forEach(path -> lines.add("wire path " + path + " of " + build
            + " is not in mappings.json and no reason of WireShapes.classify fits"));
        classified.getOrDefault(UNDOCUMENTED, List.of()).forEach(path -> lines.add("wire path " + path + " of " + build
            + " belongs to a component the creator docs do not describe: document it or map it by hand"));
        return lines;
    }

    /**
     * What {@link #classify} calls the paths of a documented component that has no mappings at all: new in the schema (BDS
     * 1.26.63.1, block format 1.26.60: {@code minecraft:leashable} and {@code minecraft:sound} of blocks), so no inference has
     * mapped it yet. {@code update} checks the coverage before it infers; capture-only and verify runs do not infer.
     */
    public static final String NOT_INFERRED_YET = "a documented component the mappings have no entry for yet (new in the schema): "
        + "the mapping inference maps it, which capture-only and verify runs do not run";

    /**
     * Sorts the uncovered paths ({@link #uncovered}) by why the model does not cover them. The reasons:
     * <ul>
     *   <li>the block definition data around the components that is not in the envelope ({@code blockTags},
     *       {@code vanilla_block_data.block_id}, states): BlockCompiler writes it and the conformance suite checks it;</li>
     *   <li>a component the mappings call {@code untestable}: BDS rejected its companions, so nothing was inferred;</li>
     *   <li>a component without documented fields: the creator docs do not describe it, so the model has nothing to map;</li>
     *   <li>a documented component without any mappings: {@link #NOT_INFERRED_YET};</li>
     *   <li>an {@code item_properties} entry: BDS sends it for every item, no add-on JSON field sets it, ItemCompiler and
     *       WireOnlyWire write it.</li>
     * </ul>
     * Anything else is {@link #UNCLASSIFIED}.
     *
     * @return reason -> paths, in the order above, only reasons with paths
     */
    public static Map<String, List<String>> classify(List<String> uncovered, JsonObject mappings, Model model) {
        Set<String> documented = new TreeSet<>();
        model.components().stream().filter(c -> !c.fields().isEmpty()).forEach(c -> documented.add(c.id()));
        JsonObject components = mappings.getAsJsonObject("components");
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (String path : uncovered) {
            String component = path.contains(" ") ? path.substring(0, path.indexOf(' ')) : path;
            String reason;
            if (component.equals(BLOCK_DEFINITION)) {
                reason = "block definition data around the components: written by BlockCompiler, checked by the conformance suite";
            } else if (components.has(component) && untestable(components.getAsJsonObject(component))) {
                reason = "the component is untestable: BDS rejected its companions, so nothing was inferred";
            } else if (component.equals("item:item_properties")) {
                reason = "item_properties entry BDS sends for every item and no add-on JSON field sets: written by ItemCompiler and WireOnlyWire";
            } else if (!documented.contains(component)) {
                reason = UNDOCUMENTED;
            } else if (!components.has(component)) {
                reason = NOT_INFERRED_YET;
            } else {
                reason = UNCLASSIFIED;
            }
            out.computeIfAbsent(reason, k -> new ArrayList<>()).add(path);
        }
        return out;
    }

    /** Whether a window of the component's mappings is {@code untestable}. */
    private static boolean untestable(JsonObject mapping) {
        if (mapping.has("status") && mapping.get("status").getAsString().equals("untestable")) {
            return true;
        }
        return org.geysermc.paletter.tools.model.MappingWindows.of(mapping).stream()
            .anyMatch(window -> window != mapping && untestable(window));
    }

    private static boolean covered(String path, Set<String> wire) {
        String generic = path.replaceAll("\\[\\d+]", "[]");
        for (String w : wire) {
            String g = w.replaceAll("\\[\\d+]", "[]");
            if (within(generic, g) || within(g, generic)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code path} is {@code ancestor} or below it. */
    private static boolean within(String path, String ancestor) {
        return path.startsWith(ancestor) && (path.length() == ancestor.length()
            || path.charAt(ancestor.length()) == '.' || path.startsWith("[", ancestor.length()));
    }

    private static void collectWire(JsonElement element, Set<String> wire) {
        if (element instanceof JsonObject object) {
            object.entrySet().forEach(entry -> {
                if (entry.getKey().equals("wire") && entry.getValue().isJsonPrimitive()) {
                    wire.add(entry.getValue().getAsString());
                } else if (entry.getKey().equals("wire") && entry.getValue() instanceof JsonArray array) {
                    array.forEach(e -> wire.add(e.getAsString()));
                } else {
                    collectWire(entry.getValue(), wire);
                }
            });
        } else if (element instanceof JsonArray array) {
            array.forEach(e -> collectWire(e, wire));
        }
    }
}
