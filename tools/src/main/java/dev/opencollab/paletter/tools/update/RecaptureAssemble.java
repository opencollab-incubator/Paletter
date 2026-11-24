package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.DataPaths;
import dev.opencollab.paletter.tools.Layout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code recapture assemble}: the sequential half of a full recapture (docs/data-pipeline.md, "Full recapture"), after the
 * capture legs. The artifacts of the legs are data: every one is validated before anything is copied, then each is laid
 * over {@code data/bds/<build>} (deleting nothing), the {@link #STEPS} run in their order, {@code check-mappings --strict}
 * gives its verdict for the report, and the report is written. When a step changed an input of the spec add-on
 * ({@link DataPaths#ADDON_INPUTS}, compared byte for byte), the captures answer for an add-on that is not the final one:
 * the run ends with {@link #EXIT_ADDON_CHANGED}, the changed inputs in {@code addon-inputs/} and {@code model.diff} in the
 * output directory, for the change to be committed and the recapture run again.
 */
public final class RecaptureAssemble {
    /** The exit code of a run whose steps changed an add-on input. */
    public static final int EXIT_ADDON_CHANGED = 3;
    /** The largest captures of one build that are staged. */
    public static final long MAX_BYTES = 400L << 20;
    /** The largest report (GitHub limits bodies to 65536 characters). */
    static final int MAX_REPORT = 60000;
    /** What one leg's report contributes at most. */
    static final int MAX_LEG_REPORT = 5000;
    /** A relative path of a capture: plain names, no segment starting with a dot (so no {@code ..} either). */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");
    private static final Pattern VERDICT = Pattern.compile("(?m)^(?:OK|Changed|Failed): .*$");
    /** The placeholders of the behaviour import's checkouts in {@link #STEPS}. */
    static final String CLOUDBURST_DATA = "{cloudburst-data}";
    static final String ALLAY = "{allay}";

    /** One step: a command of the tools, run in-process with {@code --root}. */
    public record Step(String name, List<String> arguments) {
    }

    /**
     * What {@code update} does after its captures, with no BDS, in this order (docs/data-pipeline.md lists the same, which
     * {@code RecaptureAssembleTest} checks): the experiment marks of the bundled versions from the new experiment records;
     * the inference; the behaviour data from the new dumps and the third-party ones at their pins; the white-box coverage
     * (the committed whitebox.json: the binary did not change); the component API; the voxel shapes; the bundled recipes;
     * the item variant names; the vanilla constants. {@code :paletter:apiDump} follows as a Gradle task, since it compiles
     * what the generators wrote.
     */
    public static final List<Step> STEPS = List.of(
        new Step("experiment-marks", List.of("experiment-marks")),
        new Step("infer", List.of("infer")),
        new Step("behaviour import", List.of("behaviour", "import", "--cloudburst-data", CLOUDBURST_DATA, "--allay", ALLAY)),
        new Step("whitebox-coverage", List.of("whitebox-coverage")),
        new Step("components generate", List.of("components", "generate")),
        new Step("voxel-shapes import", List.of("voxel-shapes", "import")),
        new Step("recipes-import", List.of("recipes-import")),
        new Step("variant-names", List.of("variant-names")),
        new Step("constants generate", List.of("constants", "generate")));
    /** The check whose verdict goes into the report without failing the run. */
    public static final List<String> STRICT = List.of("check-mappings", "--strict");

    /** Runs a command of the tools; returns its exit code. */
    @FunctionalInterface
    public interface Runner {
        int run(List<String> arguments) throws Exception;
    }

    /**
     * @param plan the legs of the recapture plan
     * @param in the downloaded artifacts: {@code captures-<build>/} with {@code captures/} and {@code report.md}
     * @param out where the report (and, after an add-on change, its evidence) goes
     * @param sources fetches the checkouts of the behaviour import by source name, once the artifacts are staged
     * @param commit the commit the captures were made at, named in the report (null for none)
     * @param maxBytes the largest captures of one build ({@link #MAX_BYTES})
     */
    public record Options(List<RecapturePlan.Leg> plan, Path in, Path out, Sources sources, String commit, long maxBytes) {
    }

    /** The checkouts the behaviour import reads ({@code BehaviourSources.fetch}). */
    @FunctionalInterface
    public interface Sources {
        Map<String, Path> fetch() throws IOException, InterruptedException;
    }

    private RecaptureAssemble() {
    }

    /** @return the process exit code: 0, {@link #EXIT_ADDON_CHANGED}, or 1 for invalid artifacts or a failed step */
    public static int run(Path root, Options options, Runner runner) throws Exception {
        List<String> problems = validate(root, options);
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.out.println("INVALID " + problem));
            System.out.println("Nothing was staged: every artifact must be valid");
            return 1;
        }
        for (RecapturePlan.Leg leg : options.plan()) {
            int files = stage(captures(options.in(), leg.build()), root.resolve(Layout.BDS).resolve(leg.build()));
            System.out.println("Staged " + files + " files into " + Layout.BDS + "/" + leg.build());
        }
        Map<String, Path> sources = options.sources().fetch();
        Map<String, byte[]> inputs = inputs(root);
        for (Step step : STEPS) {
            List<String> arguments = new ArrayList<>();
            for (String argument : step.arguments()) {
                arguments.add(switch (argument) {
                    case CLOUDBURST_DATA -> sources.get("CloudburstMC/Data").toString();
                    case ALLAY -> sources.get("AllayMC/Allay").toString();
                    default -> argument;
                });
            }
            arguments.add("--root");
            arguments.add(root.toString());
            System.out.println("== " + step.name());
            int code = runner.run(arguments);
            if (code != 0) {
                System.out.println("FAILED " + step.name() + " (exit " + code + ")");
                return 1;
            }
        }
        String strict = strict(root, runner);
        Files.createDirectories(options.out());
        Files.writeString(options.out().resolve("report.md"), report(options, strict));
        List<String> changed = changedInputs(root, inputs);
        if (!changed.isEmpty()) {
            evidence(root, options.out(), changed);
            System.out.println("::error::The inference changed the add-on inputs (" + String.join(", ", changed) + "), so these captures were made "
                + "with an add-on that is not the final one. Commit that change (addon-inputs/ and model.diff in " + options.out() + ") and run again.");
            return EXIT_ADDON_CHANGED;
        }
        return 0;
    }

    private static Path captures(Path in, String build) {
        return in.resolve("captures-" + build).resolve("captures");
    }

    /** Every problem of the artifacts; empty when all of them may be staged. */
    static List<String> validate(Path root, Options options) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> expected = new TreeSet<>();
        options.plan().forEach(leg -> expected.add("captures-" + leg.build()));
        Set<String> found = new TreeSet<>();
        if (Files.isDirectory(options.in())) {
            try (Stream<Path> entries = Files.list(options.in())) {
                entries.forEach(entry -> found.add(entry.getFileName().toString()));
            }
        }
        if (!expected.equals(found)) {
            problems.add("the artifacts are not the builds of the plan: plan " + expected + ", artifacts " + found);
            return problems;
        }
        for (RecapturePlan.Leg leg : options.plan()) {
            String build = leg.build();
            Path artifact = options.in().resolve("captures-" + build);
            try (Stream<Path> entries = Files.list(artifact)) {
                for (Path entry : entries.toList()) {
                    String name = entry.getFileName().toString();
                    BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!(name.equals("captures") && attributes.isDirectory() && !attributes.isOther())
                        && !(name.equals("report.md") && attributes.isRegularFile())) {
                        problems.add(build + ": the artifact holds " + name + " besides captures/ and report.md");
                    }
                }
            }
            Path captures = captures(options.in(), build);
            if (!Files.isDirectory(captures, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(build + ": no captures");
                continue;
            }
            long[] bytes = {0};
            Files.walkFileTree(captures, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(captures)) {
                        check(directory, attributes);
                    }
                    return attributes.isDirectory() && !attributes.isOther() && !attributes.isSymbolicLink()
                        ? java.nio.file.FileVisitResult.CONTINUE : java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    check(file, attributes);
                    bytes[0] += attributes.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                private void check(Path path, BasicFileAttributes attributes) {
                    String relative = captures.relativize(path).toString().replace('\\', '/');
                    if (attributes.isSymbolicLink() || attributes.isOther() || !(attributes.isRegularFile() || attributes.isDirectory())) {
                        problems.add(build + ": " + relative + " is no plain file or directory");
                    } else if (!PATH.matcher(relative).matches()) {
                        problems.add(build + ": " + relative + " is not a plain relative path");
                    }
                }
            });
            if (!Files.isRegularFile(captures.resolve("spec/capture.json"), LinkOption.NOFOLLOW_LINKS)) {
                problems.add(build + ": no spec capture");
            }
            if (bytes[0] > options.maxBytes()) {
                problems.add(build + ": the captures are larger than " + (options.maxBytes() >> 20) + " MB");
            }
            if (!Files.isRegularFile(root.resolve(Layout.BDS).resolve(build).resolve("spec/capture.json"))) {
                problems.add(build + ": no committed captures to refresh");
            }
        }
        return problems;
    }

    /** Copies every file of the captures over the build's directory, replacing files and deleting nothing. */
    static int stage(Path captures, Path target) throws IOException {
        int count = 0;
        try (Stream<Path> files = Files.walk(captures)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path destination = target.resolve(captures.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    private static Map<String, byte[]> inputs(Path root) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String input : DataPaths.ADDON_INPUTS) {
            Path file = root.resolve(input);
            bytes.put(input, Files.exists(file) ? Files.readAllBytes(file) : null);
        }
        return bytes;
    }

    /** The add-on inputs whose bytes differ from {@code before}. */
    static List<String> changedInputs(Path root, Map<String, byte[]> before) throws IOException {
        List<String> changed = new ArrayList<>();
        Map<String, byte[]> after = inputs(root);
        before.forEach((input, bytes) -> {
            if (!Arrays.equals(bytes, after.get(input))) {
                changed.add(input);
            }
        });
        return changed;
    }

    /** The changed inputs as they are now, and {@code git diff -- data/model} (or why there is none). */
    private static void evidence(Path root, Path out, List<String> changed) throws IOException, InterruptedException {
        Path inputs = Files.createDirectories(out.resolve("addon-inputs"));
        for (String input : changed) {
            Path file = root.resolve(input);
            if (Files.exists(file)) {
                Files.copy(file, inputs.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path diff = out.resolve("model.diff");
        try {
            Process git = new ProcessBuilder("git", "-C", root.toString(), "diff", "--", "data/model").redirectOutput(diff.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (git.waitFor() != 0) {
                Files.writeString(diff, "git diff failed in " + root + "; the changed inputs are in addon-inputs/\n");
            }
        } catch (IOException e) {
            Files.writeString(diff, "no git (" + e.getMessage() + "); the changed inputs are in addon-inputs/\n");
        }
    }

    /** The verdict of {@code check-mappings --strict}: what it leaves unexplained, or that it leaves nothing. */
    static String strict(Path root, Runner runner) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int code;
        try (PrintStream tee = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                original.write(b);
                captured.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                captured.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8)) {
            System.setOut(tee);
            List<String> arguments = new ArrayList<>(STRICT);
            arguments.add("--root");
            arguments.add(root.toString());
            code = runner.run(arguments);
        } finally {
            System.setOut(original);
        }
        if (code == 0) {
            return "no residual bits, unclassified wire paths or linked fields";
        }
        List<String> unexplained = captured.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("UNEXPLAINED")).limit(40).toList();
        return "residual bits, unclassified wire paths or linked fields (check-mappings --strict):\n" + String.join("\n", unexplained);
    }

    /** The pull request body: a verdict per leg, the strict line, and every leg's report collapsed; at most {@link #MAX_REPORT} bytes. */
    static String report(Options options, String strict) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("Every kept BDS build and the newest preview, captured again with the add-on generated from `")
            .append(options.commit() == null ? "the checkout" : options.commit()).append("`.\n\n");
        text.append("| Build | Channel | Verdict of its capture against the committed captures |\n|---|---|---|\n");
        Map<String, String> reports = new LinkedHashMap<>();
        for (RecapturePlan.Leg leg : options.plan()) {
            Path file = options.in().resolve("captures-" + leg.build()).resolve("report.md");
            String report = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.readString(file) : null;
            Matcher verdict = report == null ? null : VERDICT.matcher(report);
            text.append("| ").append(leg.build()).append(" | ").append(leg.channel()).append(" | ")
                .append(verdict != null && verdict.find() ? verdict.group().replace("|", "\\|") : "no verdict").append(" |\n");
            if (report != null) {
                reports.put(leg.build(), report);
            }
        }
        text.append("Mappings: ").append(strict.length() > 3000 ? strict.substring(0, 3000) : strict).append("\n\n");
        text.append("The patch holds the refreshed captures and what follows from them (mappings, coverage, white-box coverage, generated code). "
            + "Merge it only with the model change it recaptures.\n");
        reports.forEach((build, report) -> text.append("\n<details><summary>BDS ").append(build).append("</summary>\n\n")
            .append(report.length() > MAX_LEG_REPORT ? report.substring(0, MAX_LEG_REPORT) : report).append("\n</details>\n"));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length <= MAX_REPORT ? text.toString() : new String(bytes, 0, MAX_REPORT, StandardCharsets.UTF_8);
    }
}
