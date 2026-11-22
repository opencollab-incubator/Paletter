package dev.opencollab.paletter.tools;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.BdsInventory;
import dev.opencollab.paletter.tools.bds.ScriptApiDump;
import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.SchemaExtract;
import dev.opencollab.paletter.tools.whitebox.Whitebox;
import dev.opencollab.paletter.tools.whitebox.WhiteboxCoverage;
import dev.opencollab.paletter.tools.whitebox.WhiteboxDiff;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static dev.opencollab.paletter.tools.Cli.readPalette;

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
                : BdsDistribution.resolve(linux, preview, bds).zip(cache == null ? BdsDistribution.defaultCache() : cache, root());
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
            BdsDistribution.resolve(preview, bds).onServer(cacheDirectory, root(),
                server -> ScriptApiDump.run(root(), server, log, timeout, port));
            System.out.println("Wrote " + ScriptApiDump.write(root(), log, bds, BuildNumber.parse(bds).dataVersion()));
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
}
