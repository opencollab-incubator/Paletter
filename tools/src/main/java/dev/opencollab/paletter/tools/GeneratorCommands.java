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
}
