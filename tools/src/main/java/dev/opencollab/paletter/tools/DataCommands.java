package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.codegen.constants.Baseline;
import dev.opencollab.paletter.tools.data.Checker;
import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.data.Export;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.update.ExperimentMarks;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static dev.opencollab.paletter.tools.Cli.flag;
import static dev.opencollab.paletter.tools.Cli.put;
import static dev.opencollab.paletter.tools.Cli.readPalette;

/**
 * The commands on the committed data, none of which starts BDS: {@code import}, {@code data-diff}, {@code verify},
 * {@code baseline}, {@code export}, {@code check-data}, {@code experiment-marks}, {@code recipes-diff},
 * {@code recipes-import}, {@code voxel-shapes}.
 */
final class DataCommands {
    private DataCommands() {
    }

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

    @Command(name = "baseline", description = "Writes the block and item identifiers of a CloudburstMC/Data checkout (at the commit of the release "
        + "before the oldest bundled one) to data/model/baseline-<name>.txt. The constants generator gives no First in Bedrock line to content in it.")
    static final class BaselineCommand extends Rooted implements Callable<Integer> {
        @Option(names = "--source", required = true, description = "The CloudburstMC/Data checkout") Path source;
        @Option(names = "--name", required = true, description = "The release of the checkout, e.g. 26.20") String name;
        @Option(names = "--commit", required = true, description = "The CloudburstMC/Data commit of the checkout") String commit;

        @Override
        public Integer call() throws Exception {
            Path out = root().resolve("data/model/baseline-" + name + ".txt");
            Baseline.write(source, name, commit, out);
            System.out.println("Wrote " + out + "; " + Baseline.FILE + " is the file the generator reads");
            return 0;
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

    @Command(name = "experiment-marks", description = "Writes the experiment marks (content only there behind a toggle) of the bundled versions "
        + "from the experiment records data/bds/*/experiments.json.")
    static final class ExperimentMarksCommand extends Rooted implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            System.out.println("Changed: " + ExperimentMarks.apply(root()));
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
}
