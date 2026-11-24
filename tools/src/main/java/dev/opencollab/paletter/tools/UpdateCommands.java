package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsRun;
import dev.opencollab.paletter.tools.update.BuildHistory;
import dev.opencollab.paletter.tools.update.BuildTable;
import dev.opencollab.paletter.tools.update.CreativeProbe;
import dev.opencollab.paletter.tools.update.EducationProbe;
import dev.opencollab.paletter.tools.update.RecapturePlan;
import dev.opencollab.paletter.tools.update.RecipeProbe;
import dev.opencollab.paletter.tools.update.SpecCapture;
import dev.opencollab.paletter.tools.update.SpecCaptureOptions;
import dev.opencollab.paletter.tools.update.Update;
import dev.opencollab.paletter.tools.update.UpdateOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The commands that run BDS to bring the data up to date: {@code update}, {@code builds}, {@code spec-capture},
 * {@code creative-probe}, {@code education-capture}, {@code recipes-capture}.
 */
final class UpdateCommands {
    private UpdateCommands() {
    }

    /** The toggles of a {@code --experiments a,b} option; null if the option was not given (the recorded ones are used). */
    static List<String> toggles(List<String> experiments) {
        return experiments == null ? null : experiments.stream().map(String::strip).filter(toggle -> !toggle.isEmpty()).toList();
    }

    @Command(name = "update", description = "The whole pipeline: newest BDS of the channel, Script API dump, vanilla and add-on network captures "
        + "(hashed and sequential block ids), comparison with the committed data, import or refresh, component shape comparison, markdown report. "
        + "A preview is imported as a preview version. The report goes to data/bds/VERSION/update-report.md when the update changed "
        + "data, else only to the run's work directory. "
        + "--verify-only writes nothing and exits with 1 on any difference; an update exits with 2 when the report lists decisions.")
    static final class UpdateCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--verify-only", description = "Write nothing; exit with 1 on any difference") boolean verifyOnly;
        @Option(names = "--keep-server", description = BdsOptions.KEEP_SERVER) boolean keepServer;
        @Option(names = "--report", description = "Where the report goes (default: data/bds/VERSION/update-report.md when data changed, else the work directory)")
        Path report;
        @Option(names = "--data-version", description = "The name a new protocol is imported as (default: the build's line, e.g. 26.60)") String dataVersion;
        @Option(names = "--experiments", split = ",", description = "The toggles of the spec add-on's world, a,b (default: the ones recorded for the build)")
        List<String> experiments;
        @Option(names = "--experiment-attribution", defaultValue = "EACH", description = "Which experiment captures attribute content to toggles: "
            + "${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})") UpdateOptions.Attribution attribution;
        @Option(names = "--against", description = "A history check (see `builds`): compare with the committed captures of this build "
            + "instead of the newest, and write only data/bds/VERSION, when anything differs") String against;
        @Option(names = "--capture-only", description = "The capture half of a full recapture: write the captures of the build (the files that "
            + "would go to data/bds/VERSION, and spec/) into this directory and nothing into the repository; the report goes to the work directory") Path captureOnly;

        @Override
        public Integer call() throws Exception {
            BdsRun run = bds.run(19260, 300, keepServer);
            return new Update(root(), new UpdateOptions(run, report, dataVersion, toggles(experiments), attribution, against, captureOnly, verifyOnly)).run();
        }
    }

    @Command(name = "spec-capture", description = "Generates the spec add-on from data/model, loads it in BDS with experiments on and writes the "
        + "content log, the custom item registry and block properties and variants.json to data/bds/VERSION/spec (input of infer).")
    static final class SpecCaptureCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--out", description = "Where the capture goes (default: data/bds/VERSION/spec, spec-keys, spec-format or spec-gating)") Path out;
        @Option(names = "--keep-server", description = BdsOptions.KEEP_SERVER) boolean keepServer;
        @Option(names = "--experiments", split = ",", description = "The toggles of the world, a,b (default: the ones recorded for the build)")
        List<String> experiments;
        @Option(names = "--protocol", defaultValue = "0", description = "The protocol to capture with when the server's status carries none") int protocol;
        @Option(names = "--probe-keys", description = "Ask BDS about keys the docs do not list (from the BDS binary, wiki.bedrock.dev and vanilla packs) "
            + "instead of the spec; writes data/bds/VERSION/spec-keys and data/model/bds-keys.json") boolean probeKeys;
        @Option(names = "--format-sweep", description = "Capture the components at the format versions around their Versioned boundaries "
            + "instead of the spec; writes data/bds/VERSION/spec-format with format-sweep.json") boolean formatSweep;
        @Option(names = "--gating", description = "Load only the experiment-gated components in a world without experiments, to verify what the creator docs "
            + "say of their experiments; writes data/bds/VERSION/spec-gating with gating.json") boolean gating;
        @Option(names = "--vanilla-pack", description = "A pack with vanilla block and item files, a source of keys for --probe-keys") Path vanillaPack;

        @Override
        public Integer call() throws Exception {
            if ((probeKeys ? 1 : 0) + (formatSweep ? 1 : 0) + (gating ? 1 : 0) > 1) {
                throw new IllegalArgumentException("--probe-keys, --format-sweep and --gating are three captures: give at most one");
            }
            SpecCaptureOptions.Mode mode = probeKeys ? SpecCaptureOptions.Mode.KEYS : formatSweep ? SpecCaptureOptions.Mode.FORMAT_SWEEP
                : gating ? SpecCaptureOptions.Mode.GATING : SpecCaptureOptions.Mode.SPEC;
            return new SpecCapture(root(), new SpecCaptureOptions(bds.run(19270, 600, keepServer), mode, out, vanillaPack, toggles(experiments),
                protocol, false, false, false)).run();
        }
    }

    @Command(name = "creative-probe", description = "Runs a creative add-on on BDS and records the creative inventory order.")
    static final class CreativeProbeCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--addon", required = true, description = "The add-on directory, with behavior_pack and optionally resource_pack") Path addon;
        @Option(names = "--out", required = true, description = "Where the captures of each start go") Path out;
        @Option(names = "--starts", defaultValue = "2", description = "How many times the server starts (default: ${DEFAULT-VALUE})") int starts;
        @Option(names = "--protocol", defaultValue = "0", description = "The protocol of the build (default: the one of its spec capture in data/bds)") int protocol;
        @Option(names = "--data-version", description = "The bundled version the captures resolve block hashes against (default: the build's line)")
        String dataVersion;
        @Option(names = "--experiments", split = ",", description = "The toggles of the world, a,b (default: none)") List<String> experiments;

        @Override
        public Integer call() throws Exception {
            bds.requireVersion();
            List<String> toggles = toggles(experiments);
            CreativeProbe.run(root(), new CreativeProbe.Options(bds.run(19290, 300, true), addon, out, starts, protocol, dataVersion,
                toggles == null ? List.of() : toggles));
            return 0;
        }
    }

    @Command(name = "education-capture", description = "Starts a world on BDS with Education Edition features off and on, captures both and "
        + "writes the identifiers only the education world has to data/bds/VERSION/education.json (input of constants generate).")
    static final class EducationCaptureCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--data-version", description = "The bundled version the block hashes are resolved against (default: the one of the server's protocol)")
        String dataVersion;
        @Option(names = "--out", description = "Where the logs and both captures go (default: the build's run directory in the cache)") Path out;

        @Override
        public Integer call() throws Exception {
            EducationProbe.run(root(), bds.run(19300, 300, false), out, dataVersion);
            return 0;
        }
    }

    @Command(name = "recipes-capture", description = "Captures a vanilla world of one BDS build and writes only its crafting and trim data "
        + "to data/bds/VERSION/vanilla.")
    static final class RecipesCaptureCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--keep-server", description = BdsOptions.KEEP_SERVER) boolean keepServer;

        @Override
        public Integer call() throws Exception {
            bds.requireVersion();
            RecipeProbe.run(root(), bds.run(19750, 300, keepServer));
            return 0;
        }
    }

    @Command(name = "builds", description = "Every BDS release build, one by one: list them, or check the ones data/bds/builds.json lacks "
        + "against the previous kept build (captures kept only when they differ).",
        subcommands = {Builds.ListBuilds.class, Builds.Check.class, Builds.Table.class, Builds.Plan.class, Builds.Resolve.class, Builds.Record.class})
    static final class Builds implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "Prints the Windows release builds of the range, oldest first, with what data/bds/builds.json says of each.")
        static final class ListBuilds extends Rooted implements Callable<Integer> {
            @Option(names = "--from", required = true, description = "First build, e.g. 1.26.30") String from;
            @Option(names = "--to", description = "Last build or line, e.g. 1.26.63 (default: the newest)") String to;

            @Override
            public Integer call() throws Exception {
                BuildHistory.BuildList list = BuildHistory.fetchWithCurrentRelease();
                Map<String, BuildHistory.Entry> record = BuildHistory.read(root());
                for (String build : list.range(from, to)) {
                    BuildHistory.Entry entry = record.get(build);
                    BuildHistory.Entry target = entry == null || entry.kept() ? null : record.get(entry.identicalTo());
                    System.out.println(build + (list.fromLinksApi().contains(build) ? " (links API)" : "")
                        + (entry == null ? "  not checked" : "  protocol " + entry.protocol()
                        + (entry.kept() ? "  kept" : "  identical to " + entry.identicalTo()
                        + (target != null && target.protocol() != entry.protocol() ? " (protocol " + target.protocol() + ")" : ""))));
                }
                return 0;
            }
        }

        @Command(name = "check", description = "Checks the builds of the range that data/bds/builds.json lacks, oldest first, with "
            + "`update --against <kept build>` (Windows BDS), and records each in data/bds/builds.json.")
        static final class Check extends Rooted implements Callable<Integer> {
            @Option(names = "--from", required = true, description = "First build, e.g. 1.26.30") String from;
            @Option(names = "--to", description = "Last build or line (default: the newest)") String to;
            @Option(names = "--limit", description = "At most this many runs (default: all)") Integer limit;
            @Option(names = "--min-free-mb", description = "Stop when the cache's disk has less free (default 1536)") Long minFreeMb;
            @Option(names = "--cache", description = BdsOptions.CACHE) Path cache;
            @Option(names = "--port", defaultValue = "19260", description = "The server port of each run (default: ${DEFAULT-VALUE})") int port;
            @Option(names = "--timeout", defaultValue = "300", description = "Seconds each server gets to start (default: ${DEFAULT-VALUE})") int timeout;

            @Override
            public Integer call() throws Exception {
                BuildHistory.BuildList list = BuildHistory.fetchWithCurrentRelease();
                BdsRun run = new BdsRun(null, false, cache, port, timeout, false);
                return BuildHistory.check(root(), list, list.range(from, to), run.cache(), BuildHistory.updates(root(), run),
                    limit == null ? Integer.MAX_VALUE : limit, (minFreeMb == null ? 1536 : minFreeMb) << 20);
            }
        }

        @Command(name = "plan", description = "The builds a full recapture captures (every kept release build and the preview with captures in "
            + "data/bds, read from the checkout without the network) as a GitHub Actions matrix, written to --output (one line of JSON) and listed on stdout.")
        static final class Plan extends Rooted implements Callable<Integer> {
            @Option(names = "--output", required = true, description = "File for the matrix JSON") Path output;
            @Option(names = "--build", description = "Only this build of the plan (repeatable; a rehearsal); a build the plan lacks fails")
            List<String> builds;

            @Override
            public Integer call() throws Exception {
                List<RecapturePlan.Leg> legs = RecapturePlan.only(RecapturePlan.of(root()), builds);
                Files.writeString(output, RecapturePlan.matrix(legs) + "\n");
                RecapturePlan.frozen(root()).forEach(line -> System.out.println("SKIPPED " + line));
                legs.forEach(leg -> System.out.println(leg.build() + (leg.preview() ? " (preview)" : "") + (leg.against() == null ? "" : " (history check)")));
                return legs.isEmpty() ? 1 : 0;
            }
        }

        @Command(name = "record", description = "Brings data/bds/builds.json up to date with the committed data, without BDS or the "
            + "network: records release builds with captures it lacks, and the sha256 of every kept build's zips from the cache "
            + "(unknown without a zip); freezes builds whose zip can no longer be downloaded. Writes the build table too.")
        static final class Record extends Rooted implements Callable<Integer> {
            @Option(names = "--cache", description = BdsOptions.CACHE) Path cache;
            @Option(names = "--freeze", description = "BUILD=REASON: a kept build whose zip minecraft.net no longer serves; a recapture skips it")
            Map<String, String> freeze;
            @Option(names = "--unfreeze", description = "A frozen build whose zip can be downloaded again") List<String> unfreeze;

            @Override
            public Integer call() throws Exception {
                return BuildHistory.record(root(), cache != null ? cache : BdsDistribution.defaultCache(),
                    freeze == null ? Map.of() : freeze, unfreeze == null ? List.of() : unfreeze);
            }
        }

        @Command(name = "resolve", description = "Prints the newest Windows build of a channel that the download links API names "
            + "(the build the BDS data workflow checks), and writes it to --output.")
        static final class Resolve implements Callable<Integer> {
            @Option(names = "--channel", required = true, description = "The channel: ${COMPLETION-CANDIDATES}") BuildOptions.Channel channel;
            @Option(names = "--output", description = "File for the build number") Path output;

            @Override
            public Integer call() throws Exception {
                String version = BdsDistribution.resolve(false, channel == BuildOptions.Channel.PREVIEW, null).version();
                if (output != null) {
                    Files.writeString(output, version + "\n");
                }
                System.out.println(version);
                return 0;
            }
        }

        @Command(name = "table", description = "Writes what data/bds/builds.json says per bundled data version: the library's "
            + "builds.txt and data/bds/builds.md (BuildTable). --check fails if they are stale instead.")
        static final class Table extends Rooted implements Callable<Integer> {
            @Option(names = "--check", description = "Fail if the committed files differ instead of writing them") boolean check;

            @Override
            public Integer call() throws Exception {
                return BuildTable.run(root(), check) ? 0 : 1;
            }
        }
    }
}
