package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.behaviour.BehaviourSources;
import dev.opencollab.paletter.tools.update.RecaptureAssemble;
import dev.opencollab.paletter.tools.update.RecapturePlan;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;

/** The two halves of a full recapture around its capture legs (docs/data-pipeline.md, "Full recapture"): {@code recapture plan|assemble}. */
final class RecaptureCommands {
    private RecaptureCommands() {
    }

    @Command(name = "recapture", mixinStandardHelpOptions = true, description ="A full recapture: `plan` lists the legs, each leg runs `update --capture-only`, `assemble` "
        + "stages their captures and runs the rest.", subcommands = {Recapture.Plan.class, Recapture.Assemble.class})
    static final class Recapture implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "plan", description = "The builds a full recapture captures (every kept release build and the preview with captures in "
            + "data/bds, read from the checkout without the network), as a GitHub Actions matrix; frozen builds are listed as skipped.")
        static final class Plan extends Rooted implements Callable<Integer> {
            @Option(names = "--build", description = "Only this build of the plan (repeatable; a rehearsal); a build the plan lacks fails")
            List<String> builds;
            @Option(names = "--output", description = "File for the matrix JSON") Path output;
            @Option(names = "--outputs", description = "File the outputs are appended to: matrix=<json> and legs=<count>") Path outputs;
            @Option(names = "--summary", description = "File the legs and the skipped builds are appended to, as a markdown list") Path summary;

            @Override
            public Integer call() throws Exception {
                List<RecapturePlan.Leg> legs = RecapturePlan.only(RecapturePlan.of(root()), builds);
                String matrix = RecapturePlan.matrix(legs);
                if (output != null) {
                    Files.writeString(output, matrix + "\n");
                }
                if (outputs != null) {
                    Files.writeString(outputs, "matrix=" + matrix + "\nlegs=" + legs.size() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                StringBuilder list = new StringBuilder();
                for (RecapturePlan.Leg leg : legs) {
                    list.append("- ").append(leg.build()).append(" (").append(leg.channel())
                        .append(RecapturePlan.historyCheck(root(), leg.build()) ? ", kept by a history check" : "").append(")\n");
                }
                RecapturePlan.frozen(root()).forEach(line -> list.append("- SKIPPED ").append(line).append('\n'));
                System.out.print(list);
                if (summary != null) {
                    Files.writeString(summary, list, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                return 0;
            }
        }

        @Command(name = "assemble", description = "Validates the artifacts of the capture legs (all or nothing), lays them over data/bds/<build>, "
            + "runs the steps after the captures in their order, check-mappings --strict for the report, and writes --out/report.md. Exit 3: a step "
            + "changed an input of the spec add-on (addon-inputs/ and model.diff in --out): commit it and recapture again.")
        static final class Assemble extends Rooted implements Callable<Integer> {
            @Option(names = "--plan", required = true, description = "The matrix of `recapture plan`") Path plan;
            @Option(names = "--in", required = true, description = "The downloaded artifacts: captures-<build>/ with captures/ and report.md") Path in;
            @Option(names = "--out", required = true, description = "Where the report goes (and the evidence of a changed add-on input)") Path out;
            @Option(names = "--fetch-sources", required = true, description = "An empty directory the third-party behaviour dumps are cloned "
                + "into, at the pins of data/behaviour/sources.json") Path fetchSources;
            @Option(names = "--commit", description = "The commit the captures were made at, named in the report") String commit;

            @Override
            public Integer call() throws Exception {
                List<RecapturePlan.Leg> legs = RecapturePlan.read(Files.readString(plan));
                if (commit != null && !commit.matches("[0-9a-f]{40}")) {
                    throw new IllegalArgumentException("Not a commit: " + commit);
                }
                RecaptureAssemble.Sources sources = () -> BehaviourSources.fetch(root(), Files.createDirectories(fetchSources), BehaviourSources.GIT);
                return RecaptureAssemble.run(root(), new RecaptureAssemble.Options(legs, in, out, sources, commit, RecaptureAssemble.MAX_BYTES),
                    arguments -> Cli.commandLine().execute(arguments.toArray(String[]::new)));
            }
        }
    }
}
