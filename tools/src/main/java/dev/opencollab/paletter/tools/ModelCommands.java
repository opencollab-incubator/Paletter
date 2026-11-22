package dev.opencollab.paletter.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.data.WireShapes;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.addon.FixedAddons;
import dev.opencollab.paletter.tools.model.docs.DocsFetcher;
import dev.opencollab.paletter.tools.model.docs.DocsParser;
import dev.opencollab.paletter.tools.model.infer.MappingInference;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static dev.opencollab.paletter.tools.Cli.DEFAULT_COMMIT;

/** The commands of the component model: {@code docs}, {@code addon}, {@code infer}, {@code check-mappings}. */
final class ModelCommands {
    private ModelCommands() {
    }

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
}
