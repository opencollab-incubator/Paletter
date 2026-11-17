package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.whitebox.WhiteboxCoverage.Captured;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The self-checks of a whitebox run: what they let through, and what they stop. */
class WhiteboxChecksTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    /** Facts with these block components (name to its keys) and nothing else. */
    private static JsonObject facts(String... blocks) {
        StringBuilder components = new StringBuilder();
        for (String block : blocks) {
            String[] parts = block.split("=");
            components.append(components.isEmpty() ? "" : ",").append('"').append(parts[0]).append("\": {\"network\": true, \"writes\": [")
                .append(parts.length > 1 ? Stream.of(parts[1].split(",")).map(key -> '"' + key + '"').reduce((a, b) -> a + "," + b).orElse("") : "")
                .append("]}");
        }
        return JsonParser.parseString("{\"itemComponents\": {}, \"itemClassesWithoutName\": {\"sent\": []}, \"blockComponents\": {" + components + "}, \"traits\": {},"
            + "\"envelopes\": {\"item\": {\"writes\": [\"item_properties\"]}, \"block\": {\"writes\": []}}}").getAsJsonObject();
    }

    private static Captured capturedBlocks(String... names) {
        Captured captured = new Captured();
        NbtMapBuilder components = NbtMap.builder();
        for (String name : names) {
            components.putCompound(name, NbtMap.builder().putInt("value", 1).build());
        }
        captured.addBlock(NbtMap.builder().putCompound("components", components.build()).build());
        return captured;
    }

    @Test
    void passesWhatIsComplete() {
        JsonObject facts = facts("minecraft:a=value", "minecraft:b=value");
        assertEquals(List.of(), WhiteboxChecks.problems(facts, null, new Captured()));
        assertEquals(List.of(), WhiteboxChecks.problems(facts, facts, capturedBlocks("minecraft:a", "minecraft:b")));
    }

    @Test
    void stopsACapturedComponentThatIsNotFound() {
        List<String> problems = WhiteboxChecks.problems(facts("minecraft:a=value"), null, capturedBlocks("minecraft:a", "minecraft:b"));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("[block:minecraft:b]"), problems.toString());
    }

    @Test
    void stopsALargeDropAgainstTheBuildBeforeUnlessCapturesShowIt() {
        String[] many = new String[8];
        for (int i = 0; i < many.length; i++) {
            many[i] = "minecraft:c" + i + "=value,other";
        }
        JsonObject before = facts(many);
        JsonObject after = facts("minecraft:c0=value,other", "minecraft:c1=value,other", "minecraft:c2=value,other", "minecraft:c3=value,other");

        // Four of eight components gone and no capture to say BDS dropped them: the analysis lost them
        List<String> problems = WhiteboxChecks.problems(after, before, new Captured());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("4 of the "), problems.toString());
        // Three would pass (a small drop), and so does any number the captures of this build do not show
        assertEquals(List.of(), WhiteboxChecks.problems(after, before, capturedBlocks("minecraft:c0", "minecraft:c1", "minecraft:c2", "minecraft:c3")));
        assertEquals(List.of(), WhiteboxChecks.problems(facts("minecraft:c0=value,other", "minecraft:c1=value,other", "minecraft:c2=value,other",
            "minecraft:c3=value,other", "minecraft:c4=value,other"), before, new Captured()));

        // The same for keys: every component stays but loses a key
        JsonObject fewerKeys = facts("minecraft:c0=value", "minecraft:c1=value", "minecraft:c2=value", "minecraft:c3=value", "minecraft:c4=value",
            "minecraft:c5=value", "minecraft:c6=value", "minecraft:c7=value");
        problems = WhiteboxChecks.problems(fewerKeys, before, new Captured());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("8 of the "), problems.toString());
        assertEquals(List.of(), WhiteboxChecks.problems(fewerKeys, before, capturedBlocks("minecraft:c0", "minecraft:c1", "minecraft:c2", "minecraft:c3", "minecraft:c4", "minecraft:c5", "minecraft:c6", "minecraft:c7")));
    }

    /** The committed facts of every build pass against the build before and the captures committed with it. */
    @Test
    void committedFactsPass() throws IOException {
        Path bds = ROOT.resolve(Layout.BDS);
        List<Path> builds;
        try (Stream<Path> list = Files.list(bds)) {
            builds = list.filter(build -> Files.isRegularFile(build.resolve(Whitebox.FILE)))
                .sorted((a, b) -> Versions.compare(a.getFileName().toString(), b.getFileName().toString())).toList();
        }
        assertFalse(builds.isEmpty());
        JsonObject previous = null;
        for (Path build : builds) {
            JsonObject facts = Whitebox.parse(build.resolve(Whitebox.FILE));
            assertEquals(List.of(), WhiteboxChecks.problems(facts, previous, WhiteboxCoverage.captured(build, "spec"::equals)), build.toString());
            previous = facts;
        }
    }
}
