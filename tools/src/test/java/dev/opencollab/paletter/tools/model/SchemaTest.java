package dev.opencollab.paletter.tools.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.Model.Component;
import dev.opencollab.paletter.tools.model.Model.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema step on a fake docs tree shaped like BDS's: the extract follows {@code $ref}s from the entry points (whatever
 * the files are called), and the merge puts the schema before the docs and the content log.
 */
class SchemaTest {
    @TempDir
    Path docs;

    private void file(String path, String json) throws IOException {
        Path file = docs.resolve("json_schemas/server").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    /** A stable item format with {@code minecraft:test} (members by reference), the beta one with a component of its own. */
    private JsonObject extract() throws IOException {
        String header = "\"$schema\": \"http://json-schema.org/draft-07/schema#\", \"x-format-version\": \"x\", ";
        file("item/1.26.30/Item Components.json", "{" + header + "\"properties\": {\"minecraft:test\": {\"$ref\": \"../1.20.50/minecraft_test.json\"},"
            + "\"minecraft:old\": {\"deprecated\": true, \"type\": \"boolean\"}, \"minecraft:same\": {\"type\": \"boolean\"}}}");
        file("item/1.26.50/Item Components.json", "{" + header + "\"properties\": {\"minecraft:test\": {\"$ref\": \"../1.20.50/minecraft_test.json\"},"
            + "\"minecraft:same\": {\"type\": \"boolean\"}}}");
        file("item/beta/Item Components (Beta).json", "{" + header + "\"properties\": {\"minecraft:test\": {\"$ref\": \"../1.20.50/minecraft_test.json\"},"
            + "\"minecraft:same\": {\"type\": \"boolean\"}, \"minecraft:newer\": {\"type\": \"boolean\"}}}");
        // A byte order mark, a URL-encoded name, an enum behind a reference, a limit next to a reference, a list of enum values, a map
        file("item/1.20.50/minecraft_test.json", "\uFEFF{\"type\": \"object\", \"properties\": {"
            + "\"power\": {\"type\": \"integer\", \"minimum\": 0.0, \"maximum\": 15.0, \"default\": 3, \"x-underlying-type\": \"uint8\"},"
            + "\"kind\": {\"default\": \"water\", \"$ref\": \"../../common/Liquid%20Type.json\"},"
            + "\"faces\": {\"type\": \"array\", \"minItems\": 1, \"maxItems\": 6, \"items\": {\"$ref\": \"../../common/Liquid%20Type.json\"}},"
            + "\"bones\": {\"type\": \"object\", \"additionalProperties\": {\"oneOf\": [{\"type\": \"boolean\"}, {\"type\": \"string\"}]}},"
            + "\"rule\": {\"oneOf\": [{\"type\": \"boolean\"}, {\"type\": \"object\", \"properties\": {\"n\": {\"type\": \"number\"}}, \"required\": [\"n\"]}],"
            + "\"x-runtime-constraint-description\": \"n must be positive\"}},"
            + "\"required\": [\"power\"]}");
        file("common/Liquid Type.json", "{\"type\": \"string\", \"enum\": [\"water\", \"lava\"]}");
        file("block/1.26.20/Traits.json", "{\"properties\": {\"minecraft:placement\": {\"type\": \"object\", \"properties\": {\"n\": {\"type\": \"integer\"}}}}}");
        return SchemaExtract.extract(docs, "1.26.99.1");
    }

    private static JsonObject definition(JsonObject schema, String kind, String name, int index) {
        return schema.getAsJsonObject(kind).getAsJsonArray(name).get(index).getAsJsonObject();
    }

    @Test
    void theExtractFollowsReferencesAndReadsTheFacets() throws IOException {
        JsonObject schema = extract();
        assertEquals("1.26.99.1", schema.get("bdsVersion").getAsString());
        assertTrue(schema.get("_generated").getAsString().contains("Do not edit"));
        assertEquals("[\"1.26.30\",\"1.26.50\",\"beta\"]", schema.getAsJsonObject("formats").get("item").toString());
        assertEquals("[\"1.26.20\"]", schema.getAsJsonObject("formats").get("trait").toString());

        // The same definition in every format is written once, with its formats
        JsonObject test = definition(schema, "item", "minecraft:test", 0);
        assertEquals("[\"1.26.30\",\"1.26.50\",\"beta\"]", test.getAsJsonArray("formats").toString());
        JsonObject fields = test.getAsJsonObject("fields");
        assertEquals("{\"types\":[\"integer\"],\"minimum\":0.0,\"maximum\":15.0,\"default\":3,\"underlyingType\":\"uint8\",\"required\":true}",
            fields.get("power").toString());
        assertEquals("[\"water\",\"lava\"]", fields.getAsJsonObject("kind").get("enum").toString());
        assertEquals("water", fields.getAsJsonObject("kind").get("default").getAsString());
        assertEquals("[\"water\",\"lava\"]", fields.getAsJsonObject("faces[]").get("enum").toString());
        assertEquals(6, fields.getAsJsonObject("faces").get("maxItems").getAsInt());
        assertEquals("[\"boolean\",\"string\"]", fields.getAsJsonObject("bones{}").get("types").toString());
        // Alternatives are merged into one path; a requirement of one of them counts
        assertEquals("[\"boolean\",\"object\"]", fields.getAsJsonObject("rule").get("types").toString());
        assertTrue(fields.getAsJsonObject("rule.n").get("required").getAsBoolean());
        assertEquals("n must be positive", fields.getAsJsonObject("rule").get("runtimeConstraint").getAsString());

        // Components that only differ in a format get a definition each; a deprecated one says so
        assertTrue(definition(schema, "item", "minecraft:old", 0).getAsJsonObject("root").get("deprecated").getAsBoolean());
        assertEquals(1, schema.getAsJsonObject("item").getAsJsonArray("minecraft:same").size());
        assertEquals("[\"beta\"]", definition(schema, "item", "minecraft:newer", 0).getAsJsonArray("formats").toString());
        assertNotNull(definition(schema, "trait", "minecraft:placement", 0).getAsJsonObject("fields").get("n"));
    }

    @Test
    void aReferenceToNowhereSkipsOnlyItsComponent() throws IOException {
        file("item/1.26.30/Item Components.json", "{\"properties\": {\"minecraft:test\": {\"$ref\": \"./gone.json\"}, \"minecraft:fine\": {\"type\": \"boolean\"}}}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        assertNotNull(schema.getAsJsonObject("item").get("minecraft:fine"));
        assertNull(schema.getAsJsonObject("item").get("minecraft:test"));
        // The message names the file relative to the docs: the same on every machine
        assertEquals("[\"item 1.26.30 minecraft:test: the $ref ./gone.json in item/1.26.30/Item Components.json points to a file that is not there\"]",
            schema.getAsJsonArray("skipped").toString());
        assertEquals(schema.getAsJsonArray("skipped").size(), Schema.of(schema).skipped().size());
        // No component schema at all is not a schema
        assertThrows(IOException.class, () -> SchemaExtract.extract(docs.resolve("nowhere"), "1.26.99.1"));
    }

    /**
     * Fixture: BDS docgen 1.26.36.1, {@code json_schemas/server/block_components/1.26.20/Block Components.json} (cut to two
     * components). That build writes every block component into this one file as {@code definitions} and refers to them with
     * {@code #/definitions/<id>}; later builds use a file per component.
     */
    @Test
    void aReferenceToADefinitionOfTheSameFileIsFollowed() throws IOException {
        file("block_components/1.26.20/Block Components.json", """
            {"$schema": "http://json-schema.org/draft-07/schema#", "$id": "2556852513", "x-format-version": "1.26.20", "x-minecraft-version": "1.26.36",
             "definitions": {
              "202983454": {"title": "Detailed Flammable", "oneOf": [{"type": "boolean"}, {"$ref": "#/definitions/545187925"}]},
              "545187925": {"title": "Detailed Flammable", "type": "object", "properties": {
                "catch_chance_modifier": {"type": "integer", "default": 5, "minimum": 0.0},
                "lava_flammable": {"title": "Lava Flammable", "type": "string", "default": "never", "enum": ["always", "never"]}}},
              "1514119701": {"title": "Friction", "type": "number", "minimum": 0.0, "maximum": 0.8999999761581421}},
             "properties": {"minecraft:flammable": {"$ref": "#/definitions/202983454"}, "minecraft:friction": {"$ref": "#/definitions/1514119701"}}}
            """);
        JsonObject schema = SchemaExtract.extract(docs, "1.26.36.1");
        assertNull(schema.get("skipped"));
        JsonObject friction = definition(schema, "block", "minecraft:friction", 0);
        assertEquals("{\"types\":[\"number\"],\"minimum\":0.0,\"maximum\":0.8999999761581421}", friction.getAsJsonObject("root").toString());
        JsonObject flammable = definition(schema, "block", "minecraft:flammable", 0);
        assertEquals("[\"boolean\",\"object\"]", flammable.getAsJsonObject("root").get("types").toString());
        assertEquals("[\"always\",\"never\"]", flammable.getAsJsonObject("fields").getAsJsonObject("lava_flammable").get("enum").toString());
        assertEquals(5, flammable.getAsJsonObject("fields").getAsJsonObject("catch_chance_modifier").get("default").getAsInt());
    }

    @Test
    void aReferenceToADefinitionThatIsNotThereSkipsOnlyItsComponent() throws IOException {
        file("item/1.26.30/Item Components.json", "{\"definitions\": {\"1\": {\"type\": \"boolean\"}}, \"properties\": {"
            + "\"minecraft:test\": {\"$ref\": \"#/definitions/2\"}, \"minecraft:fine\": {\"$ref\": \"#/definitions/1\"}}}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        assertNotNull(schema.getAsJsonObject("item").get("minecraft:fine"));
        assertNull(schema.getAsJsonObject("item").get("minecraft:test"));
        assertEquals("[\"item 1.26.30 minecraft:test: the $ref #/definitions/2 in item/1.26.30/Item Components.json points to a definition that is not there\"]",
            schema.getAsJsonArray("skipped").toString());
    }

    @Test
    void aRecursiveTypeEndsAndATooDeepOneIsSkipped() throws IOException {
        file("item/1.26.30/rec.json", "{\"type\": \"object\", \"properties\": {\"child\": {\"$ref\": \"./rec.json\"}, \"n\": {\"type\": \"integer\"}}}");
        String deep = "{\"type\": \"boolean\"}";
        for (int i = 0; i < 20; i++) {
            deep = "{\"properties\": {\"x\": " + deep + "}}";
        }
        file("item/1.26.30/Item Components.json", "{\"properties\": {\"minecraft:rec\": {\"$ref\": \"./rec.json\"}, \"minecraft:deep\": " + deep + "}}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        JsonObject fields = definition(schema, "item", "minecraft:rec", 0).getAsJsonObject("fields");
        assertTrue(fields.has("child") && fields.has("n"));
        assertNull(schema.getAsJsonObject("item").get("minecraft:deep"));
        assertTrue(schema.getAsJsonArray("skipped").get(0).getAsString().contains("nesting deeper than"), schema.getAsJsonArray("skipped").toString());
    }

    @Test
    void alternativesAreCombinedPerPath() throws IOException {
        file("item/1.26.30/Item Components.json", "{\"properties\": {\"minecraft:alt\": {\"$ref\": \"./alt.json\"}}}");
        file("item/1.26.30/alt.json", "{\"not\": {}, \"oneOf\": ["
            + "{\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"integer\", \"minimum\": 0.0, \"maximum\": 5.0, \"default\": 1}, \"b\": {\"type\": \"string\"}},"
            + " \"required\": [\"a\", \"b\"]},"
            + "{\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"integer\", \"minimum\": 10.0, \"default\": 12}, \"c\": {\"type\": \"string\", \"enum\": [\"x\"]}},"
            + " \"required\": [\"a\"]},"
            + "{\"type\": \"string\", \"enum\": [\"s\", \"t\"]}]}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        JsonObject definition = definition(schema, "item", "minecraft:alt", 0);
        JsonObject fields = definition.getAsJsonObject("fields");
        // Required where every object alternative requires it; in some where only some do
        assertTrue(fields.getAsJsonObject("a").has("required"));
        assertFalse(fields.getAsJsonObject("b").has("required"));
        assertTrue(fields.getAsJsonObject("b").get("requiredInSome").getAsBoolean());
        assertFalse(fields.getAsJsonObject("c").has("required") || fields.getAsJsonObject("c").has("requiredInSome"));
        // Limits and default of one alternative together, not minimum of one and maximum of another
        assertEquals("{\"types\":[\"integer\"],\"minimum\":0.0,\"maximum\":5.0,\"default\":1,\"required\":true}", fields.get("a").toString());
        // The enum of the string alternative says so; the component takes either form
        JsonObject root = definition.getAsJsonObject("root");
        assertEquals("[\"object\",\"string\"]", root.get("types").toString());
        assertEquals("[\"s\",\"t\"]", root.get("enum").toString());
        assertEquals("[\"string\"]", root.get("enumTypes").toString());
        // A keyword the extract does not know is listed
        assertEquals(List.of("not"), Schema.of(schema).unhandledKeywords());
    }

    @Test
    void whatTheDocumentForbidsIsDeprecated() throws IOException {
        file("block_components/1.26.60/Block Components.json", "{\"properties\": {\"minecraft:a\": {\"type\": \"boolean\"}, \"minecraft:b\": {\"type\": \"boolean\"}}}");
        file("block/1.26.60/Blocks.json", "{\"properties\": {\"components\": {\"propertyNames\": {\"pattern\": \"^(?!(minecraft:b)$)\"}}}}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        assertFalse(definition(schema, "block", "minecraft:a", 0).getAsJsonObject("root").has("deprecated"));
        assertTrue(definition(schema, "block", "minecraft:b", 0).getAsJsonObject("root").get("deprecated").getAsBoolean());
    }

    /**
     * BDS 1.26.45.1 and 1.26.52.3 name their block component entry point {@code block/1.26.20/Components.json}; 1.26.63.1 has that one
     * and {@code block_components/1.26.60/Block Components.json}. Item formats of that name are not block components.
     */
    @Test
    void theBlockComponentsOfTheBlockDirectoryAreAnEntryPoint() throws IOException {
        file("block/1.26.20/Components.json", "{\"title\": \"Components\", \"properties\": {\"minecraft:a\": {\"type\": \"boolean\"}}}");
        file("block_components/1.26.60/Block Components.json", "{\"properties\": {\"minecraft:a\": {\"type\": \"integer\"}}}");
        file("item/1.21.10/Components.json", "{\"properties\": {\"minecraft:i\": {\"type\": \"boolean\"}}}");
        file("item/1.26.30/Item Components.json", "{\"properties\": {\"minecraft:j\": {\"type\": \"boolean\"}}}");
        JsonObject schema = SchemaExtract.extract(docs, "1.26.99.1");
        assertEquals("[\"1.26.20\",\"1.26.60\"]", schema.getAsJsonObject("formats").get("block").toString());
        assertEquals("[\"1.26.30\"]", schema.getAsJsonObject("formats").get("item").toString());
        assertEquals(2, schema.getAsJsonObject("block").getAsJsonArray("minecraft:a").size(), "one definition per format");
    }

    @Test
    void aKindWithOnlyBetaIsNothingToMerge() throws IOException {
        file("item/beta/Item Components (Beta).json", "{\"properties\": {\"minecraft:newer\": {\"type\": \"boolean\"}}}");
        Schema schema = Schema.of(SchemaExtract.extract(docs, "1.26.99.1"));
        Model docsModel = docsModel();
        assertEquals(docsModel.components().size(), schema.apply(docsModel).components().size());
        assertEquals(List.of(), schema.facts("item:minecraft:newer", "", List.of()));
    }

    @Test
    void theNewestFormatAtOrBelowTheAddonsCounts() throws IOException {
        file("item/1.26.30/Item Components.json", "{\"properties\": {\"minecraft:v\": {\"type\": \"object\", \"properties\": {\"n\": {\"type\": \"integer\", \"maximum\": 5.0}}}}}");
        file("item/1.26.50/Item Components.json", "{\"properties\": {\"minecraft:v\": {\"type\": \"object\", \"properties\": {\"n\": {\"type\": \"integer\", \"maximum\": 9.0}}}}}");
        Schema schema = Schema.of(SchemaExtract.extract(docs, "1.26.99.1"));
        assertEquals(List.of("max 9"), schema.facts("item:minecraft:v", "n", List.of()));
        assertEquals(List.of("max 9"), schema.forFormat("1.26.60").facts("item:minecraft:v", "n", List.of()));
        assertEquals(List.of("max 5"), schema.forFormat("1.26.40").facts("item:minecraft:v", "n", List.of()));
        // Older than any format it describes: nothing of the schema
        assertEquals(List.of("max 1"), schema.forFormat("1.20.0").facts("item:minecraft:v", "n", List.of("max 1")));
    }

    @Test
    void aPathOfTheModelFindsItsSchemaKey() throws IOException {
        JsonObject definition = JsonParser.parseString("{\"root\": {}, \"fields\": {\"{}.tint\": {}, \"a.b\": {}, \"faces\": {}, \"faces[]\": {}}}").getAsJsonObject();
        assertEquals("", Schema.key(definition, ""));
        assertEquals("a.b", Schema.key(definition, "a.b"));
        // the free keys of a map: the model's *.x and one copy per documented key
        assertEquals("{}.tint", Schema.key(definition, "*.tint"));
        assertEquals("{}.tint", Schema.key(definition, "down.tint"));
        assertEquals("faces[]", Schema.key(definition, "faces[]"));
        assertNull(Schema.key(definition, "nothing"));
        assertNull(Schema.key(definition, "x.nothing"));
    }

    @Test
    void aSchemaFileThatCannotBeReadIsNoSchema(@TempDir Path data) throws IOException {
        Files.createDirectories(data.resolve("1.26.1.1"));
        Files.writeString(data.resolve("1.26.1.1").resolve(Schema.FILE), "{ not json");
        assertEquals(Schema.NONE, Schema.newest(data, null));
        Files.createDirectories(data.resolve("1.26.2.1"));
        Files.writeString(data.resolve("1.26.2.1").resolve(Schema.FILE), "{}");
        assertEquals(Schema.NONE, Schema.newest(data, null), "no bdsVersion");
    }

    @Test
    void aFloatBoundIsTheFloat() {
        Schema schema = Schema.of(JsonParser.parseString("{\"bdsVersion\": \"x\", \"formats\": {\"item\": [\"1.26.30\"]}, \"item\": {\"minecraft:f\": [{\"formats\": [\"1.26.30\"],"
            + " \"root\": {\"types\": [\"object\"]}, \"fields\": {\"v\": {\"types\": [\"number\"], \"underlyingType\": \"float\", \"minimum\": 0.10000000149011612,"
            + " \"maximum\": 0.8999999761581421}, \"w\": {\"types\": [\"number\"], \"underlyingType\": \"double\", \"minimum\": 0.30000000000000004, \"multipleOf\": 5}}}]}}").getAsJsonObject());
        assertEquals(List.of("range 0.1 0.9"), schema.facts("item:minecraft:f", "v", List.of()));
        assertEquals(List.of("min 0.30000000000000004", "step 5"), schema.facts("item:minecraft:f", "w", List.of()));
        Model merged = schema.apply(new Model("s", "c", "l", java.util.Map.of(), List.of(), List.of()));
        assertEquals(0.1, merged.component("item:minecraft:f").field("v").range().min());
        assertEquals(0.9, merged.component("item:minecraft:f").field("v").range().max());
    }

    @Test
    void whereTheLogAndTheSchemaDisagreeBothAreKeptAndReported() throws IOException {
        Schema schema = Schema.of(extract());
        JsonObject log = JsonParser.parseString("{\"_generated\": \"x\", \"item:minecraft:test\": {\"power\": [\"range 0 20\", \"required\"],"
            + " \"faces\": [\"maxItems 6\", \"minItems 2\"], \"kind\": [\"values 'water', 'mud'\"], \"other\": [\"max 3\"]}}").getAsJsonObject();
        List<String> disputes = schema.disputes(log);
        assertEquals(3, disputes.size(), disputes.toString());
        assertTrue(disputes.contains("item:minecraft:test power: the content log says `range 0 20`, the schema `range 0 15`"), disputes.toString());
        assertTrue(disputes.stream().anyMatch(d -> d.contains("faces") && d.contains("minItems 2") && d.contains("`minItems 1`")), disputes.toString());
        assertTrue(disputes.stream().anyMatch(d -> d.contains("kind") && d.contains("values 'water', 'mud'")), disputes.toString());
        // The facts the generator reads: the schema's replace the log's of the family, the log's others stay
        assertEquals(List.of("range 0 15", "required"), schema.facts("item:minecraft:test", "power", List.of("range 0 20", "required")));
        assertEquals(List.of("range 0 20"), schema.disputed("item:minecraft:test", "power", List.of("range 0 20", "required")));
        // No dispute where the log says what the schema says
        assertEquals(List.of(), schema.disputed("item:minecraft:test", "power", List.of("range 0 15", "max 15")));
    }

    private static Field field(String path, String type, String doc) {
        return new Field(path, List.of(Model.JType.of(type)), null, false, List.of(), null, null, List.of(), doc);
    }

    /** The docs: {@code power} (a string without limits, required), {@code kind}, {@code stale}, and a component the schema does not list. */
    private static Model docsModel() {
        Component test = new Component("item:minecraft:test", "item", "component", "minecraft:test", "active", "", "p", null, List.of(), List.of(),
            List.of(new Field("power", List.of(Model.JType.of("string")), null, false, List.of(), new Model.Range(0.0, 99.0, "docs"), null, List.of(), "docs"),
                field("kind", "string", "docs"), field("stale", "string", "docs")), List.of());
        Component other = new Component("item:minecraft:other", "item", "component", "minecraft:other", "active", "", "p", null, List.of(), List.of(),
            List.of(field("x", "string", "docs")), List.of());
        return new Model("s", "c", "l", java.util.Map.of(), List.of(test, other), List.of());
    }

    @Test
    void theSchemaComesBeforeTheDocs() throws IOException {
        Schema schema = Schema.of(extract());
        Model merged = schema.apply(docsModel());
        Component test = merged.component("item:minecraft:test");

        // Limits and required of the schema replace the docs' (the docs call power a string: the schema's integer comes first)
        Field power = test.field("power");
        assertEquals(List.of("integer", "string"), power.types().stream().map(Object::toString).toList());
        assertEquals(0.0, power.range().min());
        assertEquals(15.0, power.range().max());
        assertTrue(power.required());
        // Enum values, also of a list's elements
        assertEquals(List.of("water", "lava"), test.field("kind").enumValues().stream().map(e -> e.getAsString()).toList());
        Field faces = test.field("faces");
        assertEquals("array<string>", faces.types().get(0).toString());
        assertEquals(2, faces.enumValues().size());
        assertNull(test.field("faces[]"), "the elements of a list are no fields of their own");
        // A field only the schema knows, marked as such; a map's values; the docs' stale field is untouched and reported
        assertTrue(test.field("bones").doc().startsWith("(bds-schema 1.26.99.1) "));
        assertEquals("map<boolean>", test.field("bones").types().get(0).toString());
        assertNull(test.field("bones{}"), "nor are the values of a map");
        assertEquals("rule.n", test.field("rule.n").path());
        assertEquals(List.of("item:minecraft:test stale"), schema.stale(merged));
        // Components: the docs' own are kept, the schema's are added unless deprecated or beta only
        assertNotNull(merged.component("item:minecraft:other"));
        assertNotNull(merged.component("item:minecraft:same"));
        assertNull(merged.component("item:minecraft:old"));
        assertNull(merged.component("item:minecraft:newer"));
        assertEquals("block:trait:minecraft:placement", merged.component("block:trait:minecraft:placement").id());
        assertEquals("trait", merged.component("block:trait:minecraft:placement").category());
        // Nothing is removed
        assertEquals(List.of("power", "kind", "stale"), test.fields().stream().map(Field::path).limit(3).toList());
        Model docs = docsModel();
        assertSame(docs, Schema.NONE.apply(docs));
    }

    /** The forms of the root, the members of the component's own list and lists of three numbers, as the schema has them. */
    @Test
    void theSchemaGivesFormsListMembersAndVectors() {
        Model docs = Model.GSON.fromJson("""
            {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
              {"id": "block:minecraft:boxes", "kind": "block", "category": "component", "name": "minecraft:boxes", "status": "active",
               "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "boolean"}, {"type": "array", "items": {"type": "object"}}],
               "fields": [{"path": "[].min", "types": [{"type": "vec3"}], "required": false, "enumValues": [], "examples": [], "doc": ""}]},
              {"id": "item:minecraft:button", "kind": "item", "category": "component", "name": "minecraft:button", "status": "active",
               "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}], "fields": []},
              {"id": "block:minecraft:gone", "kind": "block", "category": "component", "name": "minecraft:gone", "status": "active",
               "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "string"}], "fields": []}
            ]}""", Model.class);
        String vector = "{\"types\": [\"array\", \"object\"], \"minItems\": 3, \"maxItems\": 3}";
        Schema schema = Schema.of(JsonParser.parseString("""
            {"bdsVersion": "1.26.99.1", "formats": {"block": ["1.26.60"], "item": ["1.26.50"]},
             "block": {"minecraft:boxes": [{"formats": ["1.26.60"], "root": {"types": ["boolean", "array"], "maxItems": 16},
                         "fields": {"[]": {"types": ["object"]}, "[].min": %s, "[].min[]": {"types": ["number"]}, "[].min.x": {"types": ["number"]},
                                    "[].origin": %s, "[].origin[]": {"types": ["number"]}, "[].origin.x": {"types": ["number"]},
                                    "[].pair": {"types": ["array"], "minItems": 2, "maxItems": 2}, "[].pair[]": {"types": ["number"]}}}],
                       "minecraft:gone": [{"formats": ["1.26.60"], "root": {"deprecated": true, "types": ["object"]}, "fields": {}}]},
             "item": {"minecraft:button": [{"formats": ["1.26.50"], "root": {"types": ["boolean", "string"]}, "fields": {}}]}}"""
            .formatted(vector, vector)).getAsJsonObject());
        Model merged = schema.apply(docs);
        Component boxes = merged.component("block:minecraft:boxes");
        // A member of the component's own list the docs lack; three numbers are a vector, two are a list
        assertEquals(List.of("vec3", "object"), boxes.field("[].origin").types().stream().map(Object::toString).toList());
        assertEquals("[].origin.x", boxes.field("[].origin.x").path());
        assertEquals("array<number>", boxes.field("[].pair").types().getFirst().toString());
        assertEquals(List.of("vec3", "object"), boxes.field("[].min").types().stream().map(Object::toString).toList());
        // The root's types the forms lack, after the documented forms; nothing of a deprecated root
        assertEquals(List.of("object", "boolean", "string"), merged.component("item:minecraft:button").forms().stream().map(Object::toString).toList());
        assertEquals(List.of("boolean", "array<object>"), boxes.forms().stream().map(Object::toString).toList());
        assertEquals(List.of("string"), merged.component("block:minecraft:gone").forms().stream().map(Object::toString).toList());
    }

    @Test
    void theSchemasFactsReplaceTheContentLogsOfTheSameKind() throws IOException {
        Schema schema = Schema.of(extract());
        // The schema's range replaces the log's; a fact of another kind stays
        assertEquals(List.of("range 0 15", "required", "unknown"), schema.facts("item:minecraft:test", "power", List.of("range 1 2", "required", "unknown")));
        assertEquals(List.of("minItems 1", "maxItems 6", "values 'water', 'lava'").subList(0, 2), schema.facts("item:minecraft:test", "faces", List.of()).subList(0, 2));
        assertEquals(List.of("values 'water', 'lava'", "unknown"), schema.facts("item:minecraft:test", "kind", List.of("values 'water'", "unknown")));
        // Required is the union of the schema, the log and the docs: the log's stays, whatever the schema says
        assertTrue(schema.facts("item:minecraft:test", "kind", List.of("required")).contains("required"));
        // What the schema does not have is the log's alone
        assertEquals(List.of("range 1 2"), schema.facts("item:minecraft:test", "nothing", List.of("range 1 2")));
        assertEquals(List.of("range 1 2"), schema.facts("item:minecraft:other", "x", List.of("range 1 2")));
    }

    @Test
    void theNewestSchemaNotAfterTheBuildIsUsed(@TempDir Path data) throws IOException {
        for (String build : List.of("1.26.30.1", "1.26.60.2", "1.26.70.24")) {
            JsonObject json = extract();
            json.addProperty("bdsVersion", build);
            Files.createDirectories(data.resolve(build));
            Files.writeString(data.resolve(build).resolve(Schema.FILE), SchemaExtract.render(json));
        }
        Files.createDirectories(data.resolve("1.26.65.1"));
        assertEquals("1.26.70.24", Schema.newest(data, null).build());
        assertEquals("1.26.60.2", Schema.newest(data, "1.26.65.1").build());
        assertEquals("1.26.60.2", Schema.newest(data, "1.26.60.2").build());
        assertEquals(Schema.NONE, Schema.newest(data, "1.26.20.1"));
        assertEquals(Schema.NONE, Schema.newest(data.resolve("none"), null));
    }

    @Test
    void theDiffNamesWhatChanged() throws IOException {
        JsonObject before = extract();
        JsonObject changed = before.deepCopy();
        JsonObject test = definition(changed, "item", "minecraft:test", 0).getAsJsonObject("fields");
        test.getAsJsonObject("power").addProperty("maximum", 20.0);
        test.remove("bones{}");
        test.add("extra", JsonParser.parseString("{\"types\": [\"boolean\"]}"));
        changed.getAsJsonObject("item").remove("minecraft:same");
        List<String> diff = Schema.diff(Schema.of(before), Schema.of(changed));
        assertEquals(4, diff.size(), diff.toString());
        assertTrue(diff.contains("+ item minecraft:test extra"));
        assertTrue(diff.contains("- item minecraft:test bones{}"));
        assertTrue(diff.contains("- item minecraft:same"));
        assertTrue(diff.stream().anyMatch(l -> l.startsWith("~ item minecraft:test power: ") && l.contains("\"maximum\":15.0") && l.contains("\"maximum\":20.0")));
        assertEquals(List.of(), Schema.diff(Schema.of(before), Schema.of(before)));
    }

    /** The committed schema through {@link Fixtures#apply}: what the add-on gets that the docs and the content log did not give. */
    @Test
    void theCommittedSchemaCompletesTheModelOfTheAddon() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        Path modelDir = root.resolve("data/model");
        Model docs = Model.read(modelDir.resolve("components.json"));
        Fixtures fixtures = Fixtures.read(modelDir);
        assertNotNull(fixtures.schemaBuild(), "no data/bds/<build>/schema.json");
        Model with = fixtures.apply(docs);
        Model without = docs;
        // The content log cut this list short ("... (7 others)"), the docs have none
        assertEquals(0, without.component("item:minecraft:use_animation").field("value").enumValues().size());
        assertTrue(with.component("item:minecraft:use_animation").field("value").enumValues().size() >= 10);
        assertEquals(15.0, with.component("block:minecraft:redstone_producer").field("power").range().max());
        assertTrue(fixtures.facts("block:minecraft:liquid_detection", "detection_rules").contains("maxItems 1"));
        // Formerly errata: the origin and size boxes of collision_box, the boolean or string of interact_button
        for (String box : List.of("[].origin", "[].size")) {
            assertEquals("vec3", with.component("block:minecraft:collision_box").field(box).types().getFirst().toString(), box);
        }
        assertTrue(with.component("item:minecraft:interact_button").forms().stream().map(Object::toString).toList().containsAll(List.of("boolean", "string")));
        // The docs' fields and components all stay
        assertTrue(with.components().size() >= without.components().size());
        for (Component c : without.components()) {
            assertTrue(with.component(c.id()).fields().size() >= c.fields().size(), c.id());
        }
    }


    /**
     * The committed content log and the committed schema say the same where they speak of the same thing, at the format of the
     * add-on whose content log it is (as the add-on reads the schema): a newer format may say otherwise (collision_box's
     * {@code maxItems} is 16 at format 1.26.60 and 32 at 1.26.70 in BDS 1.26.70.24's schema).
     */
    @Test
    void theCommittedDataHasNoDisputes() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        String format = JsonParser.parseString(Files.readString(root.resolve("data/model/fixtures.json"))).getAsJsonObject().get("formatVersion").getAsString();
        Schema schema = Schema.newest(root.resolve("data/bds"), null).forFormat(format);
        assertNotNull(schema.build());
        JsonObject log = JsonParser.parseString(Files.readString(root.resolve("data/model/bds-feedback.json"))).getAsJsonObject();
        assertEquals(List.of(), schema.disputes(log));
    }
}
