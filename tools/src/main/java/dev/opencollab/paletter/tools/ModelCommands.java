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

/** The commands of the component model: {@code docs}, {@code addon}, {@code infer}, {@code check-mappings}, {@code retire}. */
final class ModelCommands {
    private ModelCommands() {
    }

    @Command(name = "docs", description = "Creator docs -> data/model/components.json. --docs is a checkout of MicrosoftDocs/minecraft-creator "
        + "(commit from git); otherwise the pages of --commit (default: the commit components.json was made from) are downloaded into the cache.")
    static final class Docs extends Rooted implements Callable<Integer> {
        @Option(names = "--docs", description = "A checkout of MicrosoftDocs/minecraft-creator (default: download the pages of --commit)") Path docs;
        @Option(names = "--commit", description = "The commit of the docs (default: the checkout's HEAD, or the one components.json was made from)") String commit;

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
                source = new DocsFetcher(sha != null ? sha : DocsFetcher.PINNED_COMMIT, DocsFetcher.defaultCache());
            }
            Model parsed = new DocsParser(source).parse();
            parsed.write(model.resolve("components.json"));
            System.out.println(parsed.components().size() + " components, "
                + parsed.components().stream().mapToInt(c -> c.fields().size()).sum() + " fields -> " + model.resolve("components.json"));
            return 0;
        }

        /** The commit is the provenance of the model and of coverage.md: without one the run fails and asks for {@code --commit}. */
        private static String gitHead(Path docs) {
            try {
                Process git = new ProcessBuilder("git", "-C", docs.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
                String head = new String(git.getInputStream().readAllBytes()).trim();
                if (git.waitFor() == 0) {
                    return head;
                }
            } catch (Exception e) {
                // the same message below
            }
            throw new IllegalArgumentException(docs + " is no git checkout: pass --commit, the commit of its docs");
        }
    }

    @Command(name = "addon", description = "Generates an add-on. Kinds: spec (data/model -> every documented component + variants.json, "
        + "the default), creative (data/addons/creative), creative-large (the 700 block probe)")
    static final class Addon extends Rooted implements Callable<Integer> {
        enum Kind {
            SPEC, CREATIVE, CREATIVE_LARGE
        }

        /** {@code creative-large} for {@code CREATIVE_LARGE}. */
        static final class KindConverter implements picocli.CommandLine.ITypeConverter<Kind> {
            @Override
            public Kind convert(String value) {
                try {
                    return Kind.valueOf(value.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    throw new picocli.CommandLine.TypeConversionException("expected spec, creative or creative-large but was '" + value + "'");
                }
            }
        }

        @Option(names = "--out", required = true, description = "The directory the add-on is written to") Path out;
        @Option(names = "--kind", defaultValue = "spec", converter = KindConverter.class, description = "spec, creative or creative-large (default: spec)") Kind kind;

        @Override
        public Integer call() throws Exception {
            switch (kind) {
                case SPEC -> {
                    Path model = root().resolve(Layout.MODEL);
                    new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model).withNewestSchema(null)).write(out);
                }
                case CREATIVE -> FixedAddons.writeCreative(out);
                case CREATIVE_LARGE -> FixedAddons.writeCreativeLarge(out);
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

    @Command(name = "retire", description = "Drops a window protocol from data/model/mappings.json and mappings-evidence.json (its"
        + " captures and windows; neighbouring windows that become alike join) and lists what else must go")
    static final class Retire extends Rooted implements Callable<Integer> {
        @picocli.CommandLine.Parameters(index = "0", paramLabel = "PROTOCOL", description = "The window protocol of the captures to retire (mappings.json captures)")
        int protocol;

        @Override
        public Integer call() throws Exception {
            List<String> leftovers = dev.opencollab.paletter.tools.model.infer.Retire.run(root(), protocol);
            System.out.println("retired protocol " + protocol + " from data/model; delete or edit by hand:");
            leftovers.forEach(line -> System.out.println("  " + line));
            return 0;
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
