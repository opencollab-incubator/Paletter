package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
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
    public static final String CAPTURES = "paletter/src/main/resources/paletter/behaviour.nbt";
    static final String RESOURCES = "paletter/src/main/resources/paletter";

    private BehaviourGenerator() {
    }

    /** @return the generated files and the SOURCE ONLY changes, from the committed store and palettes */
    public static BehaviourCode.Result generate(Path root) throws IOException {
        BehaviourStore store;
        try (InputStream in = Files.newInputStream(root.resolve(CAPTURES))) {
            store = BehaviourStore.read(in);
        }
        return generate(root, store);
    }

    /** @return the generated files of a store: its versions whose palettes are bundled */
    static BehaviourCode.Result generate(Path root, BehaviourStore store) throws IOException {
        List<BehaviourImport.Bundled> bundled = BehaviourImport.bundled(root.resolve(RESOURCES)).stream()
            .filter(palette -> store.versions().contains(palette.version().name())).toList();
        return BehaviourCode.generate(new BehaviourModel(store, bundled), CAPTURES);
    }

    /** @return the exit code: 1 if {@code check} found a generated file out of date */
    public static int run(Path root, boolean check) throws IOException {
        return write(root, generate(root), check);
    }

    static int write(Path root, BehaviourCode.Result result, boolean check) throws IOException {
        List<String> changes = GeneratedSources.sync(root, result.files(), List.of(BehaviourCode.DIRECTORY), BehaviourCode.GENERATOR, check);
        int code = GeneratedSources.report(changes, check, ":tools:generateBehaviour");
        System.out.println(check ? result.files().size() - changes.size() + " of " + result.files().size() + " generated behaviour files are up to date"
            : "Wrote " + changes.size() + " of " + result.files().size() + " files; " + result.sourceOnly().size() + " SOURCE ONLY changes");
        return code;
    }
}
