package dev.opencollab.paletter.tools.update;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sequential half of a full recapture, on temporary directories with stub steps. */
class RecaptureAssembleTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final String A = "1.26.36.1";
    private static final String B = "1.26.63.1";

    /** A repository with committed captures of both builds and the add-on inputs. */
    private static void repository(Path root) throws IOException {
        for (String build : List.of(A, B)) {
            Path spec = Files.createDirectories(root.resolve("data/bds").resolve(build).resolve("spec"));
            Files.writeString(spec.resolve("capture.json"), "{\"protocol\": 1, \"old\": true}");
            Files.writeString(root.resolve("data/bds").resolve(build).resolve("only-committed.txt"), "stays");
        }
        Path model = Files.createDirectories(root.resolve("data/model"));
        for (String file : List.of("fixtures.json", "components.json", "bds-feedback.json", "bds-keys.json", "mappings.json")) {
            Files.writeString(model.resolve(file), "{}");
        }
    }

    /** A valid artifact of a leg. */
    private static Path artifact(Path in, String build, String verdict) throws IOException {
        Path captures = Files.createDirectories(in.resolve("captures-" + build).resolve("captures").resolve("spec"));
        Files.writeString(captures.resolve("capture.json"), "{\"protocol\": 1, \"new\": true}");
        Files.writeString(captures.resolve("variants.json"), "[]");
        Files.writeString(in.resolve("captures-" + build).resolve("report.md"), "# Report of " + build + "\n\n" + verdict + "\n\ndetails\n");
        return captures.getParent();
    }

    private static RecaptureAssemble.Options options(Path in, Path out) {
        return new RecaptureAssemble.Options(List.of(new RecapturePlan.Leg(A, false), new RecapturePlan.Leg(B, false)), in, out,
            () -> Map.of("CloudburstMC/Data", Path.of("cb"), "AllayMC/Allay", Path.of("allay")), "c".repeat(40), 1 << 20);
    }

    /** Records every command; check-mappings --strict finds one unexplained bit. */
    private static final class Steps implements RecaptureAssemble.Runner {
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public int run(List<String> arguments) {
            commands.add(arguments);
            if (arguments.get(0).equals("check-mappings")) {
                System.out.println("UNEXPLAINED minecraft:foo bit 3");
                return 1;
            }
            return 0;
        }
    }

    @Test
    void stagesEveryLegOverItsBuildAndRunsTheStepsInOrder(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        // B was kept by a history check: it is staged like any leg (it was skipped once)
        Files.writeString(root.resolve("data/bds/builds.json"), "{\"builds\": {\"" + A + "\": {\"protocol\": 1, \"keptBy\": \"update\"}, \""
            + B + "\": {\"protocol\": 1, \"keptBy\": \"history check\"}}}");
        artifact(in, A, "OK: no changes");
        artifact(in, B, "Changed: 2 components");
        Steps steps = new Steps();
        assertEquals(0, RecaptureAssemble.run(root, options(in, out), steps));
        for (String build : List.of(A, B)) {
            Path directory = root.resolve("data/bds").resolve(build);
            assertEquals("{\"protocol\": 1, \"new\": true}", Files.readString(directory.resolve("spec/capture.json")), build + " replaced");
            assertTrue(Files.exists(directory.resolve("spec/variants.json")), build + " added");
            assertEquals("stays", Files.readString(directory.resolve("only-committed.txt")), build + ": nothing is deleted");
        }
        List<String> names = steps.commands.stream().map(command -> String.join(" ", command.subList(0, command.indexOf("--root")))).toList();
        List<String> expected = new ArrayList<>(RecaptureAssemble.STEPS.stream().map(step -> String.join(" ", step.arguments())
            .replace(RecaptureAssemble.CLOUDBURST_DATA, "cb").replace(RecaptureAssemble.ALLAY, "allay")).toList());
        expected.add("check-mappings --strict");
        assertEquals(expected, names);
        assertTrue(steps.commands.stream().allMatch(command -> command.get(command.size() - 1).equals(root.toString())), "every step gets --root");
    }

    /** download-artifact puts a single artifact's content straight into the target directory: a one-leg plan stages it. */
    @Test
    void aOneLegPlanStagesTheFlatLayoutOfASingleArtifact(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        Path captures = Files.createDirectories(in.resolve("captures").resolve("spec"));
        Files.writeString(captures.resolve("capture.json"), "{\"protocol\": 1, \"new\": true}");
        Files.writeString(in.resolve("report.md"), "# Report of " + A + "\n\nOK: no changes\n");
        RecaptureAssemble.Options one = new RecaptureAssemble.Options(List.of(new RecapturePlan.Leg(A, false)), in, out,
            () -> Map.of("CloudburstMC/Data", Path.of("cb"), "AllayMC/Allay", Path.of("allay")), "c".repeat(40), 1 << 20);
        assertEquals(0, RecaptureAssemble.run(root, one, new Steps()));
        assertEquals("{\"protocol\": 1, \"new\": true}", Files.readString(root.resolve("data/bds").resolve(A).resolve("spec/capture.json")));
    }

    /** With two legs a flat layout is not guessed at: the artifacts are not the plan's. */
    @Test
    void aTwoLegPlanRejectsAFlatLayout(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        Files.createDirectories(in.resolve("captures").resolve("spec"));
        assertEquals(1, RecaptureAssemble.run(root, options(in, out), new Steps()));
    }

    @Test
    void theReportHasAVerdictPerLegAndTheStrictLine(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "Changed: 2 | 3 components");
        assertEquals(0, RecaptureAssemble.run(root, options(in, out), new Steps()));
        String report = Files.readString(out.resolve("report.md"));
        assertTrue(report.contains("`" + "c".repeat(40) + "`"), report);
        assertTrue(report.contains("| " + A + " | release | OK: no changes |"), report);
        assertTrue(report.contains("| " + B + " | release | Changed: 2 \\| 3 components |"), report);
        assertTrue(report.contains("Mappings: residual bits, unclassified wire paths or linked fields (check-mappings --strict):\nUNEXPLAINED minecraft:foo bit 3"), report);
        assertTrue(report.contains("<details><summary>BDS " + B + "</summary>"), report);
    }

    @Test
    void theReportIsCapped(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "OK: no changes");
        List<RecapturePlan.Leg> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String build = "1.26.70." + i;
            many.add(new RecapturePlan.Leg(build, true));
            Path dir = Files.createDirectories(in.resolve("captures-" + build));
            Files.writeString(dir.resolve("report.md"), "OK: no changes\n" + "x".repeat(10000));
        }
        String report = RecaptureAssemble.report(new RecaptureAssemble.Options(many, in, out, Map::of, null, 1), "no residual bits");
        assertTrue(report.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= RecaptureAssemble.MAX_REPORT, "length " + report.length());
        assertTrue(report.contains("from `the checkout`"));
    }

    /** The guard: a step that changes an add-on input ends with exit 3 and the evidence; any other change is the point. */
    @Test
    void aChangedAddonInputIsExitThreeWithTheEvidence(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "OK: no changes");
        RecaptureAssemble.Runner inference = arguments -> {
            if (arguments.get(0).equals("infer")) {
                Files.writeString(root.resolve("data/model/fixtures.json"), "{\"changed\": true}");
            }
            return 0;
        };
        assertEquals(RecaptureAssemble.EXIT_ADDON_CHANGED, RecaptureAssemble.run(root, options(in, out), inference));
        assertEquals("{\"changed\": true}", Files.readString(out.resolve("addon-inputs/fixtures.json")));
        assertTrue(Files.exists(out.resolve("model.diff")));
        assertTrue(Files.exists(out.resolve("report.md")), "the report is written either way");
    }

    @Test
    void aChangedMappingIsNoAddonChange(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "OK: no changes");
        RecaptureAssemble.Runner inference = arguments -> {
            if (arguments.get(0).equals("infer")) {
                Files.writeString(root.resolve("data/model/mappings.json"), "{\"changed\": true}");
            }
            return 0;
        };
        assertEquals(0, RecaptureAssemble.run(root, options(in, out), inference));
        assertFalse(Files.exists(out.resolve("addon-inputs")));
    }

    @Test
    void aFailedStepFailsTheRun(@TempDir Path root, @TempDir Path in, @TempDir Path out) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "OK: no changes");
        List<String> ran = new ArrayList<>();
        assertEquals(1, RecaptureAssemble.run(root, options(in, out), arguments -> {
            ran.add(arguments.get(0));
            return arguments.get(0).equals("whitebox-coverage") ? 2 : 0;
        }));
        assertEquals(List.of("experiment-marks", "infer", "behaviour", "whitebox-coverage"), ran, "nothing after the failed step");
    }

    private interface Breakage {
        void apply(Path root, Path in) throws IOException;
    }

    /** Each way an artifact is refused; and then nothing at all is staged, also not the valid first leg. */
    @Test
    void anInvalidLegStagesNothing(@TempDir Path base) throws Exception {
        Map<String, Breakage> breakages = new java.util.LinkedHashMap<>();
        breakages.put("a missing leg", (root, in) -> { });
        breakages.put("an extra leg", (root, in) -> artifact(in, "1.26.45.1", "OK"));
        breakages.put("a dot segment", (root, in) -> Files.writeString(in.resolve("captures-" + B + "/captures/.hidden"), "x"));
        breakages.put("a dot directory", (root, in) -> Files.writeString(Files.createDirectories(in.resolve("captures-" + B + "/captures/..x")).resolve("a"), "x"));
        breakages.put("a character outside the list", (root, in) -> Files.writeString(in.resolve("captures-" + B + "/captures/a b.json"), "x"));
        breakages.put("no spec capture", (root, in) -> Files.delete(in.resolve("captures-" + B + "/captures/spec/capture.json")));
        breakages.put("more than the cap", (root, in) -> Files.write(in.resolve("captures-" + B + "/captures/big.bin"), new byte[(1 << 20) + 1]));
        breakages.put("no committed captures", (root, in) -> Files.delete(root.resolve("data/bds/" + B + "/spec/capture.json")));
        breakages.put("something besides captures and the report", (root, in) -> Files.writeString(in.resolve("captures-" + B + "/result.txt"), "exit=0"));
        int index = 0;
        for (Map.Entry<String, Breakage> breakage : breakages.entrySet()) {
            Path root = Files.createDirectories(base.resolve("root" + index));
            Path in = Files.createDirectories(base.resolve("in" + index));
            Path out = base.resolve("out" + index++);
            repository(root);
            artifact(in, A, "OK: no changes");
            if (!breakage.getKey().equals("a missing leg")) {
                artifact(in, B, "OK: no changes");
            }
            breakage.getValue().apply(root, in);
            Steps steps = new Steps();
            List<String> problems = RecaptureAssemble.validate(root, options(in, out));
            assertFalse(problems.isEmpty(), breakage.getKey());
            assertEquals(1, RecaptureAssemble.run(root, options(in, out), steps), breakage.getKey());
            assertEquals(List.of(), steps.commands, breakage.getKey() + ": no step runs");
            assertEquals("{\"protocol\": 1, \"old\": true}", Files.readString(root.resolve("data/bds/" + A + "/spec/capture.json")),
                breakage.getKey() + ": the valid leg is not staged either");
        }
    }

    @Test
    void aLinkIsRefusedWithoutFollowingIt(@TempDir Path root, @TempDir Path in, @TempDir Path out, @TempDir Path outside) throws Exception {
        repository(root);
        artifact(in, A, "OK: no changes");
        artifact(in, B, "OK: no changes");
        Files.writeString(outside.resolve("secret.txt"), "x");
        try {
            Files.createSymbolicLink(in.resolve("captures-" + B + "/captures/link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating links needs a privilege on Windows
            Assumptions.abort("no symbolic links here: " + e);
        }
        List<String> problems = RecaptureAssemble.validate(root, options(in, out));
        assertEquals(List.of(B + ": link is no plain file or directory"), problems);
    }

    /** docs/data-pipeline.md lists the steps of assemble in the order of {@link RecaptureAssemble#STEPS}. */
    @Test
    void theDocumentedStepsAreTheSteps() throws IOException {
        String docs = Files.readString(ROOT.resolve("docs/data-pipeline.md"));
        Matcher line = Pattern.compile("(?m)^.*runs, in this order: (.*)$").matcher(docs);
        assertTrue(line.find(), "docs/data-pipeline.md has no line `runs, in this order: ...`");
        List<String> documented = new ArrayList<>();
        Matcher code = Pattern.compile("`([^`]+)`").matcher(line.group(1));
        while (code.find()) {
            documented.add(code.group(1));
        }
        List<String> steps = new ArrayList<>(RecaptureAssemble.STEPS.stream().map(RecaptureAssemble.Step::name).toList());
        steps.add(String.join(" ", RecaptureAssemble.STRICT));
        assertEquals(steps, documented);
    }
}
