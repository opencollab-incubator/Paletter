package dev.opencollab.paletter.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The BDS builds committed under {@code data/bds}, read from the directory and from {@code versions.txt}, never named in a test: a
 * data-only pull request that adds a build, drops one or replaces the preview must not need a test edit.
 */
public final class CommittedBuilds {
    /** The repository root. */
    public static final Path ROOT = Path.of(System.getProperty("paletter.root", "..")).toAbsolutePath().normalize();
    public static final Path BDS = ROOT.resolve("data").resolve("bds");
    private static final Path VERSIONS = ROOT.resolve("paletter/src/main/resources/paletter/versions.txt");

    /**
     * The builds whose captures were made before the current add-on, so they lack what the add-on gained since (script modules, gating,
     * block items, engine pack blocks, the hash collision pair). The recapture removes a build from this list when it captures it again.
     * A build that is not committed any more is ignored here; a build that is committed and not listed must have everything.
     */
    private static final Set<String> BEFORE_CURRENT_ADDON = Set.of();

    private CommittedBuilds() {
    }

    /** Every committed build, oldest first. */
    public static List<String> all() {
        try (var builds = Files.list(BDS)) {
            return builds.filter(Files::isDirectory).map(b -> b.getFileName().toString()).filter(BuildNumber::isBuild).sorted(Versions.ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The committed builds of a data version that is not a preview, oldest first. */
    public static List<String> releases() {
        return all().stream().filter(b -> !isPreview(b)).toList();
    }

    /** The committed builds of the preview data version (none while no preview is bundled). */
    public static List<String> preview() {
        return all().stream().filter(CommittedBuilds::isPreview).toList();
    }

    public static Path directory(String build) {
        return BDS.resolve(build);
    }

    private static boolean isPreview(String build) {
        String name = BuildNumber.parse(build).dataVersion();
        try {
            for (String line : Files.readAllLines(VERSIONS)) {
                String[] parts = line.trim().split("\\s+");
                if (!line.startsWith("#") && parts.length > 2 && parts[0].equals(name) && List.of(parts).subList(2, parts.length).contains("preview")) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }

    /**
     * The ratchet of a file the add-on gained: a build listed as captured before the current add-on must lack it, any other build must have
     * it. A build that has it while listed, and one that lacks it while not listed, fail with {@code what}.
     */
    public static void assertPending(String what, Predicate<String> has) {
        List<String> problems = new ArrayList<>();
        for (String build : all()) {
            boolean pending = BEFORE_CURRENT_ADDON.contains(build);
            if (has.test(build) && pending) {
                problems.add(build + " has " + what + ": remove it from CommittedBuilds.BEFORE_CURRENT_ADDON");
            } else if (!has.test(build) && !pending) {
                problems.add(build + " has no " + what + ": capture it again with the current add-on");
            }
        }
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }
}
