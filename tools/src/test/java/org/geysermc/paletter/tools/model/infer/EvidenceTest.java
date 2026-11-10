package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.infer.Evidence.Scan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gates on synthetic input: what they flag, what they accept, and that the messages say what to add and where. */
class EvidenceTest {
    private static final List<String> SOURCE = List.of(
        "    /** Javadoc with (byte) 1 in it is not code. */",
        "    // wire-constant: flag_a",
        "    static final int FLAG_A = 4;",
        "    out.put(\"x\", (byte) 0); // wire-constant: zero",
        "    out.put(\"y\", (byte) 0);",
        "    float v = a == null ? 1f : a;",
        "    ctx.drop(NAME, \"a message\");",
        "    int unrelated = 3 + 4;");

    @Test
    void scannerFlagsLiteralsWithoutMarker() {
        Scan scan = Evidence.scan(SOURCE);
        assertEquals(Map.of("flag_a", List.of(3), "zero", List.of(4)), scan.ids());
        assertEquals(List.of(5, 6), scan.unmarked().stream().map(Evidence.Line::number).toList());
    }

    private static Path root(String evidence, List<String> source) throws Exception {
        Path root = Files.createTempDirectory("paletter-evidence");
        Path file = root.resolve(Evidence.TRANSFORMS_SOURCE);
        Files.createDirectories(file.getParent());
        Files.write(file, source, StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("data/model"));
        if (evidence != null) {
            Files.writeString(root.resolve("data/model/evidence.json"), evidence);
        }
        return root;
    }

    private static List<String> problems(String evidence, List<String> source, Map<String, String> findings, Map<String, String> constants) throws Exception {
        Path root = root(evidence, source);
        return Evidence.read(root.resolve("data/model")).problems(root, findings, constants, null, Map.of());
    }

    /** The marked lines of SOURCE only. */
    private static final List<String> MARKED = SOURCE.subList(1, 4);

    @Test
    void everythingWithEvidenceIsFine() throws Exception {
        String evidence = """
            {"findings": {"f": {"status": "unexplained", "reason": "r", "wouldExplain": "w"}},
             "constants": {"c a.b": {"status": "unexplained", "reason": "r", "wouldExplain": "w"},
                           "Transforms zero": {"status": "unexplained", "reason": "r", "wouldExplain": "w"},
                           "Transforms flag_a": {"status": "unexplained", "reason": "r", "wouldExplain": "w"}}}
            """;
        assertTrue(problems(evidence, MARKED, Map.of("f", "d"), Map.of("c a.b", "1 (byte)")).isEmpty());
    }

    @Test
    void missingEvidenceSaysWhatToAdd() throws Exception {
        List<String> problems = problems(null, SOURCE.subList(1, 6), Map.of("residual-bits c x", "bit 2"), Map.of("c a.b", "1 (byte)"));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("FINDING without evidence: residual-bits c x") && p.contains("evidence.json")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("CONSTANT without evidence: c a.b = 1 (byte)") && p.contains("\"constants\"")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("CONSTANT without evidence: Transforms flag_a")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("UNMARKED") && p.contains("wire-constant")), problems.toString());
    }

    @Test
    void incompleteAndStaleEntriesAreProblems() throws Exception {
        String evidence = """
            {"findings": {"gone": {"status": "unexplained", "reason": "r", "wouldExplain": "w"},
                          "bad": {"status": "unexplained", "reason": "r"}},
             "constants": {"stale c.d": {"status": "captured", "capture": "data/bds/x/spec", "variants": ["v"]},
                           "empty a.b": {"status": "captured"},
                           "Transforms nothing": {"status": "unexplained", "reason": "r", "wouldExplain": "w"}}}
            """;
        List<String> problems = problems(evidence, MARKED, Map.of("bad", "d"), Map.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("findings \"bad\"") && p.contains("wouldExplain")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("findings \"gone\"") && p.contains("remove the entry")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("constants \"stale c.d\"") && p.contains("remove the entry")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("constants \"empty a.b\"") && p.contains("needs a \"capture\"")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("constants \"Transforms nothing\"") && p.contains("no `wire-constant:` marker")), problems.toString());
    }

    @Test
    void aStatusOutsideTheKnownOnesIsAProblem() throws Exception {
        JsonObject finding = new JsonObject();
        finding.addProperty("status", "captured");
        JsonObject findings = new JsonObject();
        findings.add("f", finding);
        JsonObject root = new JsonObject();
        root.add("findings", findings);
        List<String> problems = problems(root.toString(), MARKED, Map.of(), Map.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("findings \"f\"") && p.contains("status must be one of")), problems.toString());
    }
}
