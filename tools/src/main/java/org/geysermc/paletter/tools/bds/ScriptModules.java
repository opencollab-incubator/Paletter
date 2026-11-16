package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Http;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The beta version of the {@code @minecraft/server} Script API module a BDS build offers. Mojang publishes the typings of
 * every build to npm as {@code <module version>-beta.<build without its last part>-stable} (for 1.26.63.1:
 * {@code 2.12.0-beta.1.26.63-stable}); a pack depends on {@code <module version>-beta}.
 */
public final class ScriptModules {
    static final String REGISTRY = "https://registry.npmjs.org/@minecraft/server";

    private ScriptModules() {
    }

    /** @return the beta dependency version for the build, e.g. {@code 2.12.0-beta} for {@code 1.26.63.1} */
    public static String betaVersion(String bdsVersion) throws IOException, InterruptedException {
        // the abbreviated metadata: only the version list
        String body = Http.DEFAULT.get(REGISTRY, Map.of("Accept", "application/vnd.npm.install-v1+json"));
        return betaVersion(JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("versions"), bdsVersion);
    }

    /**
     * The module version npm lists for the build ({@code 1.26.63}); for a build npm skipped (1.26.45), the one every listed
     * build of its release line ({@code 1.26.4x}) has, if they all have the same.
     */
    static String betaVersion(JsonObject versions, String bdsVersion) {
        String build = bdsVersion.substring(0, bdsVersion.lastIndexOf('.'));
        Pattern pattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+)-beta\\.(\\d+\\.\\d+\\.\\d+)-stable");
        java.util.Set<String> line = new java.util.TreeSet<>();
        for (String version : versions.keySet()) {
            Matcher matcher = pattern.matcher(version);
            if (matcher.matches() && matcher.group(2).equals(build)) {
                return matcher.group(1) + "-beta";
            }
            if (matcher.matches() && lineOf(matcher.group(2)).equals(lineOf(build))) {
                line.add(matcher.group(1));
            }
        }
        if (line.size() == 1) {
            return line.iterator().next() + "-beta";
        }
        throw new IllegalStateException("npm has no @minecraft/server beta for BDS " + bdsVersion + " and no single one for its release line: " + line);
    }

    /** {@code 1.26.45} to {@code 1.26.4} */
    private static String lineOf(String build) {
        return build.substring(0, build.length() - 1);
    }

    /** Sets the version of the manifest's {@code @minecraft/server} dependency. */
    static void setServerModule(JsonObject manifest, String version) {
        for (JsonElement dependency : manifest.getAsJsonArray("dependencies")) {
            JsonObject object = dependency.getAsJsonObject();
            if (object.has("module_name") && object.get("module_name").getAsString().equals("@minecraft/server")) {
                object.addProperty("version", version);
            }
        }
    }
}
