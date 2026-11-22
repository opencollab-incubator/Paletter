package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.update.BuildHistory;
import dev.opencollab.paletter.tools.update.BuildTable;
import dev.opencollab.paletter.tools.update.CreativeProbe;
import dev.opencollab.paletter.tools.update.EducationProbe;
import dev.opencollab.paletter.tools.update.RecapturePlan;
import dev.opencollab.paletter.tools.update.SpecCapture;
import dev.opencollab.paletter.tools.update.Update;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static dev.opencollab.paletter.tools.Cli.flag;
import static dev.opencollab.paletter.tools.Cli.put;

/**
 * The commands that run BDS to bring the data up to date: {@code update}, {@code builds}, {@code spec-capture},
 * {@code creative-probe}, {@code education-capture}, {@code recipes-capture}.
 */
final class UpdateCommands {
    private UpdateCommands() {
    }

    @Command(name = "update", description = "The whole pipeline: newest BDS of the channel, Script API dump, vanilla and add-on network captures "
        + "(hashed and sequential block ids), comparison with the committed data, import or refresh, component shape comparison, markdown report. "
        + "A preview is imported as a preview version. The report goes to data/bds/VERSION/update-report.md when the update changed "
        + "data, else only to the run's work directory. "
        + "--verify-only writes nothing and exits with 1 on any difference; an update exits with 2 when the report lists TODOs.")
    static final class UpdateCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--channel", required = true, description = "release or preview") String channel;
        @Option(names = "--version") String version;
        @Option(names = "--verify-only") boolean verifyOnly;
        @Option(names = "--keep-server") boolean keepServer;
        @Option(names = "--cache") String cache;
        @Option(names = "--report") String report;
        @Option(names = "--name") String name;
        @Option(names = "--experiments", description = "a,b") String experiments;
        @Option(names = "--experiment-attribution", description = "each, all or none") String attribution;
        @Option(names = "--port") String port;
        @Option(names = "--timeout", description = "seconds") String timeout;
        @Option(names = "--against", description = "A history check (see `builds`): compare with the committed captures of this build "
            + "instead of the newest, and write only data/bds/VERSION, when anything differs") String against;
        @Option(names = "--capture-only", description = "The capture half of a full recapture: write the captures of the build (the files that "
            + "would go to data/bds/VERSION, and spec/) into this directory and nothing into the repository; the report goes to the work directory") String captureOnly;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            options.put("channel", channel);
            put(options, "against", against);
            put(options, "capture-only", captureOnly);
            put(options, "version", version);
            flag(options, "verify-only", verifyOnly);
            flag(options, "keep-server", keepServer);
            put(options, "cache", cache);
            put(options, "report", report);
            put(options, "name", name);
            put(options, "experiments", experiments);
            put(options, "experiment-attribution", attribution);
            put(options, "port", port);
            put(options, "timeout", timeout);
            return new Update(root(), options).run();
        }
    }

    @Command(name = "spec-capture", description = "Generates the spec add-on from data/model, loads it in BDS with experiments on and writes the "
        + "content log, the custom item registry and block properties and variants.json to data/bds/VERSION/spec (input of infer).")
    static final class SpecCaptureCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--channel") String channel;
        @Option(names = "--version") String version;
        @Option(names = "--out") String out;
        @Option(names = "--keep-server") boolean keepServer;
        @Option(names = "--cache") String cache;
        @Option(names = "--port") String port;
        @Option(names = "--timeout") String timeout;
        @Option(names = "--probe-keys", description = "Ask BDS about keys the docs do not list (from the BDS binary, wiki.bedrock.dev and vanilla packs) "
            + "instead of the spec; writes data/bds/VERSION/spec-keys and data/model/bds-keys.json") boolean probeKeys;
        @Option(names = "--format-sweep", description = "Capture the components at the format versions around their Versioned boundaries "
            + "instead of the spec; writes data/bds/VERSION/spec-format with format-sweep.json") boolean formatSweep;
        @Option(names = "--gating", description = "Load only the experiment-gated components in a world without experiments, to verify what the creator docs "
            + "say of their experiments; writes data/bds/VERSION/spec-gating with gating.json") boolean gating;
        @Option(names = "--vanilla-pack", description = "A pack with vanilla block and item files, a source of keys for --probe-keys") String vanillaPack;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            put(options, "channel", channel);
            put(options, "version", version);
            put(options, "out", out);
            flag(options, "keep-server", keepServer);
            flag(options, "probe-keys", probeKeys);
            flag(options, "format-sweep", formatSweep);
            flag(options, "gating", gating);
            put(options, "vanilla-pack", vanillaPack);
            put(options, "cache", cache);
            put(options, "port", port);
            put(options, "timeout", timeout);
            return new SpecCapture(root(), options).run();
        }
    }

    @Command(name = "creative-probe", description = "Runs a creative add-on on BDS and records the creative inventory order.")
    static final class CreativeProbeCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--addon", required = true) String addon;
        @Option(names = "--out", required = true) String out;
        @Option(names = "--cache") String cache;
        @Option(names = "--version") String version;
        @Option(names = "--port") String port;
        @Option(names = "--starts") String starts;
        @Option(names = "--protocol") String protocol;
        @Option(names = "--reference") String reference;
        @Option(names = "--experiments") String experiments;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            put(options, "addon", addon);
            put(options, "out", out);
            put(options, "cache", cache);
            put(options, "version", version);
            put(options, "port", port);
            put(options, "starts", starts);
            put(options, "protocol", protocol);
            put(options, "reference", reference);
            put(options, "experiments", experiments);
            CreativeProbe.run(root(), options);
            return 0;
        }
    }

    @Command(name = "education-capture", description = "Starts a world on BDS with Education Edition features off and on, captures both and "
        + "writes the identifiers only the education world has to data/bds/VERSION/education.json (input of constants generate).")
    static final class EducationCaptureCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--version") String version;
        @Option(names = "--cache") String cache;
        @Option(names = "--port") String port;
        @Option(names = "--reference") String reference;
        @Option(names = "--out") String out;
        @Option(names = "--timeout") String timeout;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            put(options, "version", version);
            put(options, "cache", cache);
            put(options, "port", port);
            put(options, "reference", reference);
            put(options, "out", out);
            put(options, "timeout", timeout);
            EducationProbe.run(root(), options);
            return 0;
        }
    }

    @Command(name = "recipes-capture", description = "Captures a vanilla world of one BDS build and writes only its crafting and trim data "
        + "to data/bds/VERSION/vanilla.")
    static final class RecipesCaptureCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--version", required = true) String version;
        @Option(names = "--preview") boolean preview;
        @Option(names = "--cache") String cache;
        @Option(names = "--port") String port;
        @Option(names = "--timeout") String timeout;
        @Option(names = "--keep-server") boolean keepServer;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            put(options, "version", version);
            put(options, "cache", cache);
            put(options, "port", port);
            put(options, "timeout", timeout);
            flag(options, "preview", preview);
            flag(options, "keep-server", keepServer);
            dev.opencollab.paletter.tools.update.RecipeProbe.run(root(), options);
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
            @Option(names = "--cache") String cache;
            @Option(names = "--port") String port;
            @Option(names = "--timeout", description = "seconds") String timeout;

            @Override
            public Integer call() throws Exception {
                Map<String, String> options = new HashMap<>();
                put(options, "cache", cache);
                put(options, "port", port);
                put(options, "timeout", timeout);
                BuildHistory.BuildList list = BuildHistory.fetchWithCurrentRelease();
                return BuildHistory.check(root(), list, list.range(from, to), cache != null ? Path.of(cache) : BdsDistribution.defaultCache(),
                    BuildHistory.updates(root(), options), limit == null ? Integer.MAX_VALUE : limit, (minFreeMb == null ? 1536 : minFreeMb) << 20);
            }
        }

        @Command(name = "plan", description = "The builds a full recapture captures (every kept release build, the newest unchecked build after "
            + "each data version's newest kept build, and the preview with captures in data/bds) as a GitHub Actions matrix, written to --output (one line of JSON) and listed on stdout.")
        static final class Plan extends Rooted implements Callable<Integer> {
            @Option(names = "--output", required = true, description = "File for the matrix JSON") Path output;

            @Override
            public Integer call() throws Exception {
                List<RecapturePlan.Leg> legs = RecapturePlan.of(root(), BuildHistory.fetchWithCurrentRelease());
                Files.writeString(output, RecapturePlan.matrix(legs) + "\n");
                RecapturePlan.frozen(root()).forEach(line -> System.out.println("SKIPPED " + line));
                legs.forEach(leg -> System.out.println(leg.build() + (leg.preview() ? " (preview)" : "")
                    + (leg.against() == null ? "" : leg.against().equals(leg.build()) ? " (history check)" : " (not checked yet, compared with " + leg.against() + ")")));
                return legs.isEmpty() ? 1 : 0;
            }
        }

        @Command(name = "record", description = "Brings data/bds/builds.json up to date with the committed data, without BDS or the "
            + "network: records release builds with captures it lacks, and the sha256 of every kept build's zips from the cache "
            + "(unknown without a zip); freezes builds whose zip can no longer be downloaded. Writes the build table too.")
        static final class Record extends Rooted implements Callable<Integer> {
            @Option(names = "--cache", description = "The BDS cache (default: $PALETTER_BDS_CACHE or the Gradle user home's)") Path cache;
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
            @Option(names = "--channel", required = true, description = "release or preview") String channel;
            @Option(names = "--output", description = "File for the build number") Path output;

            @Override
            public Integer call() throws Exception {
                if (!channel.equals("release") && !channel.equals("preview")) {
                    throw new CommandLine.ParameterException(new CommandLine(this), "--channel must be release or preview: " + channel);
                }
                String version = BdsDistribution.resolve(false, channel.equals("preview"), null).version();
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
