package org.geysermc.paletter.tools.model.docs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.Model.FormatNote;
import org.geysermc.paletter.tools.model.Model.JType;
import org.geysermc.paletter.tools.model.Model.Range;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses the item and block reference of Microsoft's creator docs (github.com/MicrosoftDocs/minecraft-creator,
 * {@code creator/Reference/Content}) into the component {@link Model}.
 * <p>
 * The component pages are generated from Mojang's schemas and follow one layout: notes (format version,
 * experiments), "Alternate Simple Representations", a root property table, and one section per nested type, linked
 * from the type column as {@code [Title](#anchor)}. Description fields, states, permutations and traits are on
 * hand written pages with plainer tables; they are parsed into pseudo components with the same field model.
 */
public final class DocsParser {
    public static final String CONTENT = "creator/Reference/Content/";
    public static final String ITEM_LIST = CONTENT + "ItemReference/Examples/ItemComponentList.md";
    public static final String BLOCK_LIST = CONTENT + "BlockReference/Examples/BlockComponents/BlockComponentsList.md";
    public static final String ITEM_DESCRIPTION = CONTENT + "ItemReference/Examples/ItemComponents/description_component.md";
    public static final String BLOCK_DESCRIPTION = CONTENT + "BlockReference/Examples/BlockDescription.md";
    public static final String STATES_PERMUTATIONS = CONTENT + "BlockReference/Examples/BlockStatesAndPermutations.md";
    public static final String TRAIT_LISTING = CONTENT + "BlockReference/Examples/BlockStateAndTraitListings.md";
    public static final String TRAIT_PAGES = CONTENT + "BlockReference/Examples/traits/";
    /** Item definition pages per format version; compared, not parsed into components. */
    public static final List<String> ITEM_DEFINITIONS = List.of(
        CONTENT + "ItemReference/Examples/ItemComponents/minecraft_item_v1_21_90.md",
        CONTENT + "ItemReference/Examples/ItemComponents/minecraft_item_v1_26_0.md",
        CONTENT + "ItemReference/Examples/ItemComponents/minecraft_item.md");

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\((#[^)]*)\\)");
    private static final Pattern PAGE_LINK = Pattern.compile("\\[([^\\]]*)]\\(\\./([^)]*\\.md)\\)");
    private static final Pattern MIN_FORMAT = Pattern.compile("(?i)requires a format version of at least (\\d+(?:\\.\\d+)+)");
    private static final Pattern VERSION = Pattern.compile("\\b(1\\.\\d{1,2}\\.\\d{1,3})\\b");
    private static final Pattern SHORTHAND = Pattern.compile("represented as an? `([^`]+)`");

    private final DocsSource docs;
    private final List<FormatNote> formatNotes = new ArrayList<>();

    public DocsParser(DocsSource docs) {
        this.docs = docs;
    }

    public Model parse() throws IOException {
        List<Component> components = new ArrayList<>();
        components.addAll(componentPages("item", ITEM_LIST, CONTENT + "ItemReference/Examples/ItemComponents/"));
        components.addAll(componentPages("block", BLOCK_LIST, CONTENT + "BlockReference/Examples/BlockComponents/"));
        components.add(description("item", ITEM_DESCRIPTION));
        components.add(description("block", BLOCK_DESCRIPTION));
        components.add(states());
        components.add(permutations());
        components.addAll(traits());
        components.sort(Comparator.comparing(Component::id));
        for (Component component : components) {
            collectFormatNotes(component);
        }
        itemDefinitionVersions();
        formatNotes.sort(Comparator.comparing((FormatNote n) -> versionKey(n.version())).thenComparing(FormatNote::component)
            .thenComparing(FormatNote::text));
        List<FormatNote> unique = new ArrayList<>(new LinkedHashSet<>(formatNotes));
        return new Model("https://github.com/MicrosoftDocs/minecraft-creator (" + CONTENT + ")", docs.commit(),
            "Derived from the Minecraft creator documentation by Microsoft, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/); "
                + "see codegen/model/ATTRIBUTION.md", components, unique);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Component pages

    private List<Component> componentPages(String kind, String listPage, String directory) throws IOException {
        Markdown list = docs.page(listPage);
        Map<String, String> status = new LinkedHashMap<>();
        String current = "active";
        for (Markdown.Section section : list.sections) {
            if (section.title.toLowerCase(Locale.ROOT).contains("deprecated")) {
                current = "deprecated";
            }
            for (String line : section.lines) {
                Matcher link = PAGE_LINK.matcher(line);
                if (link.find()) {
                    status.putIfAbsent(directory + link.group(2).replace("ItemComponents/", ""), current);
                }
            }
        }
        for (String page : docs.list(directory)) {
            status.putIfAbsent(page, "unlisted");
        }
        Map<String, Component> byName = new TreeMap<>();
        for (Map.Entry<String, String> entry : status.entrySet()) {
            String page = entry.getKey();
            String file = page.substring(page.lastIndexOf('/') + 1);
            if (!file.startsWith("minecraft") || ITEM_DEFINITIONS.contains(page) || file.contains("durability_threshold")
                || file.contains("kinetic_effect_conditions")) {
                continue;
            }
            Component component = componentPage(kind, page, entry.getValue());
            Component existing = byName.get(component.id());
            // a misspelled duplicate page (minecraft_hand_equippped.md) loses against the listed one
            if (existing == null || existing.status().equals("unlisted")) {
                byName.put(component.id(), component);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private Component componentPage(String kind, String page, String status) throws IOException {
        Markdown md = docs.page(page);
        Markdown.Section h1 = md.first(1);
        String file = page.substring(page.lastIndexOf('/') + 1).replace(".md", "");
        String name = "minecraft:" + file.replaceFirst("^minecraftBlock_", "").replaceFirst("^minecraft_", "");
        Matcher titled = Pattern.compile("(minecraft:[a-z_0-9]+)\\s*$").matcher(h1 == null ? "" : h1.title);
        if (titled.find() && !titled.group(1).startsWith("minecraft:item")) {
            name = titled.group(1);
        }
        List<String> notes = new ArrayList<>(h1 == null ? List.of() : h1.notes());
        String doc = h1 == null ? "" : h1.text();
        List<JType> forms = new ArrayList<>();
        Markdown.Section alternate = md.sections.stream().filter(s -> s.title.startsWith("Alternate Simple Representations")).findFirst().orElse(null);
        if (alternate != null) {
            Matcher m = SHORTHAND.matcher(String.join(" ", alternate.lines));
            while (m.find()) {
                forms.add(parseType(m.group(1)).type);
            }
        }
        Fields fields = new Fields();
        Markdown.Section root = md.sections.stream().filter(s -> s.level == 2 && s.title.endsWith("Properties")).findFirst().orElse(null);
        boolean object = false;
        if (root != null && !root.tables().isEmpty()) {
            for (Map<String, String> row : root.tables().get(0)) {
                String rowName = clean(row.getOrDefault("name", ""));
                if (rowName.startsWith("Using ")) {
                    // An alternative form of the whole value ("Using custom boxes": an array of boxes)
                    Parsed type = parseType(row.getOrDefault("type", ""));
                    JType form = new JType(type.type.type(), type.type.items(), type.type.ref(), rowName);
                    forms.add(form);
                    fields.add(md, "", form, row, type, this);
                    fields.remove("");
                } else {
                    object = true;
                }
            }
            if (object) {
                forms.add(0, JType.of("object"));
                fields.rows(md, "", root.tables().get(0).stream().filter(r -> !clean(r.getOrDefault("name", "")).startsWith("Using ")).toList(), this);
            }
        } else if (forms.isEmpty()) {
            forms.add(JType.of("object"));
        }
        fields.details(md);
        List<String> experiments = experiments(String.join(" ", notes) + " " + doc);
        if (status.equals("active") && doc.startsWith("Legacy component")) {
            status = "legacy";
        }
        return new Component(kind + ":" + name, kind, "component", name, status, doc, page, minFormat(String.join(" ", notes)),
            experiments, forms, fields.list(), notes);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pseudo components

    private Component description(String kind, String page) throws IOException {
        Markdown md = docs.page(page);
        Fields fields = new Fields();
        for (Markdown.Section section : md.sections) {
            if (section.tables().isEmpty()) {
                continue;
            }
            String title = section.title.toLowerCase(Locale.ROOT);
            if (title.equals("parameters")) {
                fields.rows(md, "", section.tables().get(0), this);
            } else if (title.startsWith("menu_category parameters")) {
                fields.rows(md, "menu_category", section.tables().get(0), this);
            }
        }
        // The item group list ("### group": one bullet per group key)
        Markdown.Section groups = md.sections.stream().filter(s -> s.title.equals("group")).findFirst().orElse(null);
        Field group = fields.get("menu_category.group");
        if (groups != null && group != null) {
            List<JsonElement> values = new ArrayList<>();
            groups.bullets().forEach(b -> values.add(new JsonPrimitive(b.text().replace("`", ""))));
            fields.replace(new Field(group.path(), List.of(JType.of("string")), group.defaultValue(), group.required(), values,
                group.range(), group.minFormatVersion(), group.examples(), group.doc()));
        }
        Markdown.Section h1 = md.first(1);
        return new Component(kind + ":description", kind, "description", "description", "active", h1 == null ? "" : h1.text(), page,
            null, List.of(), List.of(JType.of("object")), fields.list(), List.of());
    }

    private Component states() throws IOException {
        Markdown md = docs.page(STATES_PERMUTATIONS);
        Markdown.Section section = md.sections.stream().filter(s -> s.title.equals("Block States")).findFirst().orElseThrow();
        String text = section.text() + " " + String.join(" ", section.notes());
        // "State values can be Booleans, numbers, or strings." and the range object note
        List<JType> valueTypes = new ArrayList<>();
        if (text.contains("Booleans")) {
            valueTypes.add(new JType("array", JType.of("boolean"), null, null));
        }
        if (text.contains("numbers")) {
            valueTypes.add(new JType("array", JType.of("integer"), null, null));
        }
        if (text.contains("strings")) {
            valueTypes.add(new JType("array", JType.of("string"), null, null));
        }
        List<Field> fields = new ArrayList<>();
        boolean range = text.contains("\"values\":{\"min\"");
        if (range) {
            valueTypes.add(new JType("object", null, "range", null));
        }
        fields.add(new Field("{}", valueTypes, null, false, List.of(), null, null, List.of(), "All possible values of the state"));
        if (range) {
            fields.add(new Field("{}.values", List.of(JType.of("object")), null, true, List.of(), null, null, List.of(), "A range of integers"));
            fields.add(new Field("{}.values.min", List.of(JType.of("integer")), null, true, List.of(), null, null, List.of(), ""));
            fields.add(new Field("{}.values.max", List.of(JType.of("integer")), null, true, List.of(), null, null, List.of(), ""));
        }
        return new Component("block:states", "block", "states", "states", "active", section.text(), STATES_PERMUTATIONS, null,
            experiments(text), List.of(new JType("map", null, null, null)), fields, section.notes());
    }

    private Component permutations() throws IOException {
        Markdown md = docs.page(STATES_PERMUTATIONS);
        Markdown.Section section = md.sections.stream().filter(s -> s.title.equals("Block Permutations")).findFirst().orElseThrow();
        Markdown.Section parameters = md.sections.stream().filter(s -> s.title.equals("Block Permutation Parameters")).findFirst().orElseThrow();
        Fields fields = new Fields();
        fields.rows(md, "[]", parameters.tables().get(0), this);
        return new Component("block:permutations", "block", "permutations", "permutations", "active", section.text(), STATES_PERMUTATIONS,
            null, List.of(), List.of(new JType("array", JType.of("object"), null, null)), fields.list(), List.of());
    }

    private List<Component> traits() throws IOException {
        Markdown md = docs.page(TRAIT_LISTING);
        List<Component> traits = new ArrayList<>();
        for (Markdown.Section section : md.sections) {
            if (section.level != 3 || !section.title.replace("\\", "").startsWith("minecraft:")) {
                continue;
            }
            String name = section.title.replace("\\", "");
            List<Markdown.Section> subtree = md.subtree(section);
            Fields fields = new Fields();
            List<JsonElement> states = new ArrayList<>();
            for (Markdown.Section sub : subtree) {
                if (sub.title.toLowerCase(Locale.ROOT).contains("properties") && !sub.tables().isEmpty()) {
                    fields.rows(md, "", sub.tables().get(0), this);
                }
                if (sub.title.toLowerCase(Locale.ROOT).contains("states") && !sub.tables().isEmpty()) {
                    for (Map<String, String> row : sub.tables().get(0)) {
                        states.add(new JsonPrimitive(clean(row.getOrDefault("block state name", "")) + ": "
                            + row.getOrDefault("type", "") + " " + row.getOrDefault("valid values", "")));
                    }
                }
            }
            List<String> notes = new ArrayList<>();
            notes.add(section.text());
            states.forEach(s -> notes.add("adds state " + s.getAsString()));
            String trait = name.substring("minecraft:".length());
            String page = TRAIT_PAGES + trait + ".md";
            String minVersion = null;
            if (docs.exists(page)) {
                Markdown traitPage = docs.page(page);
                Markdown.Section h1 = traitPage.first(1);
                if (h1 != null) {
                    notes.addAll(h1.notes());
                }
            } else {
                page = TRAIT_LISTING;
            }
            String all = String.join(" ", notes);
            Matcher version = Pattern.compile("(?:requires (?:Minecraft )?(?:Bedrock )?(?:Edition |version )?|Minecraft version )(1\\.\\d+\\.\\d+)").matcher(all);
            if (version.find()) {
                minVersion = version.group(1);
            }
            traits.add(new Component("block:trait:" + name, "block", "trait", name, "active", section.text(), page, minVersion,
                experiments(all), List.of(JType.of("object")), fields.list(), notes));
        }
        return traits;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fields

    /** Fields of one component, merged by path (a field listed as several rows gets several types). */
    static final class Fields {
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private final Set<String> visiting = new LinkedHashSet<>();

        Field get(String path) {
            return fields.get(path);
        }

        void remove(String path) {
            fields.remove(path);
        }

        void replace(Field field) {
            fields.put(field.path(), field);
        }

        List<Field> list() {
            return new ArrayList<>(fields.values());
        }

        void rows(Markdown md, String prefix, List<Map<String, String>> rows, DocsParser parser) {
            for (Map<String, String> row : rows) {
                String cell = clean(row.getOrDefault("name", ""));
                if (cell.isEmpty()) {
                    continue;
                }
                Matcher qualified = Pattern.compile("^(\\S+?)\\s*\\((.*)\\)$").matcher(cell);
                String base = cell;
                String qualifier = null;
                if (qualified.matches()) {
                    base = qualified.group(1);
                    qualifier = qualified.group(2).trim();
                }
                base = base.trim().replace(' ', '_'); // "is hidden in commands" (BlockDescription.md)
                Parsed type = parseType(qualifier != null && qualifier.startsWith("as ") ? qualifier.substring(3) : row.getOrDefault("type", ""));
                String path = prefix.isEmpty() ? base : prefix + "." + base;
                add(md, path, type.type, row, type, parser);
            }
        }

        void add(Markdown md, String path, JType declared, Map<String, String> row, Parsed type, DocsParser parser) {
            String description = clean(row.getOrDefault("description", ""));
            String defaultCell = row.getOrDefault("default value", "").trim();
            JType jType = declared;
            List<JsonElement> enumValues = new ArrayList<>();
            Range range = range(description);
            // Default column holding a range ("0.0 - 360.0", trait tables)
            Matcher defaultRange = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(defaultCell);
            JsonElement defaultValue = null;
            if (defaultRange.matches()) {
                range = new Range(Double.parseDouble(defaultRange.group(1)), Double.parseDouble(defaultRange.group(2)), "default column: " + defaultCell);
            } else {
                defaultValue = parseDefault(defaultCell, jType);
            }
            // The parent goes before its nested fields
            fields.putIfAbsent(path, new Field(path, new ArrayList<>(List.of(jType)), defaultValue, false, new ArrayList<>(), range, null,
                List.of(), ""));
            boolean placeholder = fields.get(path).doc().isEmpty() && fields.get(path).types().size() == 1
                && fields.get(path).types().get(0).equals(jType) && fields.get(path).examples().isEmpty();
            // Nested types
            if (type.anchor != null) {
                Markdown.Section section = resolve(md, type.anchor);
                if (section != null) {
                    if (type.choices) {
                        enumValues.addAll(choices(md, section));
                    } else if (visiting.add(path)) {
                        List<Map<String, String>> nested = properties(md, section);
                        String prefix = switch (jType.type()) {
                            case "array" -> path.isEmpty() ? "[]" : path + "[]";
                            case "map" -> path.isEmpty() ? "{}" : path + "{}";
                            default -> path;
                        };
                        if (nested != null) {
                            rows(md, prefix, nested, parser);
                        }
                        visiting.remove(path);
                    }
                }
            }
            enumValues.addAll(valuesFrom(description));
            if (jType.type().equals("array") && jType.items() != null && jType.items().type().equals("any") && !enumValues.isEmpty()) {
                jType = new JType("array", JType.of(enumValues.get(0).getAsJsonPrimitive().isNumber() ? "number" : "string"), null, jType.label());
            }
            List<JsonElement> examples = examples(row.getOrDefault("example values", ""));
            boolean required = description.matches("(?is).*\\brequired\\b.*") && !description.matches("(?is).*\\b(not required|optional)\\b.*");
            Matcher fieldVersion = MIN_FORMAT.matcher(description);
            String minVersion = fieldVersion.find() ? fieldVersion.group(1) : null;

            Field existing = placeholder ? null : fields.get(path);
            // Primitive rows with an {} default ("states | {} | Integer number" + "states (as String)"): a map of values
            if (defaultValue != null && defaultValue.isJsonObject() && defaultValue.getAsJsonObject().isEmpty()
                && !List.of("object", "map", "any").contains(jType.type())) {
                jType = new JType("map", jType, null, jType.label());
            }
            if (existing == null) {
                fields.put(path, new Field(path, new ArrayList<>(List.of(jType)), defaultValue, required, dedupe(enumValues), range, minVersion,
                    examples, description));
            } else {
                List<JType> types = new ArrayList<>(existing.types());
                if (!types.contains(jType)) {
                    types.add(jType);
                }
                List<JsonElement> values = new ArrayList<>(existing.enumValues());
                values.addAll(enumValues);
                List<JsonElement> allExamples = new ArrayList<>(existing.examples());
                allExamples.addAll(examples);
                fields.put(path, new Field(path, types, existing.defaultValue() != null ? existing.defaultValue() : defaultValue,
                    existing.required() || required, dedupe(values), existing.range() != null ? existing.range() : range,
                    existing.minFormatVersion() != null ? existing.minFormatVersion() : minVersion, allExamples,
                    existing.doc().isEmpty() ? description : existing.doc()));
            }
        }

        /** Detail sections ("### uv_lock") add prose, value lists and format versions to the field of that name. */
        void details(Markdown md) {
            for (Markdown.Section section : md.sections) {
                if (section.level < 3 || !section.tables().isEmpty()) {
                    continue;
                }
                String text = section.text();
                for (Field field : list()) {
                    if (!field.name().equals(section.title)) {
                        continue;
                    }
                    List<JsonElement> values = new ArrayList<>(field.enumValues());
                    if (values.isEmpty() && !field.has("boolean") && !field.has("object")) {
                        values.addAll(valuesFrom(text));
                    }
                    Matcher version = MIN_FORMAT.matcher(text);
                    String minVersion = field.minFormatVersion() != null ? field.minFormatVersion() : version.find() ? version.group(1) : null;
                    String doc = text.isEmpty() || field.doc().contains(text) ? field.doc() : (field.doc() + " " + text).trim();
                    fields.put(field.path(), new Field(field.path(), field.types(), field.defaultValue(), field.required(), dedupe(values),
                        field.range() != null ? field.range() : range(text), minVersion, field.examples(), doc));
                }
            }
        }
    }

    /** The section of an anchor, following "Same structure as [X](#x)". */
    static Markdown.Section resolve(Markdown md, String anchor) {
        Markdown.Section section = md.anchor(anchor);
        for (int hops = 0; section != null && hops < 4; hops++) {
            if (properties(md, section) != null || !choicesOf(md, section).isEmpty()) {
                return section;
            }
            Matcher same = Pattern.compile("Same structure as \\[[^\\]]*]\\((#[^)]*)\\)").matcher(String.join(" ", section.lines));
            if (!same.find()) {
                return section;
            }
            section = md.anchor(same.group(1));
        }
        return section;
    }

    /** The first property table (a table with a name column) in the section or its subsections. */
    static List<Map<String, String>> properties(Markdown md, Markdown.Section section) {
        for (Markdown.Section sub : md.subtree(section)) {
            for (List<Map<String, String>> table : sub.tables()) {
                if (!table.isEmpty() && table.get(0).containsKey("name")) {
                    return table;
                }
            }
        }
        return null;
    }

    private static List<JsonElement> choices(Markdown md, Markdown.Section section) {
        return choicesOf(md, section);
    }

    private static List<JsonElement> choicesOf(Markdown md, Markdown.Section section) {
        List<JsonElement> values = new ArrayList<>();
        for (Markdown.Section sub : md.subtree(section)) {
            for (List<Map<String, String>> table : sub.tables()) {
                if (!table.isEmpty() && table.get(0).containsKey("value")) {
                    table.forEach(row -> values.add(literal(clean(row.get("value")))));
                    return values;
                }
            }
        }
        return values;
    }

    record Parsed(JType type, String anchor, boolean choices) {
    }

    static Parsed parseType(String cell) {
        String text = cell.trim();
        Matcher link = LINK.matcher(text);
        String title = null;
        String anchor = null;
        if (link.find()) {
            title = link.group(1);
            anchor = link.group(2);
        }
        String lower = LINK.matcher(text).replaceAll("[T]").toLowerCase(Locale.ROOT).replace("`", "").trim();
        JType type = switch (lower) {
            case "integer number", "integer" -> JType.of("integer");
            case "decimal number", "decimal" -> JType.of("number");
            case "string", "localization string", "enumerator" -> JType.of("string");
            case "boolean true/false", "boolean" -> JType.of("boolean");
            case "array of numbers" -> new JType("array", JType.of("number"), null, null);
            case "array of strings" -> new JType("array", JType.of("string"), null, null);
            case "array of objects" -> new JType("array", JType.of("object"), null, null);
            case "array", "list" -> new JType("array", JType.of("any"), null, null);
            case "x, y, z coordinate array" -> JType.of("vec3");
            case "keyed set of strings" -> new JType("map", JType.of("string"), null, null);
            case "keyed collection of boolean values" -> new JType("map", JType.of("boolean"), null, null);
            case "object", "json object" -> JType.of("object");
            case "minecraft event trigger" -> new JType("object", null, null, "event trigger");
            case "[t] item" -> new JType("object", null, title, null);
            case "array of [t] items" -> new JType("array", new JType("object", null, title, null), null, null);
            case "key/item pairs of [t] items" -> new JType("map", new JType("object", null, title, null), null, null);
            case "[t] choices" -> new JType("string", null, title, null);
            default -> new JType("any", null, null, text.isEmpty() ? null : text);
        };
        return new Parsed(type, anchor, lower.equals("[t] choices"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Values in prose

    static JsonElement parseDefault(String cell, JType type) {
        String text = cell.replace("`", "").trim();
        if (text.isEmpty() || text.equals("*not set*") || text.equals("--") || text.equals("*none*")) {
            return null;
        }
        try {
            JsonElement value = JsonParser.parseString(text);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !type.type().equals("string")) {
                return null;
            }
            if (value.isJsonPrimitive() && !value.getAsJsonPrimitive().isString() && text.contains(" ")) {
                return type.type().equals("string") ? new JsonPrimitive(text) : null;
            }
            return value;
        } catch (RuntimeException e) {
            return type.type().equals("string") || type.type().equals("any") ? new JsonPrimitive(text) : null;
        }
    }

    private static final Pattern VALUE_LIST = Pattern.compile(
        "(?i)(?:valid (?:\\(and required\\) )?values? (?:for this property )?(?:is|are)|options (?:are|include)|supported [a-z ]+? are|must be(?= \\[)|"
            + "allowed [a-z ]+? are\\s*:?|one of)\\s*:?\\s*(.*?)(?:\\. |\\.$|$)");

    public static List<JsonElement> valuesIn(String text) {
        return valuesFrom(text);
    }

    /** "Valid values are `a`, `b`", "Options are "a", "b"", "Must be [0.0, 90.0]": the listed values. */
    static List<JsonElement> valuesFrom(String text) {
        List<JsonElement> values = new ArrayList<>();
        Matcher list = VALUE_LIST.matcher(text);
        while (list.find()) {
            String part = list.group(1);
            Matcher code = Pattern.compile("`([^`]+)`").matcher(part);
            boolean any = false;
            while (code.find()) {
                any = true;
                for (String token : splitList(code.group(1))) {
                    values.add(literal(token));
                }
            }
            if (!any) {
                Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(part);
                while (quoted.find()) {
                    any = true;
                    values.add(new JsonPrimitive(quoted.group(1)));
                }
            }
            if (!any) {
                Matcher bracket = Pattern.compile("\\[([^\\]]+)]").matcher(part);
                if (bracket.find()) {
                    for (String token : splitList(bracket.group(1))) {
                        values.add(literal(token));
                    }
                }
            }
        }
        return dedupe(values);
    }

    private static List<String> splitList(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split(",")) {
            String t = token.trim().replaceAll("^\"|\"$", "");
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    static JsonElement literal(String token) {
        String t = token.trim();
        if (t.matches("-?\\d+")) {
            return new JsonPrimitive(Long.parseLong(t));
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            return new JsonPrimitive(Double.parseDouble(t));
        }
        if (t.equals("true") || t.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(t));
        }
        return new JsonPrimitive(t.replaceAll("^\"|\"$", ""));
    }

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?)";

    /** Numeric bounds stated in a description. */
    static Range range(String text) {
        Double min = null;
        Double max = null;
        Matcher m;
        if ((m = Pattern.compile("(?i)range of " + NUM + "\\s*(?:-|to)\\s*" + NUM).matcher(text)).find()
            || (m = Pattern.compile("(?i)between " + NUM + " and " + NUM).matcher(text)).find()
            || (m = Pattern.compile("(?i)(?:from|range) \\(?" + NUM + "\\)? to \\(?" + NUM + "\\)?").matcher(text)).find()) {
            min = Double.parseDouble(m.group(1));
            max = Double.parseDouble(m.group(2));
        }
        if ((m = Pattern.compile("(?i)(?:minimum|min) (?:value )?of " + NUM).matcher(text)).find()) {
            min = Double.parseDouble(m.group(1));
        }
        if ((m = Pattern.compile("(?i)(?:maximum|max) (?:value )?of " + NUM).matcher(text)).find()) {
            max = Double.parseDouble(m.group(1));
        }
        if ((m = Pattern.compile("(?i)clamped between " + NUM + " and (?:at most )?" + NUM).matcher(text)).find()) {
            min = Double.parseDouble(m.group(1));
            max = Double.parseDouble(m.group(2));
        }
        if (min == null && max == null) {
            return null;
        }
        return new Range(min, max, text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }

    /** "Name: `value`, Name: `value`": the backticked JSON values. */
    static List<JsonElement> examples(String cell) {
        List<JsonElement> values = new ArrayList<>();
        Matcher code = Pattern.compile("`([^`]+)`").matcher(cell);
        while (code.find()) {
            try {
                values.add(JsonParser.parseString(code.group(1)));
            } catch (RuntimeException e) {
                values.add(new JsonPrimitive(code.group(1)));
            }
        }
        return dedupe(values);
    }

    private static List<JsonElement> dedupe(List<JsonElement> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    static List<String> experiments(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?i)experimental toggles? required:?\\s*([A-Z][A-Za-z ]+?)(?:\\s*\\(|\\.|,|$)").matcher(text);
        while (m.find()) {
            names.add(m.group(1).trim());
        }
        m = Pattern.compile("\"?((?:[A-Z][A-Za-z]+ ?)+?)\"? experiment(?:al toggle)?\\b").matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.equals("The") && !name.equals("This")) {
                names.add(name);
            }
        }
        m = Pattern.compile("[Ee]xperimental [Tt]oggle \"([^\"]+)\"").matcher(text);
        while (m.find()) {
            names.add(m.group(1).trim());
        }
        if (text.contains("currently experimental") && names.isEmpty()) {
            names.add("experimental (toggle not named)");
        }
        return new ArrayList<>(names);
    }

    private static String minFormat(String text) {
        Matcher m = MIN_FORMAT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static String clean(String cell) {
        return cell == null ? "" : cell.replace("\\_", "_").replace("\\*", "*").replace("**", "").trim();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Format versions

    private void collectFormatNotes(Component component) {
        List<String> texts = new ArrayList<>(component.notes());
        texts.add(component.doc());
        component.fields().forEach(f -> texts.add(f.path() + ": " + f.doc()));
        for (String text : texts) {
            for (String sentence : text.split("(?<=[.!?])\\s+")) {
                if (!sentence.toLowerCase(Locale.ROOT).matches(".*(format[ _]version|version|released|added|fixed|onward|deprecated|before|requires).*")) {
                    continue;
                }
                Matcher version = VERSION.matcher(sentence);
                while (version.find()) {
                    formatNotes.add(new FormatNote(version.group(1), component.id(), sentence.trim()));
                }
            }
        }
    }

    /** The versioned item definition pages: which format versions the docs describe, and whether they differ. */
    private void itemDefinitionVersions() throws IOException {
        String previous = null;
        String previousPage = null;
        for (String page : ITEM_DEFINITIONS) {
            if (!docs.exists(page)) {
                continue;
            }
            Markdown md = docs.page(page);
            Markdown.Section h1 = md.first(1);
            StringBuilder body = new StringBuilder();
            md.sections.stream().filter(s -> s.level > 1).forEach(s -> body.append(s.level).append(' ').append(s.title.replaceAll("\\d+\\.\\d+", "V"))
                .append('\n').append(String.join("\n", s.lines)).append('\n'));
            String version = page.contains("_v1_") ? page.replaceAll(".*_v(\\d+)_(\\d+)_(\\d+)\\.md", "$1.$2.$3") : "current";
            String note = "Item definition page " + page.substring(page.lastIndexOf('/') + 1) + " (" + (h1 == null ? "" : h1.title) + ")"
                + (previous == null ? "" : previous.contentEquals(body) ? ": identical to " + previousPage : ": differs from " + previousPage);
            formatNotes.add(new FormatNote(version.equals("current") ? "current" : version, "item:definition", note));
            previous = body.toString();
            previousPage = page.substring(page.lastIndexOf('/') + 1);
        }
    }

    static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            key.append(String.format("%06d", part.matches("\\d+") ? Integer.parseInt(part) : 999999));
        }
        return key.toString();
    }

    /** Pages of a docs checkout, or of a cached download of a pinned commit. */
    public interface DocsSource {
        String commit();

        Markdown page(String path) throws IOException;

        boolean exists(String path);

        /** Markdown pages directly in a directory, sorted. */
        List<String> list(String directory) throws IOException;

        static DocsSource directory(Path root, String commit) {
            return new DocsSource() {
                @Override
                public String commit() {
                    return commit;
                }

                @Override
                public Markdown page(String path) throws IOException {
                    return new Markdown(path, Files.readString(root.resolve(path), StandardCharsets.UTF_8));
                }

                @Override
                public boolean exists(String path) {
                    return Files.exists(root.resolve(path));
                }

                @Override
                public List<String> list(String directory) throws IOException {
                    try (Stream<Path> files = Files.list(root.resolve(directory))) {
                        return files.filter(f -> f.toString().endsWith(".md")).map(f -> directory + f.getFileName()).sorted().toList();
                    }
                }
            };
        }
    }
}
