package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The experiment toggles of the captured Bedrock servers ({@code data/bds/<build>/experiments.json}), with the bundled data
 * versions ({@code versions.txt}) they were current in. One source for both generators: the constants generator writes
 * {@code BedrockExperiments} from it, the component generator maps the experiment names of the creator docs to its ids.
 * A capture belongs to the bundled version of its protocol; a capture of no bundled version is ignored.
 */
public final class ExperimentToggles {
    private ExperimentToggles() {
    }

    /**
     * @param id the toggle id, e.g. {@code upcoming_creator_features}
     * @param name the English name of the toggle in the BDS binary, e.g. {@code Upcoming Creator Features}, or null
     * @param since the first bundled data version whose capture has it current (for the oldest bundled one: at the latest)
     * @param sincePreview whether {@code since} is a preview version
     * @param until the first bundled <b>release</b> whose capture has it not current after that, or null. A preview that
     *              retires it says nothing yet of the release, so it ends nothing
     */
    public record Toggle(String id, String name, String since, boolean sincePreview, String until) {
    }

    /** A bundled data version of {@code versions.txt}. */
    record Bundle(String name, int protocol, boolean preview) {
    }

    private static final class History {
        String name;
        Bundle since;
        String until;
    }

    /**
     * @param data the directory with {@code versions.txt}
     * @param bds the {@code data/bds} directory
     * @return every toggle that was current in a captured build, sorted by id; none without captures
     */
    public static List<Toggle> read(Path data, Path bds) throws IOException {
        List<Toggle> toggles = new ArrayList<>();
        if (bds == null || !Files.isDirectory(bds)) {
            return toggles;
        }
        List<Bundle> bundles = bundles(data);
        List<Path> files;
        try (Stream<Path> builds = Files.list(bds)) {
            files = builds.map(b -> b.resolve("experiments.json")).filter(Files::isRegularFile)
                .sorted((a, b) -> Main.compareVersions(a.getParent().getFileName().toString(), b.getParent().getFileName().toString())).toList();
        }
        Map<String, History> histories = new TreeMap<>();
        for (Path file : files) {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Bundle bundle = bundleOf(bundles, json.get("protocol").getAsInt());
            if (bundle != null) {
                track(histories, json.getAsJsonObject("toggles"), bundle);
            }
        }
        histories.forEach((id, h) -> toggles.add(new Toggle(id, h.name, h.since.name(), h.since.preview(), h.until)));
        return toggles;
    }

    /**
     * Folds one capture into the histories: a toggle starts with the first capture that has it current; the first capture of a
     * release after that where it is not current ends it, and a later capture that has it current again reopens it.
     */
    private static void track(Map<String, History> histories, JsonObject toggles, Bundle bundle) {
        for (Map.Entry<String, JsonElement> toggle : toggles.entrySet()) {
            JsonObject value = toggle.getValue().getAsJsonObject();
            History history = histories.get(toggle.getKey());
            if (value.get("status").getAsString().equals("current")) {
                if (history == null) {
                    history = new History();
                    history.since = bundle;
                    histories.put(toggle.getKey(), history);
                }
                history.name = value.has("name") ? value.get("name").getAsString() : history.name;
                history.until = null;
            } else if (history != null && history.until == null && !bundle.preview()) {
                history.until = bundle.name();
            }
        }
    }

    /** @return the toggle id by the name the creator docs use for the experiment; a name of two ids is an error */
    public static Map<String, String> names(List<Toggle> toggles) {
        Map<String, String> names = new TreeMap<>();
        for (Toggle toggle : toggles) {
            if (toggle.name() != null) {
                String other = names.put(toggle.name(), toggle.id());
                if (other != null) {
                    throw new IllegalStateException("The experiment name \"" + toggle.name() + "\" is " + other + " and " + toggle.id() + " in the captures");
                }
            }
        }
        return names;
    }

    static List<Bundle> bundles(Path data) throws IOException {
        List<Bundle> bundles = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("versions.txt"))) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                String[] parts = line.split("\\s+");
                bundles.add(new Bundle(parts[0], Integer.parseInt(parts[1]), parts.length > 2 && parts[2].equals("preview")));
            }
        }
        bundles.sort(java.util.Comparator.comparingInt(Bundle::protocol));
        return bundles;
    }

    /** The bundled version a protocol belongs to: the newest whose first protocol is not above it. */
    static Bundle bundleOf(List<Bundle> bundles, int protocol) {
        Bundle found = null;
        for (Bundle bundle : bundles) {
            if (bundle.protocol() <= protocol) {
                found = bundle;
            }
        }
        return found;
    }
}
