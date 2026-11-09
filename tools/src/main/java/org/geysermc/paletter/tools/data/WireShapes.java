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
 * component ({@code a.b[].c}); a place that held different types is {@code any}. Compared with {@link ShapeDiff}, and
 * checked against the wire paths of the component model's {@code mappings.json}.
 */
public final class WireShapes {
    private static final String BLOCK_DEFINITION = "block:block_definition";

    private final Map<String, Map<String, String>> components = new TreeMap<>();

    public Map<String, Map<String, String>> components() {
        return components;
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
        VanillaData data = BundledFiles.read(file);
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

    private void addItem(NbtMap componentData) {
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

    private void addBlock(NbtMap properties) {
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
        if (value instanceof NbtMap map) {
            map.forEach((key, child) -> observe(component, path.isEmpty() ? key : path + "." + key, child));
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                observe(component, path + "[]", child);
            }
        }
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
     * Observed wire paths that the component model cannot produce: not a wire path of {@code mappings.json}, not below
     * one and not above one. Paths are checked as {@code component.path} (items and blocks share the namespace of the
     * mappings; the block definition's own fields are top level).
     *
     * @return {@code component path} lines, empty when everything is covered
     */
    public List<String> uncovered(JsonObject mappings) {
        Set<String> wire = new TreeSet<>();
        collectWire(mappings, wire);
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

    /** The uncovered paths that are known and accepted: {@code wire-gaps.txt} of the model directory. */
    public static Set<String> knownGaps(Path modelDirectory) throws IOException {
        Set<String> gaps = new TreeSet<>();
        Path file = modelDirectory.resolve("wire-gaps.txt");
        if (Files.exists(file)) {
            Files.readAllLines(file).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(gaps::add);
        }
        return gaps;
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
