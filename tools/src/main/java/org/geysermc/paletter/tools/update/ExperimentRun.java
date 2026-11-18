package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.internal.data.ExperimentContent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** What the experiment step found; see docs/data-pipeline.md, "Experiments". */
final class ExperimentRun {
    /** discovered, --experiments, or fallback (discovery failed) */
    String source = "fallback";
    int candidates;
    Set<String> uiKeys = new TreeSet<>();
    Map<String, String> displayNames = new TreeMap<>();
    Map<String, String> uiKeyOf = new TreeMap<>();
    /** Toggles BDS kept in level.dat (current and retired); empty when not discovered. */
    List<String> accepted = List.of();
    /** Codes of the "Experiment(s) active" line of the discovery start. */
    List<String> activeCodes;
    /** Toggles start game streamed: what the spec add-on world and the attribution use.
     * Until discovery has run: the current toggles of the newest committed record ({@link ExperimentDiscovery#recordedCurrent}). */
    List<String> current = List.of();
    List<String> retired = List.of();
    /** Toggles start game reports for a world without any: part of vanilla, never attributed. */
    List<String> alwaysOn = List.of();
    Map<String, String> codes = new TreeMap<>();
    ExperimentAttribution.Diff all;
    Map<String, ExperimentAttribution.Diff> each = new TreeMap<>();
    Map<String, ExperimentContent> content = new TreeMap<>();
    /** Per toggle the recipes it adds, removes or changes, by section; only toggles that touch recipes. */
    Map<String, Map<String, List<String>>> recipes = new TreeMap<>();
    List<String> unexplained = List.of();
    String attribution = "none";
    long millis;
}
