package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.addon.AddonWriter;
import dev.opencollab.paletter.tools.model.infer.SpecCaptures.Capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code molangVersions} of {@code mappings.json}: the {@code molangVersion} BDS writes into every block of a pack, by the
 * pack's {@code min_engine_version}. The spec add-on has the main pack at its format version and one engine pack per
 * {@link AddonWriter#ENGINE_VERSIONS} with a single control block ({@link AddonWriter#enginePacks}); the version of each is
 * what its blocks have. Only the engine versions where the value changes are kept, oldest first: a pack's molangVersion is
 * that of the newest entry at or below its engine version (the oldest entry's below it, unverified).
 */
final class MolangVersions {
    private MolangVersions() {
    }

    /**
     * @return engine version -> molangVersion, empty if no capture has the engine packs
     * @throws IllegalStateException if two captures give one engine version different values
     */
    static JsonObject infer(Path root, List<Capture> captures) throws IOException {
        Map<String, Integer> byEngine = new TreeMap<>(Schema::compareVersions);
        for (Capture capture : captures) {
            Map<String, Integer> own = new TreeMap<>(Schema::compareVersions);
            for (AddonWriter.EnginePack pack : AddonWriter.enginePacks()) {
                Integer value = molangVersion(capture.definitions().get("block " + pack.identifier()));
                if (value != null) {
                    own.put(pack.minEngineVersion(), value);
                }
            }
            if (own.size() != AddonWriter.enginePacks().size()) {
                continue; // a capture from before the engine packs
            }
            Path meta = root.resolve(Layout.BDS).resolve(capture.bdsVersion()).resolve("spec").resolve("capture.json");
            String format = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject().get("formatVersion").getAsString();
            Integer main = mainPack(capture);
            if (main == null) {
                continue;
            }
            own.put(format, main);
            own.forEach((engine, value) -> {
                Integer before = byEngine.putIfAbsent(engine, value);
                if (before != null && !before.equals(value)) {
                    throw new IllegalStateException("molangVersion of min_engine_version " + engine + ": " + before + " in one capture, "
                        + value + " in BDS " + capture.bdsVersion());
                }
            });
        }
        JsonObject out = new JsonObject();
        Integer last = null;
        for (Map.Entry<String, Integer> e : byEngine.entrySet()) {
            if (!e.getValue().equals(last)) {
                out.addProperty(e.getKey(), e.getValue());
                last = e.getValue();
            }
        }
        return out;
    }

    /** The molangVersion every block of the main pack has, or null if they differ or there is none. */
    private static Integer mainPack(Capture capture) {
        Integer value = null;
        for (Map.Entry<String, Map<String, dev.opencollab.paletter.tools.model.rule.Leaf>> e : capture.definitions().entrySet()) {
            if (!e.getKey().startsWith("block ") || AddonWriter.enginePacks().stream().anyMatch(p -> e.getKey().equals("block " + p.identifier()))) {
                continue;
            }
            Integer own = molangVersion(e.getValue());
            if (own == null || (value != null && !value.equals(own))) {
                return null;
            }
            value = own;
        }
        return value;
    }

    private static Integer molangVersion(Map<String, dev.opencollab.paletter.tools.model.rule.Leaf> leaves) {
        if (leaves == null || leaves.get("molangVersion") == null) {
            return null;
        }
        return (int) leaves.get("molangVersion").number();
    }
}
