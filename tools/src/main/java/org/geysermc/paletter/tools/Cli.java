package org.geysermc.paletter.tools;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.bds.BdsRunner;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.tools.client.CaptureClient;
import org.geysermc.paletter.tools.client.CaptureWriter;
import org.geysermc.paletter.tools.client.CapturedData;
import org.geysermc.paletter.tools.client.LevelDat;
import org.geysermc.paletter.tools.data.BundledFiles;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.Checker;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.FixedAddons;
import org.geysermc.paletter.tools.model.docs.DocsFetcher;
import org.geysermc.paletter.tools.model.docs.DocsParser;
import org.geysermc.paletter.tools.model.infer.MappingInference;
import org.geysermc.paletter.tools.update.CreativeProbe;
import org.geysermc.paletter.tools.update.EducationProbe;
import org.geysermc.paletter.tools.update.SpecCapture;
import org.geysermc.paletter.tools.update.Update;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The one command line of the tooling: {@code ./gradlew :tools:run --args="<command> ..."}. Paths are relative to the
 * working directory, which is the repository root for Gradle; {@code --root} overrides it where a command reads the
 * repository. See tools/README.md for what each command does.
 */
@Command(name = "paletter-tools", mixinStandardHelpOptions = true, subcommands = {
    Cli.Import.class, Cli.BdsRun.class, Cli.BdsCompareCommand.class, Cli.UpdateCommand.class, Cli.SpecCaptureCommand.class,
    Cli.VerifyCommand.class, Cli.CheckData.class, Cli.BlockItemsCommand.class, Cli.RewriteCommand.class, Cli.DataRewriteCommand.class, Cli.DataDiffCommand.class, Cli.CreativeProbeCommand.class, Cli.EducationCaptureCommand.class,
    Cli.CaptureCommand.class, Cli.Experiments.class, Cli.CompareCommand.class,
    Cli.Docs.class, Cli.Addon.class, Cli.Infer.class, Cli.CheckMappings.class,
    Cli.Constants.class, Cli.Components.class, Cli.InspectCommand.class
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

    @Command(name = "block-items", description = "Adds the block to item relation of a Script API dump to bundled data (rewrites resources/NAME.nbt, "
        + "everything else is kept). Without --bds-dump the relation of the bundled version --from is applied to NAME: a block that exists in both "
        + "gets that item if it exists in NAME. For versions that no dump exists for.")
    static final class BlockItemsCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = "--bds-dump") Path bdsDump;
        @Option(names = "--from", description = "Bundled version to copy the relation from") String from;

        @Override
        public Integer call() throws Exception {
            Path file = resources().resolve(name + ".nbt");
            VanillaData data = readPalette(file);
            Map<String, String> relation;
            if (bdsDump != null) {
                relation = Importer.blockItems(BdsDump.read(bdsDump), data.blockStates(), data.items());
            } else if (from != null) {
                relation = Importer.deriveBlockItems(readPalette(resources().resolve(from + ".nbt")), data);
            } else {
                throw new IllegalArgumentException("Missing --bds-dump or --from");
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            PaletteDataCodec.write(data.withBlockItems(relation), bytes);
            Files.write(file, bytes.toByteArray());
            System.out.println("Wrote " + file + " with " + relation.size() + " block to item entries");
            return 0;
        }
    }

    @Command(name = "rewrite", description = "Reads bundled data NAME and writes it again without fallbacks (Paletter bundles none; the codec still reads them from older files).")
    static final class RewriteCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--name", required = true, split = ",") List<String> names;

        @Override
        public Integer call() throws Exception {
            for (String name : names) {
                Path file = resources().resolve(name + ".nbt");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                VanillaData data = readPalette(file);
                PaletteDataCodec.write(new VanillaData(data.version(), data.blockStates(), data.items(), data.dataDrivenBlocks(), data.creative(),
                    data.itemTags(), org.geysermc.paletter.internal.data.VersionFallbacks.NONE, data.blockDefaults(), data.experiments(), data.blockItems()), bytes);
                Files.write(file, bytes.toByteArray());
                System.out.println("Rewrote " + file);
            }
            return 0;
        }
    }

    @Command(name = "data-rewrite", description = "Rewrites every bundled version file (format 2, without item components and data driven blocks) "
        + "and rebuilds properties.nbt from all of them. Reads format 1 and 2; checks before writing that putting the data together again "
        + "gives back exactly what was read.")
    static final class DataRewriteCommand extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            BundledFiles.rewrite(resources());
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

    @Command(name = "bds-run", description = "Installs data/addons/dump on a fresh flat world of a Bedrock Dedicated Server, runs it headless until the "
        + "add-on printed PALETTER-DONE and writes the console output to --log (the input of import --bds-dump). --download fetches BDS for this OS "
        + "from minecraft.net into DIR/VERSION first; keep DIR outside the repository. Downloading BDS means accepting the Minecraft EULA.")
    static final class BdsRun extends Rooted implements Callable<Integer> {
        @Option(names = "--log", required = true) Path log;
        @Option(names = "--server", description = "An extracted BDS") Path server;
        @Option(names = "--download", description = "Download BDS into this directory") Path download;
        @Option(names = "--version") String version;
        @Option(names = "--preview", arity = "0..1", fallbackValue = "true", description = "Download the preview") boolean preview;
        @Option(names = "--timeout", defaultValue = "600") int timeout;

        @Override
        public Integer call() throws Exception {
            Path dir = download != null ? BdsRunner.download(download, preview, version) : server;
            if (dir == null) {
                throw new IllegalArgumentException("Missing --server or --download");
            }
            new BdsRunner(root().resolve(Layout.DUMP_ADDON)).run(dir, log, timeout);
            return 0;
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
        + "--verify-only writes nothing and exits with 1 on any difference.")
    static final class UpdateCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--channel", required = true, description = "release or preview") String channel;
        @Option(names = "--version") String version;
        @Option(names = "--verify-only") boolean verifyOnly;
        @Option(names = "--import-preview") boolean importPreview;
        @Option(names = "--keep-server") boolean keepServer;
        @Option(names = "--cache") String cache;
        @Option(names = "--report") String report;
        @Option(names = "--name") String name;
        @Option(names = "--experiments", description = "a,b") String experiments;
        @Option(names = "--experiment-attribution", description = "each, all or none") String attribution;
        @Option(names = "--port") String port;
        @Option(names = "--timeout", description = "seconds") String timeout;

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            options.put("channel", channel);
            put(options, "version", version);
            flag(options, "verify-only", verifyOnly);
            flag(options, "import-preview", importPreview);
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

        @Override
        public Integer call() throws Exception {
            Map<String, String> options = new HashMap<>();
            put(options, "channel", channel);
            put(options, "version", version);
            put(options, "out", out);
            flag(options, "keep-server", keepServer);
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
        @Option(names = "--report") Path report;
        @Option(names = "--max") Integer max;

        @Override
        public Integer call() throws Exception {
            Path file = bundled != null ? bundled : resources().resolve(name + ".nbt");
            Diff verify = Diff.source(source, name, readPalette(file), file.toString());
            int lines = max != null ? max : Diff.DEFAULT_MAX_LINES;
            System.out.print(verify.markdown(lines));
            if (report != null) {
                verify.write(report, lines);
            }
            return verify.identical() ? 0 : 1;
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

    // ---------------------------------------------------------------------------------------------------------------
    // The Bedrock capture client

    @Command(name = "capture", description = "Records what a Bedrock Dedicated Server sends a client. See data/bds/README.md.")
    static final class CaptureCommand implements Callable<Integer> {
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--custom-out") Path customOut;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "19140") int port;
        @Option(names = "--reference", defaultValue = "26.60") String reference;
        @Option(names = "--bds-version", defaultValue = "unknown") String bdsVersion;
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
                    out, customOut, timeout, true);
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
            CaptureWriter writer = new CaptureWriter(result, reference(reference), bdsVersion);
            writer.write(out, customOut);
            System.out.println("Wrote " + out + (writer.unresolvedHashes() > 0
                ? "; WARNING: " + writer.unresolvedHashes() + " creative block ids do not match any palette hash" : "; all creative block ids match palette hashes"));
            return 0;
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
        + "the default), capture (data/addons/capture), creative (data/addons/creative), creative-large (the 700 block probe)")
    static final class Addon extends Rooted implements Callable<Integer> {
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--kind", defaultValue = "spec", description = "spec, capture, creative or creative-large") String kind;

        @Override
        public Integer call() throws Exception {
            switch (kind) {
                case "spec" -> {
                    Path model = root().resolve(Layout.MODEL);
                    new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model)).write(out);
                }
                case "capture" -> FixedAddons.writeCapture(out);
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
            new MappingInference(root()).run(false);
            return 0;
        }
    }

    @Command(name = "check-mappings", description = "infer, but fail if the committed outputs differ")
    static final class CheckMappings extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            return new MappingInference(root()).run(true) ? 0 : 1;
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
                org.geysermc.paletter.tools.codegen.constants.Main.main(new String[]{"generate", "--root", root().toString()});
                return 0;
            }
        }

        @Command(name = "check")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                org.geysermc.paletter.tools.codegen.constants.Main.main(new String[]{"check", "--root", root().toString()});
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
            org.geysermc.paletter.tools.data.Inspect.run(org.geysermc.paletter.Paletter.builder().blockNetworkIds(org.geysermc.paletter.block.BlockNetworkIds.HASHED).build(), version, block, item, System.out);
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
                org.geysermc.paletter.tools.codegen.component.Main.main(args("generate", root(), sources));
                return 0;
            }
        }

        @Command(name = "check")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                org.geysermc.paletter.tools.codegen.component.Main.main(args("check", root(), null));
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
        return org.geysermc.paletter.data.PaletteDataSource.bundled().load(new org.geysermc.paletter.data.DataVersion(name, 0));
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
