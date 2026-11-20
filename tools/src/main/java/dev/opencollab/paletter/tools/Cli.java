package dev.opencollab.paletter.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.BdsInventory;
import dev.opencollab.paletter.tools.bds.CapturePlatform;
import dev.opencollab.paletter.tools.bds.ScriptApiDump;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.client.CaptureClient;
import dev.opencollab.paletter.tools.client.CaptureWriter;
import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.client.LevelDat;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.data.Export;
import dev.opencollab.paletter.tools.data.Checker;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.data.WireShapes;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.SchemaExtract;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.addon.FixedAddons;
import dev.opencollab.paletter.tools.model.docs.DocsFetcher;
import dev.opencollab.paletter.tools.model.docs.DocsParser;
import dev.opencollab.paletter.tools.model.infer.MappingInference;
import dev.opencollab.paletter.tools.update.BuildHistory;
import dev.opencollab.paletter.tools.update.BuildTable;
import dev.opencollab.paletter.tools.update.CreativeProbe;
import dev.opencollab.paletter.tools.update.EducationProbe;
import dev.opencollab.paletter.tools.update.ExperimentMarks;
import dev.opencollab.paletter.tools.update.RecapturePlan;
import dev.opencollab.paletter.tools.update.SpecCapture;
import dev.opencollab.paletter.tools.update.Update;
import dev.opencollab.paletter.tools.whitebox.Whitebox;
import dev.opencollab.paletter.tools.whitebox.WhiteboxCoverage;
import dev.opencollab.paletter.tools.whitebox.WhiteboxDiff;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The one command line of the tooling: {@code ./gradlew :tools:run --args="<command> ..."}. Paths are relative to the
 * working directory, which is the repository root for Gradle; {@code --root} overrides it where a command reads the
 * repository. See docs/data-pipeline.md for what each command does.
 */
@Command(name = "paletter-tools", mixinStandardHelpOptions = true, subcommands = {
    Cli.Import.class, Cli.BdsInventoryCommand.class, Cli.BdsSchemaCommand.class, Cli.WhiteboxCommand.class, Cli.WhiteboxCoverageCommand.class, Cli.BdsCompareCommand.class, Cli.UpdateCommand.class, Cli.Builds.class, Cli.SpecCaptureCommand.class,
    Cli.VerifyCommand.class, Cli.ExportCommand.class, Cli.CheckData.class, Cli.DataDiffCommand.class, Cli.CreativeProbeCommand.class, Cli.EducationCaptureCommand.class, Cli.ExperimentMarksCommand.class,
    Cli.CaptureCommand.class, Cli.Experiments.class, Cli.CompareCommand.class,
    Cli.Docs.class, Cli.Addon.class, Cli.Infer.class, Cli.CheckMappings.class,
    Cli.Constants.class, Cli.Components.class, Cli.InspectCommand.class, Cli.BehaviourCommand.class, Cli.BehaviourProbeCommand.class, Cli.BehaviourDumpCommand.class, Cli.ScriptDumpCommand.class,
    Cli.VoxelShapesCommand.class, Cli.RecipesCaptureCommand.class, Cli.RecipesDiffCommand.class, Cli.RecipesImportCommand.class
})
public final class Cli implements Runnable {
    /** The docs commit the committed model was generated from (MicrosoftDocs/minecraft-creator main, 2026-11-06). */
    public static final String DEFAULT_COMMIT = "457cdeaa5aa428429884b092895f3979b1f69ad3";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** Repository root: the working directory unless {@code --root} says otherwise. */
    abstract static class Rooted {
        @Option(names = "--root", description = "Repository root (default: the working directory)")
        Path rootOption;

        Path root() {
            return (rootOption == null ? Path.of("") : rootOption).toAbsolutePath();
        }

        Path resources() {
            return root().resolve("paletter/src/main/resources/paletter");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bundled data and BDS

    @Command(name = "import", description = "Converts a palette dump (CloudburstMC/Data layout, as produced from BDS) into the bundled format. "
        + "Paletter bundles no fallbacks. With --bds-dump the block to item relation, item tags and block defaults are imported too.")
    static final class Import extends Rooted implements Callable<Integer> {
        @Option(names = "--source", required = true) Path source;
        @Option(names = "--name", required = true, description = "Version name, e.g. 26.60") String name;
        @Option(names = "--protocol", required = true) int protocol;
        @Option(names = "--item-tags") Path itemTags;
        @Option(names = "--bds-dump") Path bdsDump;

        @Override
        public Integer call() throws Exception {
            new Importer(resources()).run(source, name, protocol, itemTags, bdsDump);
            return 0;
        }
    }

    @Command(name = "data-diff", description = "Diffs two bundled data files (network data, creative, tags, defaults, block to item relation). Exits with 1 on any difference.")
    static final class DataDiffCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--before", required = true) Path before;
        @Option(names = "--after", required = true) Path after;

        @Override
        public Integer call() throws Exception {
            Diff diff = Diff.vanilla(readPalette(before), readPalette(after), true, true);
            System.out.print(diff.markdown(Diff.DEFAULT_MAX_LINES));
            return diff.identical() ? 0 : 1;
        }
    }

    @Command(name = "bds-inventory", description = "Reads the strings of the Linux bedrock_server of a BDS build (downloaded into the cache, not run) "
        + "and writes data/bds/<VERSION>/inventory.json: block components and their JSON keys, item components, every Script API class and "
        + "member; prints what changed against the inventory of the previous build. Downloading BDS means accepting the Minecraft EULA.")
    static final class BdsInventoryCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", required = true, description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--cache") Path cache;

        @Override
        public Integer call() throws Exception {
            Path file = BdsInventory.write(root(), bds, cache);
            Path previous = BdsInventory.previous(root().resolve(Layout.BDS), bds);
            if (previous != null) {
                List<String> diff = BdsInventory.diff(
                    BdsInventory.parse(previous), BdsInventory.parse(file));
                System.out.println(diff.size() + " changes against " + previous + (diff.isEmpty() ? "" : ":"));
                diff.forEach(System.out::println);
            }
            return 0;
        }
    }

    @Command(name = "bds-schema", description = "Extracts data/bds/<VERSION>/schema.json (keys, types, enums, limits of every item and block "
        + "component) from the json_schemas of BDS's documentation generator; --docs is its docs directory (update keeps the one of a build "
        + "in the cache, docgen/<VERSION>). Prints what changed against the schema of the previous build. Needs no BDS run.")
    static final class BdsSchemaCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", required = true, description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--docs", required = true, description = "the docs directory BDS generated") Path docs;

        @Override
        public Integer call() throws Exception {
            Path bdsData = root().resolve(Layout.BDS);
            Schema previous = Schema.newest(bdsData, bds);
            Path file = bdsData.resolve(bds).resolve(Schema.FILE);
            SchemaExtract.write(docs, bds, file);
            Schema schema = Schema.read(file);
            System.out.println(file + ": " + schema.summary());
            if (previous != Schema.NONE) {
                List<String> diff = Schema.diff(previous, schema);
                System.out.println(diff.size() + " changes against the schema of " + previous.build() + ":");
                diff.forEach(System.out::println);
            }
            return 0;
        }
    }

    @Command(name = "whitebox", description = "Reads the code of the server binary of a BDS build (downloaded into the cache, not run) and writes "
        + "data/bds/<VERSION>/whitebox.json: every registered item and block component, whether it is sent to the client, whether reflection or "
        + "hand-written code writes it, the NBT keys the code mentions, the envelopes and the traits; prints what changed against the previous "
        + "build. Downloading BDS means accepting the Minecraft EULA.")
    static final class WhiteboxCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", required = true, description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--preview", description = "The version is a preview build") boolean preview;
        @Option(names = "--platform", defaultValue = "linux", description = "linux (default: the build that names its classes and shows the tag types) "
            + "or windows (a cross-check, written to whitebox.windows.json)") String platform;
        @Option(names = "--binary", description = "The server binary or a BDS zip, instead of downloading") Path binary;
        @Option(names = "--cache") Path cache;

        @Override
        public Integer call() throws Exception {
            boolean linux = !platform.equals("windows");
            Path source = binary != null ? binary
                : BdsDistribution.resolve(linux, preview, bds).zip(cache == null ? BdsDistribution.defaultCache() : cache);
            JsonObject facts = Whitebox.discover(source, bds);
            String name = Whitebox.file(facts.get("platform").getAsString());
            Path file = root().resolve(Layout.BDS).resolve(bds).resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Whitebox.json(facts));
            System.out.println("Wrote " + file);
            facts.getAsJsonArray("notes").forEach(note -> System.out.println("note: " + note.getAsString()));
            Path previous = Whitebox.previous(root().resolve(Layout.BDS), bds, name);
            if (previous != null) {
                List<String> diff = WhiteboxDiff.diff(Whitebox.parse(previous), facts);
                System.out.println(diff.size() + " changes against " + previous + (diff.isEmpty() ? "" : ":"));
                diff.forEach(System.out::println);
            }
            return 0;
        }
    }

    @Command(name = "whitebox-coverage", description = "Compares what the code of a build can send (whitebox.json) with what its captures show and "
        + "writes data/bds/<VERSION>/whitebox-coverage.md: the network components and keys no capture has.")
    static final class WhiteboxCoverageCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", description = "BDS version (default: every build with a whitebox.json)") String bds;
        @Option(names = "--check", description = "Write nothing; exit with 1 if a committed report differs from what the committed data gives") boolean check;
        @Option(names = "--strict", description = "Exit with 1 if any gap is left") boolean strict;

        @Override
        public Integer call() throws Exception {
            List<String> builds = new ArrayList<>();
            if (bds != null) {
                builds.add(bds);
            } else {
                try (var list = Files.list(root().resolve(Layout.BDS))) {
                    list.filter(build -> Files.isRegularFile(build.resolve(Whitebox.FILE))).map(build -> build.getFileName().toString()).sorted().forEach(builds::add);
                }
            }
            boolean failed = false;
            for (String build : builds) {
                Path report = root().resolve(Layout.BDS).resolve(build).resolve(WhiteboxCoverage.FILE);
                String committed = Files.isRegularFile(report) ? Files.readString(report) : null;
                if (check) {
                    String now = WhiteboxCoverage.report(root(), build);
                    if (!now.equals(committed)) {
                        System.out.println(report + " is stale: run whitebox-coverage and review what changed");
                        failed = true;
                    }
                    continue;
                }
                List<WhiteboxCoverage.Gap> gaps = WhiteboxCoverage.write(root(), build);
                System.out.println(build + ": " + gaps.size() + " gaps, written to " + report);
                failed |= strict && !gaps.isEmpty();
            }
            return failed ? 1 : 0;
        }
    }

    @Command(name = "bds-compare", description = "Cross-checks a BDS dump with a bundled version: block states and defaults against the block palette, "
        + "items and stack sizes, item tags against the bundled ones (and against --item-tags, e.g. Geyser's item_tags.json).")
    static final class BdsCompareCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds-dump", required = true) Path bdsDump;
        @Option(names = "--name", required = true) String name;
        @Option(names = "--item-tags") Path itemTags;

        @Override
        public Integer call() throws Exception {
            Map<String, Map<String, List<String>>> tagSources = new LinkedHashMap<>();
            VanillaData data = readPalette(resources().resolve(name + ".nbt"));
            tagSources.put("the bundled " + data.version().name() + " tags", data.itemTags());
            if (itemTags != null) {
                tagSources.put(itemTags.toString(), Importer.readItemTags(itemTags));
            }
            Diff.bdsDump(BdsDump.read(bdsDump), data, tagSources).print(System.out);
            return 0;
        }
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

    @Command(name = "verify", description = "Diffs a directory in the CloudburstMC/Data layout (or Geyser's versioned names) against the bundled version "
        + "(or the Paletter data file --bundled), in memory. Writes nothing but the report; exits with 1 on any difference.")
    static final class VerifyCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--source", required = true) Path source;
        @Option(names = "--name", required = true) String name;
        @Option(names = "--bundled") Path bundled;
        @Option(names = "--against", description = "A directory written by export (what a compiled palette sends) in place of the bundled version")
        Path against;
        @Option(names = "--report") Path report;
        @Option(names = "--max") Integer max;

        @Override
        public Integer call() throws Exception {
            Diff verify;
            if (against != null) {
                verify = Diff.against(source, name, against);
            } else {
                Path file = bundled != null ? bundled : resources().resolve(name + ".nbt");
                verify = Diff.source(source, name, readPalette(file), file.toString());
            }
            int lines = max != null ? max : Diff.DEFAULT_MAX_LINES;
            System.out.print(verify.markdown(lines));
            if (report != null) {
                verify.write(report, lines);
            }
            return verify.identical() ? 0 : 1;
        }
    }

    @Command(name = "export", description = "Writes what a compiled palette of a data version sends (the block palette with each runtime id as network_id, "
        + "the item registry, the creative content) in the file formats of CloudburstMC/Data, with a _generated marker. Compare it with a "
        + "cb/data checkout: verify --source <cb/data> --name <version> --against <directory>.")
    static final class ExportCommand implements Callable<Integer> {
        @Option(names = "--name", required = true, description = "The data version, e.g. 26.60") String name;
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--previews", description = "Also offer the preview versions") boolean previews;
        @Option(names = "--all-experiments", description = "Enable every experiment toggle, as CloudburstMC/Data dumps") boolean allExperiments;

        @Override
        public Integer call() throws Exception {
            dev.opencollab.paletter.Paletter paletter;
            if (previews || allExperiments) {
                dev.opencollab.paletter.Paletter.Builder builder = dev.opencollab.paletter.Paletter.builder().previews(previews);
                paletter = (allExperiments ? builder.allExperiments() : builder).build();
            } else {
                paletter = dev.opencollab.paletter.Paletter.bundled();
            }
            dev.opencollab.paletter.data.DataVersion version = paletter.version(name);
            if (version == null) {
                System.err.println("No data version " + name + " (--previews offers the preview versions)");
                return 1;
            }
            dev.opencollab.paletter.Palette palette = paletter.vanilla(version);
            Export.write(palette, out);
            System.out.println("Wrote " + palette.blocks().size() + " block states, " + palette.itemDefinitions().size() + " items and "
                + palette.creative().size() + " creative entries of " + name + " to " + out);
            return 0;
        }
    }

    @Command(name = "check-data", description = "Lists what older bundled versions lack of the newest one: new content falls back to unknown "
        + "unless a consumer defines a fallback (Paletter bundles none), and content older versions only have behind an experiment toggle. Exits with 1 on problems of the data.")
    static final class CheckData extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            return new Checker(resources()).run() ? 0 : 1;
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

    @Command(name = "experiment-marks", description = "Writes the experiment marks (content only there behind a toggle) of the bundled versions "
        + "from the experiment records data/bds/*/experiments.json.")
    static final class ExperimentMarksCommand extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            System.out.println("Changed: " + ExperimentMarks.apply(root()));
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

    @Command(name = "recipes-diff", description = "Diffs the recipe captures of two builds in data/bds (normalised: list layout, net ids and random UUIDs ignored).")
    static final class RecipesDiffCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--from", required = true) String from;
        @Option(names = "--to", required = true) String to;

        @Override
        public Integer call() throws Exception {
            Path data = root().resolve("data/bds");
            Diff diff = Diff.recipes(CapturedData.recipes(data.resolve(from).resolve("vanilla")), CapturedData.recipes(data.resolve(to).resolve("vanilla")));
            diff.print(System.out);
            System.out.println(diff.identical() ? "IDENTICAL" : "DIFFERENT");
            return 0;
        }
    }

    @Command(name = "recipes-import", description = "The recipe captures of data/bds -> the bundled recipes/<version>.nbt of paletter-recipes (--check: fail if they differ).")
    static final class RecipesImportCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--check", description = "Write nothing; fail if the bundled files are not what the captures give") boolean check;

        @Override
        public Integer call() throws Exception {
            if (!check) {
                dev.opencollab.paletter.tools.data.RecipeImport.run(root());
                return 0;
            }
            List<String> problems = dev.opencollab.paletter.tools.data.RecipeImport.check(root());
            problems.forEach(System.err::println);
            if (!problems.isEmpty()) {
                System.err.println("Run `recipes-import`");
            }
            return problems.isEmpty() ? 0 : 1;
        }
    }

    @Command(name = "voxel-shapes", description = "Bedrock's voxel shapes: capture what a BDS build says about them",
        subcommands = {VoxelShapesCommand.CaptureShapes.class, VoxelShapesCommand.ImportShapes.class, VoxelShapesCommand.CheckShapes.class})
    static final class VoxelShapesCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "capture", description = "Runs BDS without packs, with the voxel shape experiment and with the probe add-on (shapes with "
            + "varied boxes) and writes the voxel shapes packet of each, with the boxes that went in, to data/bds/VERSION/voxel-shapes.")
        static final class CaptureShapes extends Rooted implements Callable<Integer> {
            @Option(names = "--version") String version;
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
                flag(options, "keep-server", keepServer);
                dev.opencollab.paletter.tools.shape.ShapeProbe.run(root(), options);
                return 0;
            }
        }

        @Command(name = "import", description = "The captures of data/bds/*/voxel-shapes -> the bundled voxel_shapes.nbt")
        static final class ImportShapes extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                dev.opencollab.paletter.tools.shape.ShapeImport.run(root());
                return 0;
            }
        }

        @Command(name = "check", description = "import, but fail if the bundled voxel_shapes.nbt differs")
        static final class CheckShapes extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                if (!dev.opencollab.paletter.tools.shape.ShapeImport.check(root())) {
                    System.err.println("voxel_shapes.nbt is not what the captures of data/bds/*/voxel-shapes give; run `voxel-shapes import`");
                    return 1;
                }
                return 0;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The Bedrock capture client

    @Command(name = "capture", description = "Records what a Bedrock Dedicated Server sends a client. See docs/wire-notes.md.")
    static final class CaptureCommand implements Callable<Integer> {
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--custom-out") Path customOut;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "19140") int port;
        @Option(names = "--reference", defaultValue = "26.60") String reference;
        @Option(names = "--bds-version", defaultValue = "unknown") String bdsVersion;
        @Option(names = "--bds-platform", description = "windows or linux: the OS the server runs on (default: this one's)") String bdsPlatform;
        @Option(names = "--timeout", defaultValue = "90") long timeout;
        @Option(names = "--transport", defaultValue = "raknet", description = "raknet or nethernet") String transport;
        @Option(names = "--protocol") Integer protocol;
        @Option(names = "--wire-protocol", description = "Speak the codec's format but announce this protocol number") Integer wireProtocol;

        @Override
        public Integer call() throws Exception {
            if (transport.equals("nethernet")) {
                InetSocketAddress address = new InetSocketAddress(host, port);
                String[] status = Capture.netherNetPing(address, 10);
                if (status == null) {
                    throw new IllegalStateException("No NetherNet signaling on " + address);
                }
                System.out.println("NetherNet status: " + String.join(", ", status));
                Capture.Summary summary = Capture.capture(address, Integer.parseInt(status[0]), reference(reference), bdsVersion,
                    platform(), out, customOut, timeout, true);
                System.out.println(summary);
                return 0;
            }
            int wanted = protocol != null ? protocol : Capture.NEWEST.getProtocolVersion();
            BedrockCodec codec = Capture.codecFor(wanted);
            if (codec.getProtocolVersion() != wanted) {
                throw new IllegalArgumentException("No codec for protocol " + wanted + "; use --wire-protocol to probe with the newest codec");
            }
            codec = Capture.clientCodec(codec);
            if (wireProtocol != null) {
                codec = codec.toBuilder().protocolVersion(wireProtocol).build();
            }
            InetSocketAddress address = new InetSocketAddress(host, port);
            System.out.println("Connecting to " + address + " with protocol " + codec.getProtocolVersion() + " (" + codec.getMinecraftVersion() + ")");
            CaptureClient.Result result = new CaptureClient(codec, address).run(timeout);
            System.out.printf("Captured: %d block properties, %d items, %d creative groups, %d creative items, packets %s%n",
                result.startGame.getBlockProperties().size(), result.items.size(), result.creative.getGroups().size(),
                result.creative.getContents().size(), result.packetCounts);
            CaptureWriter writer = new CaptureWriter(result, reference(reference), bdsVersion, platform())
                .withProtocol(codec.getProtocolVersion(), wanted);
            writer.write(out, customOut);
            System.out.println("Wrote " + out + (writer.unresolvedHashes() > 0
                ? "; WARNING: " + writer.unresolvedHashes() + " creative block ids do not match any palette hash" : "; all creative block ids match palette hashes"));
            return 0;
        }

        private String platform() {
            if (bdsPlatform == null) {
                return CapturePlatform.host();
            }
            if (!bdsPlatform.equals(CapturePlatform.WINDOWS) && !bdsPlatform.equals(CapturePlatform.LINUX)) {
                throw new IllegalArgumentException("--bds-platform must be " + CapturePlatform.WINDOWS + " or " + CapturePlatform.LINUX);
            }
            return bdsPlatform;
        }
    }

    @Command(name = "experiments", description = "Enables experiment toggles in a level.dat.")
    static final class Experiments implements Callable<Integer> {
        @Option(names = "--level-dat", required = true) Path levelDat;
        @Option(names = "--enable", required = true, split = ",", description = "name,name,...") List<String> enable;

        @Override
        public Integer call() throws Exception {
            LevelDat.enableExperiments(levelDat, enable);
            return 0;
        }
    }

    @Command(name = "compare", description = "Diffs a capture directory against a bundled version.")
    static final class CompareCommand implements Callable<Integer> {
        @Option(names = "--capture", required = true) Path capture;
        @Option(names = "--reference", defaultValue = "26.60") String reference;

        @Override
        public Integer call() throws Exception {
            Diff diff = Diff.capture(CapturedData.read(capture), reference(reference));
            diff.print(System.out);
            System.out.println(diff.identical() ? "IDENTICAL" : "DIFFERENT");
            return diff.identical() ? 0 : 1;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The component model

    @Command(name = "docs", description = "Creator docs -> data/model/components.json. --docs is a checkout of MicrosoftDocs/minecraft-creator "
        + "(commit from git); otherwise the pages of --commit (default: the commit components.json was made from) are downloaded into the cache.")
    static final class Docs extends Rooted implements Callable<Integer> {
        @Option(names = "--docs") Path docs;
        @Option(names = "--commit") String commit;

        @Override
        public Integer call() throws Exception {
            Path model = root().resolve(Layout.MODEL);
            DocsParser.DocsSource source;
            if (docs != null) {
                source = DocsParser.DocsSource.directory(docs, commit != null ? commit : gitHead(docs));
            } else {
                String sha = commit;
                if (sha == null && Files.exists(model.resolve("components.json"))) {
                    sha = Model.read(model.resolve("components.json")).commit();
                }
                source = new DocsFetcher(sha != null ? sha : DEFAULT_COMMIT, DocsFetcher.defaultCache());
            }
            Model parsed = new DocsParser(source).parse();
            parsed.write(model.resolve("components.json"));
            System.out.println(parsed.components().size() + " components, "
                + parsed.components().stream().mapToInt(c -> c.fields().size()).sum() + " fields -> " + model.resolve("components.json"));
            return 0;
        }

        private static String gitHead(Path docs) {
            try {
                Process git = new ProcessBuilder("git", "-C", docs.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
                String head = new String(git.getInputStream().readAllBytes()).trim();
                return git.waitFor() == 0 ? head : "unknown";
            } catch (Exception e) {
                return "unknown";
            }
        }
    }

    @Command(name = "addon", description = "Generates an add-on. Kinds: spec (data/model -> every documented component + variants.json, "
        + "the default), creative (data/addons/creative), creative-large (the 700 block probe)")
    static final class Addon extends Rooted implements Callable<Integer> {
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--kind", defaultValue = "spec", description = "spec, creative or creative-large") String kind;

        @Override
        public Integer call() throws Exception {
            switch (kind) {
                case "spec" -> {
                    Path model = root().resolve(Layout.MODEL);
                    new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model).withNewestSchema(null)).write(out);
                }
                case "creative" -> FixedAddons.writeCreative(out);
                case "creative-large" -> FixedAddons.writeCreativeLarge(out);
                default -> throw new IllegalArgumentException("Unknown --kind " + kind);
            }
            return 0;
        }
    }

    @Command(name = "infer", description = "The model and the spec captures -> mappings.json, coverage.md")
    static final class Infer extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            return new MappingInference(root()).run(false) ? 0 : 1;
        }
    }

    @Command(name = "check-mappings", description = "infer, but fail if the committed outputs differ")
    static final class CheckMappings extends Rooted implements Callable<Integer> {
        @Option(names = "--strict", description = "also fail on residual bits, captured wire paths nothing explains and fields whose "
            + "evidence another field decided (CI and update)")
        boolean strict;

        @Override
        public Integer call() throws Exception {
            MappingInference inference = new MappingInference(root());
            boolean ok = inference.run(true);
            if (!strict) {
                return ok ? 0 : 1;
            }
            List<String> unexplained = new ArrayList<>(inference.unexplainedBits());
            unexplained.addAll(inference.linkedFields());
            unexplained.addAll(dev.opencollab.paletter.tools.update.BuildTable.unexplained(dev.opencollab.paletter.tools.update.BuildTable.gaps(root())));
            Path model = root().resolve(Layout.MODEL);
            JsonObject mappings = JsonParser.parseString(Files.readString(model.resolve("mappings.json"))).getAsJsonObject();
            Model components = Fixtures.read(model).apply(Model.read(model.resolve("components.json")));
            // every build's spec capture; the paths with a reason are listed in coverage.md, which is compared above, so a
            // new one there is a reviewed change
            WireShapes.uncoveredByBuild(root().resolve(Layout.BDS), mappings, components).forEach((build, uncovered) -> {
                uncovered.forEach((reason, paths) -> System.out.println(build + ", " + reason + ":\n  " + String.join("\n  ", paths)));
                unexplained.addAll(WireShapes.unexplained(build, uncovered));
            });
            unexplained.forEach(line -> System.err.println("UNEXPLAINED: " + line));
            return ok && unexplained.isEmpty() ? 0 : 1;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Code generators

    @Command(name = "constants", description = "Vanilla constants classes: generate, or check that the committed ones are up to date",
        subcommands = {Constants.Generate.class, Constants.Check.class})
    static final class Constants implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "generate")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                dev.opencollab.paletter.tools.codegen.constants.Main.main(new String[]{"generate", "--root", root().toString()});
                return 0;
            }
        }

        @Command(name = "check")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                dev.opencollab.paletter.tools.codegen.constants.Main.main(new String[]{"check", "--root", root().toString()});
                return 0;
            }
        }
    }

    @Command(name = "inspect", description = "Explain what the bundled palettes do with a block state or an item variant")
    static final class InspectCommand implements Callable<Integer> {
        @Option(names = "--version", required = true, description = "Bundled version name, or all") String version;
        @Option(names = "--block", description = "Block: identifier or identifier[key=value,...], e.g. 'minecraft:oak_stairs[upside_down_bit=true]'") String block;
        @Option(names = "--item", description = "Item: identifier or identifier:damage, e.g. minecraft:potion:5") String item;

        @Override
        public Integer call() {
            dev.opencollab.paletter.tools.data.Inspect.run(dev.opencollab.paletter.Paletter.builder().build(), version, block, item, System.out);
            return 0;
        }
    }

    @Command(name = "behaviour", description = "Behaviour values of vanilla blocks and items (friction, shapes, max stack size, ...)",
        subcommands = {BehaviourCommand.Import.class})
    static final class BehaviourCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "import", description = "Reads the git history of the dump sources and the Script API dumps of data/bds, "
            + "writes resources/behaviour.nbt and data/behaviour/REPORT.md")
        static final class Import extends Rooted implements Callable<Integer> {
            @Option(names = "--cloudburst-data", description = "Checkout of https://github.com/CloudburstMC/Data") Path cloudburstData;
            @Option(names = "--allay", description = "Checkout of https://github.com/AllayMC/Allay (git clone --filter=blob:none is enough)") Path allay;

            @Override
            public Integer call() throws Exception {
                dev.opencollab.paletter.tools.behaviour.BehaviourImport.run(root(), cloudburstData, allay);
                return 0;
            }
        }
    }

    @Command(name = "behaviour-probe", description = "Runs data/addons/probe on BDS (--bds VERSION, extracted into the cache, or --server DIR) and "
        + "writes what it measured to data/bds/<BDS version>/behaviour-probe/ (probe.log, capture.json; input of behaviour import). "
        + "With --from-log it measures a console log of an earlier run instead of starting BDS. Downloading BDS means accepting the Minecraft EULA.")
    static final class BehaviourProbeCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--preview", description = "--bds is a preview build") boolean preview;
        @Option(names = "--server", description = "An extracted BDS of that version, instead of the cache") Path server;
        @Option(names = "--cache") Path cache;
        @Option(names = "--port", defaultValue = "19290") int port;
        @Option(names = "--timeout", defaultValue = "900") int timeout;
        @Option(names = "--log", description = "Where the console of the run goes (default: <cache>/runs/behaviour-probe.log)") Path log;
        @Option(names = "--from-log", description = "Measure this console log, do not start BDS") Path fromLog;

        @Override
        public Integer call() throws Exception {
            Path console = fromLog;
            if (console == null) {
                if (bds == null) {
                    throw new IllegalArgumentException("Missing --bds (or --from-log)");
                }
                console = log != null ? log : dev.opencollab.paletter.tools.bds.BdsDistribution.defaultCache().resolve("runs").resolve("behaviour-probe-" + bds + ".log");
                dev.opencollab.paletter.tools.behaviour.BehaviourProbe.run(root(), bds, preview, server, cache, port, timeout, console);
            }
            dev.opencollab.paletter.tools.behaviour.BehaviourProbe.write(root(), console);
            return 0;
        }
    }

    @Command(name = "behaviour-dump", description = "Runs data/addons/behaviour-dump on BDS (--bds VERSION, extracted into the cache, or --server DIR) "
        + "with the Beta APIs and writes what the Script API says about every block state and item to data/bds/<BDS version>/behaviour-dump.json.gz "
        + "(input of behaviour import and of the behaviour key check). With --from-log it reads a console log of an earlier run instead of "
        + "starting BDS. Downloading BDS means accepting the Minecraft EULA.")
    static final class BehaviourDumpCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--preview", description = "--bds is a preview build") boolean preview;
        @Option(names = "--server", description = "An extracted BDS of that version, instead of the cache") Path server;
        @Option(names = "--cache") Path cache;
        @Option(names = "--port", defaultValue = "19294") int port;
        @Option(names = "--timeout", defaultValue = "1800") int timeout;
        @Option(names = "--log", description = "Where the console of the run goes (default: <cache>/runs/behaviour-dump-<VERSION>.log)") Path log;
        @Option(names = "--from-log", description = "Read this console log, do not start BDS") Path fromLog;

        @Override
        public Integer call() throws Exception {
            Path console = fromLog;
            if (console == null) {
                if (bds == null) {
                    throw new IllegalArgumentException("Missing --bds (or --from-log)");
                }
                console = log != null ? log : dev.opencollab.paletter.tools.bds.BdsDistribution.defaultCache().resolve("runs").resolve("behaviour-dump-" + bds + ".log");
                dev.opencollab.paletter.tools.behaviour.BehaviourDump.run(root(), bds, preview,
                    dev.opencollab.paletter.tools.bds.ScriptModules.betaVersion(dev.opencollab.paletter.tools.bds.ScriptModules.versions(root().resolve("data/bds"), bds)),
                    server, cache, port, timeout, console);
            }
            dev.opencollab.paletter.tools.behaviour.BehaviourDump.write(root(), console);
            return 0;
        }
    }

    @Command(name = "script-dump", description = "Runs the Script API dump add-on (data/addons/dump) on BDS (--bds VERSION, extracted into the cache) "
        + "and writes data/bds/<BDS version>/dump.json.gz and the variant names of the bundled version (what update does as its first step). "
        + "Downloading BDS means accepting the Minecraft EULA.")
    static final class ScriptDumpCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--bds", required = true, description = "BDS version, e.g. 1.26.63.1") String bds;
        @Option(names = "--preview", description = "--bds is a preview build") boolean preview;
        @Option(names = "--cache") Path cache;
        @Option(names = "--port", defaultValue = "19297") int port;
        @Option(names = "--timeout", defaultValue = "600") int timeout;

        @Override
        public Integer call() throws Exception {
            Path cacheDirectory = cache != null ? cache : BdsDistribution.defaultCache();
            Path log = cacheDirectory.resolve("runs").resolve("script-dump-" + bds + ".log");
            BdsDistribution.resolve(preview, bds).onServer(cacheDirectory,
                server -> ScriptApiDump.run(root(), server, log, timeout, port));
            System.out.println("Wrote " + ScriptApiDump.write(root(), log, bds, BuildNumber.parse(bds).dataVersion()));
            return 0;
        }
    }

    @Command(name = "components", description = "The custom item/block component API and its encoders: generate, or check that the committed ones are up to date",
        subcommands = {Components.Generate.class, Components.Check.class})
    static final class Components implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "generate")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Option(names = "--sources", description = "Write here instead of paletter/src/generated/java") Path sources;

            @Override
            public Integer call() throws Exception {
                dev.opencollab.paletter.tools.codegen.component.Main.main(args("generate", root(), sources));
                return 0;
            }
        }

        @Command(name = "check")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                dev.opencollab.paletter.tools.codegen.component.Main.main(args("check", root(), null));
                return 0;
            }
        }

        static String[] args(String mode, Path root, Path sources) {
            return sources == null ? new String[]{mode, "--root", root.toString()}
                : new String[]{mode, "--root", root.toString(), "--sources", sources.toString()};
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The bundled Paletter version {@code name} from the library jar. */
    public static VanillaData reference(String name) throws IOException {
        return dev.opencollab.paletter.data.PaletteDataSource.bundled().load(new dev.opencollab.paletter.data.DataVersion(name, 0));
    }

    static VanillaData readPalette(Path file) throws IOException {
        return BundledFiles.read(file);
    }

    private static void put(Map<String, String> options, String name, String value) {
        if (value != null) {
            options.put(name, value);
        }
    }

    private static void flag(Map<String, String> options, String name, boolean value) {
        if (value) {
            options.put(name, "true");
        }
    }
}
