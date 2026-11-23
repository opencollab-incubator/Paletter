package dev.opencollab.paletter.tools.codegen.constants;

import dev.opencollab.paletter.tools.codegen.GeneratedSources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code :tools:generateConstants} ({@code generate}) and {@code :tools:checkConstants} ({@code check}, part of
 * {@code ./gradlew check}: fails if the committed constants, enums or history differ from what the committed data,
 * names and history give).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        GeneratedSources.Arguments arguments = GeneratedSources.Arguments.parse(args, false);
        System.exit(run(arguments.root(), arguments.check()));
    }

    /** @return the process exit code: 1 if {@code check} found a file out of date */
    public static int run(Path root, boolean check) throws IOException {
        return run(root, ConstantsGenerator.generate(root), check);
    }

    static int run(Path root, ConstantsGenerator.Result result, boolean check) throws IOException {
        if (!check) {
            System.out.println(summary(result));
        }
        List<String> changes = GeneratedSources.sync(root, result.files(), List.of(ConstantsGenerator.STATE_DIR), ConstantsSource.GENERATOR, check);
        int code = GeneratedSources.report(changes, check, ":tools:generateConstants");
        System.out.println(check ? result.files().size() - changes.size() + " of " + result.files().size() + " generated constants files are up to date"
            : "Wrote " + changes.size() + " of " + result.files().size() + " files");
        return code;
    }

    /** The generated enums that the result no longer has: with the generator's header, so never a hand-written file. */
    public static List<Path> stale(Path root, Map<String, String> files) throws IOException {
        return GeneratedSources.stale(root, files, List.of(ConstantsGenerator.STATE_DIR), ConstantsSource.GENERATOR);
    }

    static String summary(ConstantsGenerator.Result result) {
        StringBuilder out = new StringBuilder("Constants of " + result.release() + (result.preview() == null ? "" : " (preview " + result.preview() + ")")
            + ": " + result.counts());
        out.append("\nBlocks with a lookup table instead of strides: ").append(result.tableBlocks().isEmpty() ? "none" : "");
        result.tableBlocks().forEach(block -> out.append("\n  ").append(block));
        if (!result.added().isEmpty()) {
            out.append("\nAdded: ").append(result.added().size());
            result.added().stream().limit(50).forEach(added -> out.append("\n  ").append(added));
            if (result.added().size() > 50) {
                out.append("\n  ...");
            }
        }
        if (!result.deprecated().isEmpty()) {
            out.append("\nDeprecated: ").append(result.deprecated().size());
            result.deprecated().forEach(deprecated -> out.append("\n  ").append(deprecated));
        }
        return out.toString();
    }
}
