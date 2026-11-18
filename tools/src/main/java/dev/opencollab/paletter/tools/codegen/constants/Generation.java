package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.Names;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.Sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one generation shares between its parts: the data, the committed history, the names, and what the parts record
 * while they run (added and deprecated constants, the new history, the hand names that were used).
 */
final class Generation {
    final VanillaData release;
    /** The bundled preview newer than the release, or null. */
    final VanillaData preview;
    final Sources sources;
    final Names names;
    final JsonObject history;
    final String version;
    final List<String> added = new ArrayList<>();
    final List<String> deprecated = new ArrayList<>();
    /** The new history, one section per part. */
    final JsonObject newHistory = new JsonObject();
    /** The lines of {@link ConstantsGenerator#NAMES} that matched the data, as {@code <kind> <key>}. */
    final Set<String> usedNames = new TreeSet<>();

    Generation(VanillaData release, VanillaData preview, Names names, JsonObject history, String version, Sources sources) {
        this.release = release;
        this.preview = preview;
        this.sources = sources;
        this.names = names;
        this.history = history.deepCopy();
        this.history.remove("_generated");
        this.version = version;
    }

    String previewName() {
        return preview == null ? null : preview.version().name();
    }
}
