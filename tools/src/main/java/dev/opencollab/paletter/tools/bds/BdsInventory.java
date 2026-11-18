package dev.opencollab.paletter.tools.bds;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.Versions;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code bds-inventory}: what one BDS build has, read from the strings of its Linux binary ({@code bedrock_server}) without
 * running it. Writes {@code data/bds/<version>/inventory.json}:
 * <ul>
 *     <li>{@code blockComponents}: the block component classes ({@code Block*Description} type names), each with its JSON
 *     name ({@code minecraft:friction}; null if the binary has no such string) and the JSON keys of the component that
 *     {@code data/model/mappings.json} knows (BDS accepted them in the spec capture) and that the binary contains;</li>
 *     <li>{@code itemComponents}: the item component classes ({@code *ItemComponent} type names);</li>
 *     <li>{@code scriptApi}: per Script API module namespace, every class with its bound members, from the type names of the
 *     {@code Scripting::Reflection} bindings. Names are the C++ ones ({@code ScriptBlock}, {@code get getId}); members bound
 *     through a lambda have no name in the binary and are only counted ({@code lambdaBindings}, all modules and the editor's).</li>
 * </ul>
 * {@code update} diffs it against the inventory of the previous build ({@link #diff}), so new or removed BDS properties show
 * up without anyone looking for them.
 */
public final class BdsInventory {
    public static final String FILE = "inventory.json";
    static final String BINARY = "bedrock_server";

    private static final Pattern BLOCK_COMPONENT = Pattern.compile("\\d+(Block\\w+Description)");
    private static final Pattern ITEM_COMPONENT = Pattern.compile("\\d+(\\w+ItemComponent)");
    private static final Pattern BINDING = Pattern.compile("N9Scripting10Reflection(\\d+)(\\w+)");
    private static final Pattern MODULE = Pattern.compile("\\d+ScriptModule");
    /** Strings that may be JSON names or keys: kept for lookups. */
    private static final Pattern WORD = Pattern.compile("[a-z0-9_:.]{3,80}");

    private BdsInventory() {
    }

    /** The strings of the binary that the inventory is made of. */
    record Strings(Set<String> words, Set<String> blockComponents, Set<String> itemComponents, Set<String> bindings) {
        static Strings of(Iterable<String> strings) {
            Strings result = new Strings(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
            strings.forEach(result::add);
            return result;
        }

        void add(String string) {
            if (WORD.matcher(string).matches()) {
                words.add(string);
            }
            Matcher block = BLOCK_COMPONENT.matcher(string);
            if (block.matches() && lengthPrefixed(string, block.group(1))) {
                blockComponents.add(block.group(1));
            }
            Matcher item = ITEM_COMPONENT.matcher(string);
            if (item.matches() && lengthPrefixed(string, item.group(1))) {
                itemComponents.add(item.group(1));
            }
            if (string.startsWith("N9Scripting10Reflection") && string.contains("ScriptModule")) {
                bindings.add(string);
            }
        }

        /** An RTTI type name is its length followed by the name: {@code 24BlockFrictionDescription}. */
        private static boolean lengthPrefixed(String string, String name) {
            return string.equals(name.length() + name);
        }
    }

    /**
     * Writes the inventory of a build, downloading its Linux zip into the cache if needed (the binary is read from the zip,
     * nothing is extracted).
     *
     * @return the file written
     */
    public static Path write(Path root, String version, Path cache) throws IOException, InterruptedException {
        JsonObject inventory = read(root, version, false, cache);
        Path file = root.resolve("data/bds").resolve(version).resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(inventory));
        System.out.println("Wrote " + file);
        return file;
    }

    /** @return the inventory of a build (see {@link #write}) */
    public static JsonObject read(Path root, String version, boolean preview, Path cache) throws IOException, InterruptedException {
        Path zip = BdsDistribution.resolve(true, preview, version).zip(cache == null ? BdsDistribution.defaultCache() : cache);
        Strings strings = new Strings(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        try (InputStream file = Files.newInputStream(zip); ZipInputStream input = new ZipInputStream(file)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null && !entry.getName().equals(BINARY)) {
                // skip to the binary
            }
            if (entry == null) {
                throw new IOException(zip + " has no " + BINARY);
            }
            scan(new BufferedInputStream(input, 1 << 16), strings::add);
        }
        JsonObject mappings = JsonParser.parseString(Files.readString(root.resolve("data/model/mappings.json"))).getAsJsonObject();
        return inventory(version, strings, mappings);
    }

    /** Calls {@code sink} with every run of at least 3 printable ASCII characters, as {@code strings} does. */
    static void scan(InputStream input, Consumer<String> sink) throws IOException {
        StringBuilder run = new StringBuilder();
        int b;
        while ((b = input.read()) >= 0) {
            if (b >= 0x20 && b <= 0x7e) {
                run.append((char) b);
                continue;
            }
            if (run.length() >= 3) {
                sink.accept(run.toString());
            }
            run.setLength(0);
        }
        if (run.length() >= 3) {
            sink.accept(run.toString());
        }
    }

    static JsonObject inventory(String version, Strings strings, JsonObject mappings) {
        JsonObject inventory = new JsonObject();
        inventory.addProperty("_generated", "Generated by `bds-inventory` (tools, BdsInventory) from the strings of the Linux "
            + BINARY + " of BDS " + version + " and the JSON keys of data/model/mappings.json. Do not edit: run the command again.");
        inventory.addProperty("bdsVersion", version);

        JsonObject blocks = new JsonObject();
        for (String type : strings.blockComponents()) {
            String name = "minecraft:" + snake(type.replaceFirst("^Block", "").replaceFirst("(ComponentDescription|Description)$", ""));
            boolean named = strings.words().contains(name);
            JsonObject component = new JsonObject();
            component.addProperty("name", named ? name : null);
            JsonArray keys = new JsonArray();
            if (named) {
                jsonKeys(mappings, "block:" + name).stream().filter(strings.words()::contains).forEach(keys::add);
            }
            component.add("keys", keys);
            blocks.add(type, component);
        }
        inventory.add("blockComponents", blocks);

        JsonArray items = new JsonArray();
        strings.itemComponents().forEach(items::add);
        inventory.add("itemComponents", items);

        Map<String, Map<String, Set<String>>> modules = new TreeMap<>();
        int lambdas = 0;
        for (String binding : strings.bindings()) {
            String[] member = binding(binding);
            if (member == null) {
                if (binding.matches("N9Scripting10Reflection\\d+Lambda.*")) {
                    lambdas++;
                }
                continue;
            }
            modules.computeIfAbsent(member[0], m -> new TreeMap<>()).computeIfAbsent(member[1], c -> new TreeSet<>()).add(member[2]);
        }
        JsonObject api = new JsonObject();
        modules.forEach((module, classes) -> {
            JsonObject json = new JsonObject();
            classes.forEach((type, members) -> {
                JsonArray array = new JsonArray();
                members.forEach(array::add);
                json.add(type, array);
            });
            api.add(module, json);
        });
        inventory.add("scriptApi", api);
        inventory.addProperty("lambdaBindings", lambdas);
        return inventory;
    }

    /** {@code DestructibleByMining} to {@code destructible_by_mining} */
    static String snake(String camel) {
        return camel.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    /** The JSON keys (last path segment) of the fields of a component in mappings.json that BDS did not reject as unknown. */
    private static Set<String> jsonKeys(JsonObject mappings, String component) {
        Set<String> keys = new TreeSet<>();
        JsonObject json = mappings.getAsJsonObject("components").getAsJsonObject(component);
        if (json == null || !json.has("fields")) {
            return keys;
        }
        for (Map.Entry<String, JsonElement> field : json.getAsJsonObject("fields").entrySet()) {
            JsonObject value = field.getValue().getAsJsonObject();
            if (!(value.has("unknownToBds") && value.get("unknownToBds").getAsBoolean())) {
                String[] path = field.getKey().split("\\.");
                keys.add(path[path.length - 1]);
            }
        }
        return keys;
    }

    /**
     * Reads a binding's type name, e.g. {@code N9Scripting10Reflection20MemberPropertyGetterIN21ScriptModuleMinecraft11ScriptBlockE...
     * XadL_ZNKS3_7isSolidEvEEEE}.
     *
     * @return {module, class, member} with the member as {@code name()}, {@code static name()}, {@code get name} or
     * {@code set name}; null for lambda bindings (no member name in the binary) and names this does not read
     */
    static String[] binding(String name) {
        Matcher kindMatcher = BINDING.matcher(name);
        if (!kindMatcher.lookingAt()) {
            return null;
        }
        String kind = kindMatcher.group(2).substring(0, Integer.parseInt(kindMatcher.group(1)));
        String prefix = switch (kind) {
            case "MemberFunction" -> "";
            case "StaticFunction" -> "static ";
            case "MemberPropertyGetter" -> "get ";
            case "MemberPropertySetter" -> "set ";
            default -> null;
        };
        if (prefix == null) {
            return null;
        }
        // The member follows the pointer-to-member marker: namespace, class and member in full, or a substitution for the
        // class that the binding named before (its first template argument) and the member
        int[] at = {name.indexOf("XadL_Z")};
        if (at[0] < 0) {
            return null;
        }
        at[0] += "XadL_Z".length();
        if (name.startsWith("N", at[0])) {
            at[0]++;
        }
        if (name.startsWith("K", at[0])) {
            at[0]++;
        }
        String moduleName;
        String type;
        if (name.startsWith("S", at[0])) {
            Matcher module = MODULE.matcher(name);
            if (!module.find() || module.start() > at[0]) {
                return null;
            }
            int[] classAt = {module.start()};
            moduleName = lengthPrefixed(name, classAt);
            type = lengthPrefixed(name, classAt);
            at[0] = name.indexOf('_', at[0]) + 1;
        } else {
            moduleName = lengthPrefixed(name, at);
            type = lengthPrefixed(name, at);
        }
        if (moduleName == null || type == null || !moduleName.startsWith("ScriptModule") || moduleName.equals("ScriptModule")) {
            // ScriptModule alone is the editor's namespace
            return null;
        }
        String member = lengthPrefixed(name, at);
        if (member == null) {
            return null;
        }
        boolean function = kind.endsWith("Function");
        return new String[]{moduleName, type, prefix + member + (function ? "()" : "")};
    }

    /** Reads {@code <length><name>} at {@code at[0]} and moves past it; null if there is none. */
    private static String lengthPrefixed(String name, int[] at) {
        int i = at[0];
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        if (i == at[0]) {
            return null;
        }
        int length = Integer.parseInt(name.substring(at[0], i));
        if (i + length > name.length()) {
            return null;
        }
        at[0] = i + length;
        return name.substring(i, i + length);
    }

    /**
     * @return what {@code after} has that {@code before} has not ({@code + ...}) and the reverse ({@code - ...}), one line
     * per block component, JSON key, item component and Script API member
     */
    public static List<String> diff(JsonObject before, JsonObject after) {
        Set<String> old = lines(before);
        Set<String> now = lines(after);
        List<String> diff = new ArrayList<>();
        now.stream().filter(line -> !old.contains(line)).forEach(line -> diff.add("+ " + line));
        old.stream().filter(line -> !now.contains(line)).forEach(line -> diff.add("- " + line));
        return diff;
    }

    static Set<String> lines(JsonObject inventory) {
        Set<String> lines = new TreeSet<>();
        inventory.getAsJsonObject("blockComponents").entrySet().forEach(entry -> {
            JsonObject component = entry.getValue().getAsJsonObject();
            String name = component.get("name").isJsonNull() ? entry.getKey() : component.get("name").getAsString();
            lines.add("block component " + name + " (" + entry.getKey() + ")");
            component.getAsJsonArray("keys").forEach(key -> lines.add("block component " + name + " key " + key.getAsString()));
        });
        inventory.getAsJsonArray("itemComponents").forEach(item -> lines.add("item component " + item.getAsString()));
        inventory.getAsJsonObject("scriptApi").entrySet().forEach(module -> module.getValue().getAsJsonObject().entrySet().forEach(type ->
            type.getValue().getAsJsonArray().forEach(member -> lines.add("Script API " + module.getKey() + " " + type.getKey() + " "
                + member.getAsString()))));
        return lines;
    }

    /** The newest inventory in {@code data/bds} of a build older than {@code version}, or null. */
    public static Path previous(Path bds, String version) throws IOException {
        if (!Files.isDirectory(bds)) {
            return null;
        }
        Path result = null;
        try (var dirs = Files.list(bds)) {
            for (Path dir : dirs.sorted((a, b) -> Versions.compare(a.getFileName().toString(), b.getFileName().toString())).toList()) {
                if (Versions.compare(dir.getFileName().toString(), version) < 0 && Files.isRegularFile(dir.resolve(FILE))) {
                    result = dir.resolve(FILE);
                }
            }
        }
        return result;
    }

    public static String json(JsonObject inventory) {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(inventory) + "\n";
    }

    public static JsonObject parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
