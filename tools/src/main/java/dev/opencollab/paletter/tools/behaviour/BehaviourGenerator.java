package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.tools.codegen.GeneratedSources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code behaviour generate} ({@code :tools:generateBehaviour}) and {@code behaviour check} ({@code :tools:checkBehaviour},
 * part of {@code ./gradlew check}): the generated behaviour data from the capture store {@link #CAPTURES} and the bundled
 * palettes. {@code behaviour import} writes the store and then generates. See {@link BehaviourCode}.
 */
public final class BehaviourGenerator {
    /** The capture store, relative to the repository root: what the importer writes and the generator reads. */
    public static final String CAPTURES = "data/behaviour/captures.nbt";
    static final String RESOURCES = "paletter/src/main/resources/paletter";

    private BehaviourGenerator() {
    }

    /** @return the generated files and the SOURCE ONLY changes, from the committed store and palettes */
    public static BehaviourCode.Result generate(Path root) throws IOException {
        return generate(root, read(root));
    }

    /** @return the committed capture store */
    public static BehaviourStore read(Path root) throws IOException {
        try (InputStream in = Files.newInputStream(root.resolve(CAPTURES))) {
            return BehaviourStore.read(in);
        }
    }

    /** Writes the capture store. */
    public static void write(Path root, BehaviourStore store) throws IOException {
        Files.createDirectories(root.resolve(CAPTURES).getParent());
        try (var out = Files.newOutputStream(root.resolve(CAPTURES))) {
            store.write(out);
        }
    }

    /** @return the generated files of a store: its versions that are not retired, whose palettes are bundled */
    static BehaviourCode.Result generate(Path root, BehaviourStore store) throws IOException {
        List<BehaviourImport.Bundled> all = BehaviourImport.bundled(root.resolve(RESOURCES));
        List<BehaviourImport.Bundled> bundled = all.stream().filter(palette -> store.generated().contains(palette.version().name())).toList();
        List<String> missing = all.stream().map(b -> b.version().name())
            .filter(name -> !store.versions().contains(name)).toList();
        if (bundled.size() != store.generated().size() || !missing.isEmpty()) {
            // A bundled release the store does not know would silently get the values of the version before
            throw new IllegalStateException("The capture store has versions " + store.generated() + " (retired: " + store.retired()
                + "), the bundled releases " + all.stream().map(b -> b.version().name()).toList()
                + ": run behaviour import for a new release, retire one that is no longer bundled");
        }
        return BehaviourCode.generate(new BehaviourModel(store, bundled), CAPTURES);
    }

    /** @return the exit code: 1 if {@code check} found a generated file out of date */
    public static int run(Path root, boolean check) throws IOException {
        return write(root, generate(root), check);
    }

    static int write(Path root, BehaviourCode.Result result, boolean check) throws IOException {
        List<String> changes = new java.util.ArrayList<>(GeneratedSources.sync(root, result.files(), List.of(BehaviourCode.DIRECTORY),
            BehaviourCode.GENERATOR, check));
        if (ChangesSection.sync(root, result.reportSection(), check)) {
            changes.add(ChangesSection.REPORT + ": " + ChangesSection.HEADING.substring(3) + " outdated");
        }
        int code = GeneratedSources.report(changes, check, ":tools:generateBehaviour");
        System.out.println(check ? result.files().size() - changes.size() + " of " + result.files().size() + " generated behaviour files are up to date"
            : "Wrote " + changes.size() + " of " + result.files().size() + " files; " + result.flags().size() + " flagged changes, "
            + result.recaptures().size() + " values that need a recapture");
        return code;
    }
}
