package dev.opencollab.paletter.tools;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/** Repository root of a command: the working directory unless {@code --root} says otherwise. */
abstract class Rooted {
    @Option(names = "--root", description = "Repository root (default: the working directory)")
    Path rootOption;

    Path root() {
        return (rootOption == null ? Path.of("") : rootOption).toAbsolutePath();
    }

    Path resources() {
        return root().resolve("paletter/src/main/resources/paletter");
    }
}
