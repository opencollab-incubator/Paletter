package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The observations of the behaviour probe ({@code data/addons/probe}) in a BDS console log: the BDS version and build,
 * and every {@code PROBE <tag> <json>} line, grouped by tag in log order. Everything else in the log is ignored.
 *
 * @param version the BDS version, e.g. {@code 1.26.63.1}
 * @param build the BDS build id
 * @param observations the JSON of each line by its tag
 * @param extract the lines of the log that carry all of this, verbatim: what is committed as {@code probe.log}
 */
record ProbeLog(String version, String build, Map<String, List<JsonObject>> observations, List<String> extract) {
    private static final Pattern VERSION = Pattern.compile("INFO\\] Version: (\\S+)");
    private static final Pattern BUILD = Pattern.compile("INFO\\] Build ID: (\\S+)");
    private static final Pattern OBSERVATION = Pattern.compile("\\[Scripting\\] PROBE (\\w+) (\\{.*\\})\\s*$");

    static ProbeLog parse(List<String> lines) {
        String version = null;
        String build = null;
        Map<String, List<JsonObject>> observations = new LinkedHashMap<>();
        List<String> extract = new ArrayList<>();
        boolean done = false;
        for (String line : lines) {
            Matcher matcher;
            if ((matcher = VERSION.matcher(line)).find() && version == null) {
                version = matcher.group(1);
                extract.add(line);
            } else if ((matcher = BUILD.matcher(line)).find() && build == null) {
                build = matcher.group(1);
                extract.add(line);
            } else if ((matcher = OBSERVATION.matcher(line)).find()) {
                observations.computeIfAbsent(matcher.group(1), tag -> new ArrayList<>())
                    .add(JsonParser.parseString(matcher.group(2)).getAsJsonObject());
                extract.add(line);
            } else if (line.contains("PROBE-FAILED")) {
                throw new IllegalStateException("The probe failed: " + line);
            } else if (line.contains("PROBE-DONE")) {
                done = true;
                extract.add(line);
            }
        }
        if (version == null || build == null || !done) {
            throw new IllegalStateException("Not a complete probe log: version " + version + ", build " + build + ", PROBE-DONE " + done);
        }
        return new ProbeLog(version, build, observations, extract);
    }

    /** @return the observations of a tag, none if the probe printed none (a section it could not run) */
    List<JsonObject> optional(String tag) {
        return observations.getOrDefault(tag, List.of());
    }

    List<JsonObject> of(String tag) {
        List<JsonObject> found = observations.get(tag);
        if (found == null) {
            throw new IllegalStateException("The probe log has no " + tag + " observations");
        }
        return found;
    }
}
