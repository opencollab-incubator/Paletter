package dev.opencollab.paletter.tools;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.BdsInventory;
import dev.opencollab.paletter.tools.bds.BdsRun;
import dev.opencollab.paletter.tools.bds.ScriptApiDump;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.SchemaExtract;
import dev.opencollab.paletter.tools.whitebox.Whitebox;
import dev.opencollab.paletter.tools.whitebox.WhiteboxCoverage;
import dev.opencollab.paletter.tools.whitebox.WhiteboxDiff;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The commands that read BDS: its inventory, schema, code and dumps ({@code bds-inventory}, {@code bds-schema},
 * {@code whitebox}, {@code whitebox-coverage}, {@code bds-compare}, {@code script-dump}) and the behaviour data
 * ({@code behaviour}, {@code behaviour-probe}, {@code behaviour-dump}).
 */
final class BdsCommands {
    private BdsCommands() {
    }

    @Command(name = "bds-inventory", description = "Reads the strings of the Linux bedrock_server of a BDS build (downloaded into the cache, not run) "
        + "and writes data/bds/<VERSION>/inventory.json: block components and their JSON keys, item components, every Script API class and "
        + "member; prints what changed against the inventory of the previous build. Downloading BDS means accepting the Minecraft EULA.")
    static final class BdsInventoryCommand extends Rooted implements Callable<Integer> {
        @Mixin BuildOptions build;
        @Option(names = "--cache", description = BdsOptions.CACHE) Path cache;

        @Override
        public Integer call() throws Exception {
            String bds = build.requireVersion();
            Path file = BdsInventory.write(root(), bds, build.preview(), cache);
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
        @Option(names = "--version", required = true, description = "The BDS build number, e.g. 1.26.63.1") String version;
        @Option(names = "--docs", required = true, description = "The docs directory BDS generated") Path docs;

        @Override
        public Integer call() throws Exception {
            String bds = BuildNumber.require(version);
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
        enum Platform {
            LINUX, WINDOWS
        }

        @Mixin BuildOptions build;
        @Option(names = "--platform", defaultValue = "LINUX", description = "${COMPLETION-CANDIDATES}: linux (default: the build that names its classes "
            + "and shows the tag types) or windows (a cross-check, written to whitebox.windows.json)") Platform platform;
        @Option(names = "--binary", description = "The server binary or a BDS zip, instead of downloading") Path binary;
        @Option(names = "--cache", description = BdsOptions.CACHE) Path cache;

        @Override
        public Integer call() throws Exception {
            String bds = build.requireVersion();
            boolean linux = platform == Platform.LINUX;
            Path source = binary != null ? binary
                : BdsDistribution.resolve(linux, build.preview(), bds).zip(BdsOptions.cache(cache), root());
            JsonObject facts = Whitebox.discover(source, bds);
            String name = Whitebox.file(facts.get("platform").getAsString());
            Path file = root().resolve(Layout.BDS).resolve(bds).resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Whitebox.json(facts));
            System.out.println("Wrote " + file);
            facts.getAsJsonArray("notes").forEach(note -> Console.inform(note.getAsString()));
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
        @Option(names = "--version", description = "The BDS build number (default: every build with a whitebox.json)") String version;
        @Option(names = "--check", description = "Write nothing; exit with 1 if a committed report differs from what the committed data gives") boolean check;
        @Option(names = "--strict", description = "Exit with 1 if any gap is left") boolean strict;

        @Override
        public Integer call() throws Exception {
            List<String> builds = new ArrayList<>();
            if (version != null) {
                builds.add(BuildNumber.require(version));
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
                        Stale.print(root().relativize(report).toString().replace('\\', '/'), "whitebox-coverage and review what changed");
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
        @Option(names = "--bds-dump", required = true, description = "The BDS dump to check (a dump.json.gz of data/bds)") Path bdsDump;
        @Option(names = "--data-version", required = true, description = "The bundled data version to check it against, e.g. 26.60") String dataVersion;
        @Option(names = "--item-tags", description = "An item tags file to check the bundled tags against too, e.g. Geyser's item_tags.json") Path itemTags;

        @Override
        public Integer call() throws Exception {
            Map<String, Map<String, List<String>>> tagSources = new LinkedHashMap<>();
            VanillaData data = BundledFiles.read(resources().resolve(dataVersion + ".nbt"));
            tagSources.put("the bundled " + data.version().name() + " tags", data.itemTags());
            if (itemTags != null) {
                tagSources.put(itemTags.toString(), Importer.readItemTags(itemTags));
            }
            Diff.bdsDump(BdsDump.read(bdsDump), data, tagSources).print(System.out);
            return 0;
        }
    }

    @Command(name = "script-dump", description = "Runs the Script API dump add-on (data/addons/dump) on BDS (--version BUILD, extracted into the cache) "
        + "and writes data/bds/<BDS version>/dump.json.gz and the variant names of the bundled version (what update does as its first step). "
        + "Downloading BDS means accepting the Minecraft EULA.")
    static final class ScriptDumpCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;

        @Override
        public Integer call() throws Exception {
            String build = bds.requireVersion();
            BdsRun run = bds.run(19297, 600, false);
            Path log = run.cache().resolve("runs").resolve("script-dump-" + build + ".log");
            run.distribution().onServer(run.cache(), root(), server -> ScriptApiDump.run(root(), server, log, run.timeout(), run.port()));
            System.out.println("Wrote " + ScriptApiDump.write(root(), log, build, BuildNumber.parse(build).dataVersion()));
            return 0;
        }
    }

    @Command(name = "behaviour", description = "Behaviour values of vanilla blocks and items (friction, shapes, max stack size, ...)",
        subcommands = {BehaviourCommand.Import.class, BehaviourCommand.Generate.class, BehaviourCommand.Check.class})
    static final class BehaviourCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "import", description = "Reads the git history of the dump sources and the Script API dumps of data/bds, "
            + "writes data/behaviour/captures.nbt, the generated behaviour data and data/behaviour/REPORT.md")
        static final class Import extends Rooted implements Callable<Integer> {
            @Option(names = "--cloudburst-data", description = "Checkout of https://github.com/CloudburstMC/Data") Path cloudburstData;
            @Option(names = "--allay", description = "Checkout of https://github.com/AllayMC/Allay (git clone --filter=blob:none is enough)") Path allay;

            @Override
            public Integer call() throws Exception {
                dev.opencollab.paletter.tools.behaviour.BehaviourImport.run(root(), cloudburstData, allay);
                return 0;
            }
        }

        @Command(name = "generate", description = "Writes the generated behaviour data (paletter internal.behaviour.vanilla) from the "
            + "committed capture store and the bundled palettes")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                return dev.opencollab.paletter.tools.behaviour.BehaviourGenerator.run(root(), false);
            }
        }

        @Command(name = "check", description = "Fails if regenerating the behaviour data from the committed capture store and palettes yields a diff")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                return dev.opencollab.paletter.tools.behaviour.BehaviourGenerator.run(root(), true);
            }
        }
    }

    @Command(name = "behaviour-probe", description = "Runs data/addons/probe on BDS (--version BUILD, extracted into the cache, or --server DIR) and "
        + "writes what it measured to data/bds/<BDS version>/behaviour-probe/ (probe.log, capture.json; input of behaviour import). "
        + "With --from-log it measures a console log of an earlier run instead of starting BDS. Downloading BDS means accepting the Minecraft EULA.")
    static final class BehaviourProbeCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--server", description = "An extracted BDS of that version, instead of the cache") Path server;
        @Option(names = "--log", description = "Where the console of the run goes (default: <cache>/runs/behaviour-probe-<VERSION>.log)") Path log;
        @Option(names = "--from-log", description = "Measure this console log, do not start BDS") Path fromLog;

        @Override
        public Integer call() throws Exception {
            Path console = fromLog;
            if (console == null) {
                String build = bds.requireVersion();
                BdsRun run = bds.run(19290, 900, false);
                console = log != null ? log : run.cache().resolve("runs").resolve("behaviour-probe-" + build + ".log");
                dev.opencollab.paletter.tools.behaviour.BehaviourProbe.run(root(), build, run.preview(), server, run.cache(), run.port(), run.timeout(), console);
            }
            dev.opencollab.paletter.tools.behaviour.BehaviourProbe.write(root(), console);
            return 0;
        }
    }

    @Command(name = "behaviour-dump", description = "Runs data/addons/behaviour-dump on BDS (--version BUILD, extracted into the cache, or --server DIR) "
        + "with the Beta APIs and writes what the Script API says about every block state and item to data/bds/<BDS version>/behaviour-dump.json.gz "
        + "(input of behaviour import and of the behaviour key check). With --from-log it reads a console log of an earlier run instead of "
        + "starting BDS. Downloading BDS means accepting the Minecraft EULA.")
    static final class BehaviourDumpCommand extends Rooted implements Callable<Integer> {
        @Mixin BdsOptions bds;
        @Option(names = "--server", description = "An extracted BDS of that version, instead of the cache") Path server;
        @Option(names = "--log", description = "Where the console of the run goes (default: <cache>/runs/behaviour-dump-<VERSION>.log)") Path log;
        @Option(names = "--from-log", description = "Read this console log, do not start BDS") Path fromLog;

        @Override
        public Integer call() throws Exception {
            Path console = fromLog;
            if (console == null) {
                String build = bds.requireVersion();
                BdsRun run = bds.run(19294, 1800, false);
                console = log != null ? log : run.cache().resolve("runs").resolve("behaviour-dump-" + build + ".log");
                dev.opencollab.paletter.tools.behaviour.BehaviourDump.run(root(), build, run.preview(),
                    dev.opencollab.paletter.tools.bds.ScriptModules.betaVersion(dev.opencollab.paletter.tools.bds.ScriptModules.versions(root().resolve(Layout.BDS), build)),
                    server, run.cache(), run.port(), run.timeout(), console);
            }
            dev.opencollab.paletter.tools.behaviour.BehaviourDump.write(root(), console);
            return 0;
        }
    }
}
