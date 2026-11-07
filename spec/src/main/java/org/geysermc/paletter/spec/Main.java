package org.geysermc.paletter.spec;

import org.geysermc.paletter.spec.addon.AddonGenerator;
import org.geysermc.paletter.spec.docs.DocsFetcher;
import org.geysermc.paletter.spec.docs.DocsParser;
import org.geysermc.paletter.spec.infer.MappingInference;
import org.geysermc.paletter.spec.model.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The component model tooling. See spec/README.md.
 * <pre>
 * docs  [--docs DIR [--commit SHA]] [--commit SHA]   creator docs -> codegen/model/components.json
 *       --docs: a checkout of MicrosoftDocs/minecraft-creator (commit from git); otherwise the pages of --commit (default:
 *       the commit components.json was made from) are downloaded into the cache
 * addon --out DIR                                     codegen/model -> capture add-on (behavior + resource pack) + variants.json
 * infer                                               model + codegen/model/captures -> mappings.json, coverage.md
 * check                                               infer, but fail if the committed outputs differ
 * </pre>
 */
public final class Main {
    /** The docs commit the committed model was generated from (MicrosoftDocs/minecraft-creator main, 2026-11-06). */
    public static final String DEFAULT_COMMIT = "457cdeaa5aa428429884b092895f3979b1f69ad3";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: docs [--docs DIR] [--commit SHA] | addon --out DIR | infer | check   (all take --root DIR)");
            return;
        }
        Map<String, String> options = options(args);
        Path root = Path.of(options.getOrDefault("root", "")).toAbsolutePath();
        Path model = root.resolve("codegen/model");
        switch (args[0]) {
            case "docs" -> {
                DocsParser.DocsSource source;
                if (options.containsKey("docs")) {
                    Path docs = Path.of(options.get("docs"));
                    source = DocsParser.DocsSource.directory(docs, options.getOrDefault("commit", gitHead(docs)));
                } else {
                    String commit = options.get("commit");
                    if (commit == null && Files.exists(model.resolve("components.json"))) {
                        commit = Model.read(model.resolve("components.json")).commit();
                    }
                    source = new DocsFetcher(commit != null ? commit : DEFAULT_COMMIT, DocsFetcher.defaultCache());
                }
                Model parsed = new DocsParser(source).parse();
                parsed.write(model.resolve("components.json"));
                System.out.println(parsed.components().size() + " components, "
                    + parsed.components().stream().mapToInt(c -> c.fields().size()).sum() + " fields -> " + model.resolve("components.json"));
            }
            case "addon" -> {
                AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
                generator.write(Path.of(options.get("out")));
            }
            case "infer" -> new MappingInference(root).run(false);
            case "check" -> System.exit(new MappingInference(root).run(true) ? 0 : 1);
            default -> throw new IllegalArgumentException("Unknown command " + args[0]);
        }
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

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option [value], got " + args[i]);
            }
            String name = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(name, args[++i]);
            } else {
                options.put(name, "true");
            }
        }
        return options;
    }
}
