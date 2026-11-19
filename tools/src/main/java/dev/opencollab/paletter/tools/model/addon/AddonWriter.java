package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.Model.Component;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator.Variant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.BP_UUID;
import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.NAMESPACE;
import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.RP_UUID;
import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.SCRIPT_MARKER;
import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.minEngine;

/**
 * Writes the variants of an {@link AddonGenerator} to disk as the spec capture add-on: the item and block files, the
 * behavior and resource pack manifests, the script of the script components, the hash collision pair, the engine packs and the
 * {@code variants.json} that says what each identifier tests. It adds no variants and decides no values.
 */
public final class AddonWriter {
    private final AddonGenerator generator;
    private final Model model;
    private final Fixtures fixtures;
    private final String formatVersion;
    private final List<Variant> variants;

    AddonWriter(AddonGenerator generator) {
        this.generator = generator;
        this.model = generator.model();
        this.fixtures = generator.fixtures;
        this.formatVersion = generator.formatVersion;
        this.variants = generator.variants();
    }

    void write(Path out) throws IOException {
        if (Files.exists(out)) {
            try (Stream<Path> files = Files.walk(out)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Path bp = out.resolve("behavior_pack");
        Path rp = out.resolve("resource_pack");
        for (Variant variant : variants) {
            json(bp.resolve(variant.file()), variant.definition());
            if (variant.companion() != null) {
                String folder = variant.companion().has("minecraft:block") ? "blocks/" : "items/";
                json(bp.resolve(folder + variant.identifier().substring(NAMESPACE.length() + 1) + ".json"), variant.companion());
            }
        }
        if (generator.standard) {
            for (String identifier : fixtures.hashCollision()) {
                json(bp.resolve("blocks/" + identifier.substring(NAMESPACE.length() + 1) + ".json"), HashCollisions.block(identifier, formatVersion));
            }
        }
        FixedAddons.writeLootTable(bp);
        JsonObject manifest = JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture", "description": "Generated from data/model: every documented component field, varied",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "data", "uuid": "a1e5c0de-0000-4000-8000-00000000d002", "version": [1, 0, 0]}],
             "dependencies": [{"uuid": "%s", "version": [1, 0, 0]}]}""".formatted(BP_UUID, minEngine(formatVersion), RP_UUID)).getAsJsonObject();
        if (fixtures.hasScriptComponents()) {
            manifest.getAsJsonArray("modules").add(JsonParser.parseString("""
                {"type": "script", "language": "javascript", "uuid": "a1e5c0de-0000-4000-8000-00000000d003", "entry": "scripts/main.js", "version": [1, 0, 0]}"""));
            manifest.getAsJsonArray("dependencies").add(JsonParser.parseString("""
                {"module_name": "@minecraft/server", "version": "%s"}""".formatted(fixtures.scriptComponents().get("module").getAsString())));
            writeScript(bp.resolve("scripts/main.js"));
        }
        json(bp.resolve("manifest.json"), manifest);
        if (generator.standard) {
            writeEnginePacks(out);
        }
        json(rp.resolve("manifest.json"), JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture resources", "description": "Textures and geometry for the spec capture",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "resources", "uuid": "a1e5c0de-0000-4000-8000-00000000e002", "version": [1, 0, 0]}]}""".formatted(RP_UUID, minEngine(formatVersion))));
        FixedAddons.writeResources(rp);
        json(out.resolve("variants.json"), manifest());
        long items = variants.stream().filter(v -> v.kind().equals("item")).count();
        System.out.println(items + " items, " + (variants.size() - items) + " blocks (" + model.components().size() + " components) -> " + out);
    }

    /**
     * The extra behavior packs of the spec add-on: per {@link #ENGINE_VERSIONS} one pack with that {@code min_engine_version} and a
     * single control block, in the same world as the main pack. They show whether what BDS writes into every block that is not a
     * component (the {@code molangVersion}) follows the pack's engine version: the file format is the main pack's, so the engine version
     * is the only difference.
     *
     * @param directory the folder of the pack in the add-on ({@code behavior_pack_engine_<n>})
     * @param identifier the identifier of its block
     */
    public record EnginePack(String minEngineVersion, String uuid, String directory, String identifier) {
    }

    public static final List<String> ENGINE_VERSIONS = List.of("1.16.100", "1.19.40");

    public static List<EnginePack> enginePacks() {
        List<EnginePack> packs = new ArrayList<>();
        for (int i = 0; i < ENGINE_VERSIONS.size(); i++) {
            packs.add(new EnginePack(ENGINE_VERSIONS.get(i), "a1e5c0de-0000-4000-8000-00000000d1" + String.format("%02x", i), "behavior_pack_engine_" + i,
                NAMESPACE + ":engine_" + i));
        }
        return packs;
    }

    private void writeEnginePacks(Path out) throws IOException {
        Component block = model.components().stream().filter(c -> c.kind().equals("block") && c.category().equals("component") && fixtures.requires(c.id()).isEmpty()).findFirst().orElse(null);
        if (block == null) {
            return;
        }
        for (EnginePack pack : enginePacks()) {
            Path bp = out.resolve(pack.directory());
            json(bp.resolve("blocks/" + pack.identifier().substring(NAMESPACE.length() + 1) + ".json"), generator.definition(block, pack.identifier(), null, formatVersion, false));
            json(bp.resolve("manifest.json"), JsonParser.parseString("""
                {"format_version": 2, "header": {"name": "Paletter spec capture, engine %s", "description": "A control block under an older min_engine_version",
                  "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
                 "modules": [{"type": "data", "uuid": "%s", "version": [1, 0, 0]}]}""".formatted(
                pack.minEngineVersion(), pack.uuid(), minEngine(pack.minEngineVersion()), pack.uuid().replaceFirst("d1(..)$", "d2$1"))));
        }
    }

    /**
     * The script of the add-on: on startup it registers every component of {@code scriptComponents} with an empty function per
     * callback (a component's name and parameters never reach the wire, only which callbacks it has), then prints {@link #SCRIPT_MARKER}.
     */
    private void writeScript(Path file) throws IOException {
        JsonObject tables = scriptTables();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            // Generated by AddonGenerator from the scriptComponents of data/model/fixtures.json: do not edit.
            import { system } from "@minecraft/server";

            const tables = %s;

            system.beforeEvents.startup.subscribe(event => {
                const registries = { block: event.blockComponentRegistry, item: event.itemComponentRegistry };
                let count = 0;
                for (const [kind, table] of Object.entries(tables)) {
                    for (const [name, callbacks] of Object.entries(table)) {
                        registries[kind].registerCustomComponent(name, Object.fromEntries(callbacks.map(callback => [callback, () => {}])));
                        count++;
                    }
                }
                console.warn("%s " + count);
            });
            """.formatted(Model.GSON.toJson(tables), SCRIPT_MARKER), StandardCharsets.UTF_8);
    }

    private JsonObject scriptTables() {
        JsonObject tables = new JsonObject();
        tables.add("block", fixtures.scriptComponents().get("block"));
        tables.add("item", fixtures.scriptComponents().get("item"));
        return tables;
    }

    /** The line the script prints when it has registered every component of the table (null without a script). */
    String scriptMarker() {
        if (!fixtures.hasScriptComponents()) {
            return null;
        }
        return SCRIPT_MARKER + " " + scriptTables().entrySet().stream().mapToInt(e -> e.getValue().getAsJsonObject().size()).sum();
    }

    /** {@code variants.json}: what each identifier tests. */
    JsonArray manifest() {
        JsonArray array = new JsonArray();
        for (Variant v : variants) {
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", v.identifier());
            entry.addProperty("kind", v.kind());
            entry.addProperty("file", v.file());
            entry.addProperty("component", v.componentId());
            entry.addProperty("role", v.role());
            entry.addProperty("path", v.path());
            entry.addProperty("label", v.label());
            entry.add("value", v.value() == null ? JsonNull.INSTANCE : v.value());
            entry.add("json", v.component() == null ? JsonNull.INSTANCE : v.component());
            if (v.format() != null) {
                entry.addProperty("format", v.format());
            }
            if (!v.linked().isEmpty()) {
                JsonArray linked = new JsonArray();
                v.linked().forEach(linked::add);
                entry.add("linked", linked);
            }
            array.add(entry);
        }
        return array;
    }

    private static void json(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Model.GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }
}
