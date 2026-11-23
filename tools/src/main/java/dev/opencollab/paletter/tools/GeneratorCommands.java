package dev.opencollab.paletter.tools;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** The commands of the code generators and of the inspection of palettes: {@code constants}, {@code components}, {@code inspect}. */
final class GeneratorCommands {
    private GeneratorCommands() {
    }

    @Command(name = "constants", description = "Vanilla constants classes: generate, or check that the committed ones are up to date",
        subcommands = {Constants.Generate.class, Constants.Check.class})
    static final class Constants implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "generate", description = "Writes the constants classes from the bundled data, the names and the history")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                return dev.opencollab.paletter.tools.codegen.constants.Main.run(root(), false);
            }
        }

        @Command(name = "check", description = "Exits with 1 if the committed constants differ from what generate writes")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                return dev.opencollab.paletter.tools.codegen.constants.Main.run(root(), true);
            }
        }
    }

    @Command(name = "blocks", description = "Vanilla block definitions as generated code: generate (and drop the block states from the "
        + "version files), or check that the committed ones are up to date", subcommands = {Blocks.Generate.class, Blocks.Check.class})
    static final class Blocks implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "generate", description = "Writes the block definitions of the bundled palettes and the version files without block states")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                return dev.opencollab.paletter.tools.blocks.BlockGenerator.run(root(), false);
            }
        }

        @Command(name = "check", description = "Exits with 1 if the committed block definitions differ from what generate writes, or a version "
            + "file still has its block states")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws IOException {
                return dev.opencollab.paletter.tools.blocks.BlockGenerator.run(root(), true);
            }
        }
    }

    @Command(name = "inspect", description = "Explain what the bundled palettes do with a block state or an item variant")
    static final class InspectCommand implements Callable<Integer> {
        @Option(names = "--data-version", required = true, description = "The bundled data version, e.g. 26.60, or all") String version;
        @Option(names = "--block", description = "Block: identifier or identifier[key=value,...], e.g. 'minecraft:oak_stairs[upside_down_bit=true]'") String block;
        @Option(names = "--item", description = "Item: identifier or identifier:damage, e.g. minecraft:potion:5") String item;

        @Override
        public Integer call() {
            dev.opencollab.paletter.tools.data.Inspect.run(dev.opencollab.paletter.Paletter.builder().build(), version, block, item, System.out);
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

        @Command(name = "generate", description = "Writes the component API from data/model")
        static final class Generate extends Rooted implements Callable<Integer> {
            @Option(names = "--sources", description = "Write here instead of paletter/src/generated/java") Path sources;

            @Override
            public Integer call() throws Exception {
                return dev.opencollab.paletter.tools.codegen.component.Main.report(root(),
                    sources != null ? sources : root().resolve(dev.opencollab.paletter.tools.codegen.component.Main.SOURCES), false);
            }
        }

        @Command(name = "check", description = "Exits with 1 if the committed component API differs from what generate writes")
        static final class Check extends Rooted implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                return dev.opencollab.paletter.tools.codegen.component.Main.report(root(),
                    root().resolve(dev.opencollab.paletter.tools.codegen.component.Main.SOURCES), true);
            }
        }
    }
}
