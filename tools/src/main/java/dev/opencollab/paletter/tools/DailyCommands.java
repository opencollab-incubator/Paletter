package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.daily.DailyDecide;
import dev.opencollab.paletter.tools.daily.DailyResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;

/** The steps of the daily BDS data workflow (bds-data.yml) that are logic, not plumbing: {@code daily decide} and {@code daily result}. */
final class DailyCommands {
    private DailyCommands() {
    }

    /** The channels a run checks. */
    enum Channels {
        BOTH, RELEASE, PREVIEW;

        List<String> names() {
            return this == BOTH ? DailyDecide.CHANNELS : List.of(name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Command(name = "daily", description = "The decisions of the daily BDS data workflow (docs/data-pipeline.md, \"The daily check\").",
        subcommands = {Daily.Decide.class, Daily.Result.class})
    static final class Daily implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "decide", description = "Which newest builds the daily check captures: one request to the download links API for every "
            + "channel (none with --version); a build that is recorded, has data/bds/<build>, waits in the open data pull request or failed "
            + "twice at this commit is skipped. Appends `matrix` and `captures` to --outputs (the GitHub output format).")
        static final class Decide extends Rooted implements Callable<Integer> {
            @Option(names = "--channel", defaultValue = "BOTH", description = "The channels: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
            Channels channel;
            @Option(names = "--version", description = "This build instead of the newest (one channel only); no request") String version;
            @Option(names = "--force", description = "Capture whatever is known of the build") boolean force;
            @Option(names = "--commit", required = true, description = "The commit the workflow runs at (40 hex digits)") String commit;
            @Option(names = "--issues", required = true, description = "The open issues: gh issue list --json number,title,author,body")
            Path issues;
            @Option(names = "--pulls", required = true, description = "The open pull requests: gh pr list --json number,headRefName,isCrossRepository,body")
            Path pulls;
            @Option(names = "--outputs", required = true, description = "File the outputs are appended to: matrix=<json> and captures=<count>")
            Path outputs;
            @Option(names = "--summary", description = "File the decisions are appended to as a markdown table") Path summary;

            @Override
            public Integer call() throws Exception {
                List<DailyDecide.Decision> decisions = DailyDecide.decide(root(), channel.names(), version, force, commit,
                    DailyDecide.issues(Files.readString(issues)), DailyDecide.pulls(Files.readString(pulls)), BdsDistribution::links);
                long captures = decisions.stream().filter(DailyDecide.Decision::capture).count();
                Files.writeString(outputs, "matrix=" + DailyDecide.matrix(decisions) + "\ncaptures=" + captures + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                String table = DailyDecide.summary(decisions);
                System.out.print(table);
                if (summary != null) {
                    Files.writeString(summary, table, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                return 0;
            }
        }

        @Command(name = "result", description = "What the daily capture job hands to its publish job: copies the run's report to --out, "
            + "writes --out/result.txt (version, update, differs, hash, addon) and deletes the patch when there is nothing to publish.")
        static final class Result implements Callable<Integer> {
            @CommandLine.Mixin BuildOptions build;
            @Option(names = "--cache", description = BdsOptions.CACHE) Path cache;
            @Option(names = "--update-exit", required = true, description = "The exit code of update") int updateExit;
            @Option(names = "--codegen-exit", defaultValue = "0", description = "The exit code of the code generation after it (default: 0)")
            int codegenExit;
            @Option(names = "--verify-only", description = "The update was a verification") boolean verifyOnly;
            @Option(names = "--patch", required = true, description = "The patch of the data paths (deleted when there is nothing to publish)")
            Path patch;
            @Option(names = "--out", required = true, description = "Where report.md and result.txt go") Path out;
            @Option(names = "--run-id", required = true, description = "The workflow run's id, the hash of a run without a report") String runId;
            @Option(names = "--summary", description = "File the report is appended to (at most 60000 bytes)") Path summary;

            @Override
            public Integer call() throws Exception {
                String version = build.requireVersion();
                // update writes the report of every run into its work directory as well
                Path runReport = BdsDistribution.resolve(build.preview(), version).runDirectory(BdsOptions.cache(cache), "").resolve("report.md");
                Files.createDirectories(out);
                Path report = out.resolve("report.md");
                if (Files.isRegularFile(runReport)) {
                    Files.copy(runReport, report, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (summary != null) {
                        byte[] bytes = Files.readAllBytes(report);
                        Files.write(summary, java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 60000)), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                }
                DailyResult.Result result = DailyResult.of(version, updateExit, codegenExit, verifyOnly, report, patch, runId);
                Files.writeString(out.resolve("result.txt"), result.text());
                System.out.print(result.text());
                return 0;
            }
        }
    }
}
