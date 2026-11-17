package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.whitebox.WhiteboxCoverage.Gap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * What {@code update} does with a build's binary: the facts, whether they pass {@link WhiteboxChecks}, what changed
 * against the build before, and which coverage gaps are new. Nothing is written; {@code update} stages the files.
 *
 * @param problems what the self-checks found; when there is any, the facts are not to be used and nothing else is set
 * @param changes the diff to the build before: {@code !} lines change the serialisation, {@code ~} lines only the code
 * @param coverage the report of {@code whitebox-coverage}, or null if the build has no captures yet
 * @param newGaps the gaps of the build that the committed report of the build (or the one before) does not list
 */
public record WhiteboxRun(JsonObject facts, List<String> problems, List<String> changes, String coverage, List<Gap> gaps, List<Gap> newGaps) {
    public static WhiteboxRun of(Path binary, Path root, String build) throws IOException {
        Path bds = root.resolve(Layout.BDS);
        Path buildData = bds.resolve(build);
        JsonObject facts = Whitebox.discover(binary, build);
        Path previousFile = Whitebox.previous(bds, build, Whitebox.FILE);
        JsonObject previous = previousFile == null ? null : Whitebox.parse(previousFile);
        boolean captured = Files.isDirectory(buildData);
        List<String> problems = WhiteboxChecks.problems(facts, previous,
            captured ? WhiteboxCoverage.captured(buildData, "spec"::equals) : new WhiteboxCoverage.Captured());
        if (!problems.isEmpty()) {
            return new WhiteboxRun(null, problems, List.of(), null, List.of(), List.of());
        }
        List<String> changes = previous == null ? List.of() : WhiteboxDiff.diff(previous, facts);
        if (!captured) {
            return new WhiteboxRun(facts, problems, changes, null, List.of(), List.of());
        }
        JsonObject mappings = JsonParser.parseString(Files.readString(root.resolve(Layout.MODEL).resolve("mappings.json"))).getAsJsonObject();
        List<Gap> gaps = WhiteboxCoverage.gaps(facts, WhiteboxCoverage.captured(buildData), mappings);
        Path report = Files.isRegularFile(buildData.resolve(WhiteboxCoverage.FILE)) || previousFile == null
            ? buildData.resolve(WhiteboxCoverage.FILE) : previousFile.resolveSibling(WhiteboxCoverage.FILE);
        Set<Gap> known = WhiteboxCoverage.read(report);
        return new WhiteboxRun(facts, problems, changes, WhiteboxCoverage.report(build, gaps), gaps, gaps.stream().filter(gap -> !known.contains(gap)).toList());
    }
}
