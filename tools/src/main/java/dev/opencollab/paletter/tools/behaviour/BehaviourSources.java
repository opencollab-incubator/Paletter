package dev.opencollab.paletter.tools.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The pins of the third-party behaviour dumps, {@code data/behaviour/sources.json}: per source its repository and the commit
 * the committed {@code data/behaviour/captures.nbt} was built from. The one place they are written: {@code behaviour import} requires its
 * checkouts to be at them, and {@code recapture assemble} fetches exactly them ({@link #fetch}).
 */
public final class BehaviourSources {
    public static final String FILE = "data/behaviour/sources.json";
    public static final String CLOUDBURST_DATA = "CloudburstMC/Data";
    public static final String ALLAY = "AllayMC/Allay";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern REPOSITORY = Pattern.compile("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\.git");

    /** One pinned source. */
    public record Source(String name, String repository, String commit) {
    }

    private BehaviourSources() {
    }

    /** The pinned sources by name, checked: a GitHub repository url and a full commit hash each. */
    public static Map<String, Source> read(Path root) throws IOException {
        JsonObject sources = JsonParser.parseString(Files.readString(root.resolve(FILE))).getAsJsonObject().getAsJsonObject("sources");
        Map<String, Source> result = new LinkedHashMap<>();
        for (var entry : sources.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            Source source = new Source(entry.getKey(), value.get("repository").getAsString(), value.get("commit").getAsString());
            if (!COMMIT.matcher(source.commit()).matches() || !REPOSITORY.matcher(source.repository()).matches()) {
                throw new IOException(FILE + ": " + source.name() + " needs a GitHub repository url and a full commit hash: " + source);
            }
            result.put(source.name(), source);
        }
        for (String required : new String[] {CLOUDBURST_DATA, ALLAY}) {
            if (!result.containsKey(required)) {
                throw new IOException(FILE + " has no pin for " + required + ": `behaviour import` needs one for each of "
                    + CLOUDBURST_DATA + " and " + ALLAY + " (a repository url and a full commit hash)");
            }
        }
        return result;
    }

    /** Runs one git command (its arguments without {@code git}). */
    @FunctionalInterface
    public interface Git {
        void run(List<String> arguments) throws IOException, InterruptedException;
    }

    /** {@link #fetch(Path, Path, Git)} with the git of the machine; a failed command fails the fetch. */
    public static final Git GIT = arguments -> {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed with " + process.exitValue());
        }
    };

    /**
     * Clones every pinned source into {@code directory} at its pin: a partial clone without a checkout (only what the import
     * reads is fetched later), HEAD set to the pinned commit. Read as data: nothing in them is run. Each command is an
     * argument array, never a shell line.
     *
     * @return the checkouts by source name
     */
    public static Map<String, Path> fetch(Path root, Path directory, Git git) throws IOException, InterruptedException {
        Map<String, Path> checkouts = new LinkedHashMap<>();
        for (Source source : read(root).values()) {
            Path checkout = directory.resolve(source.name().replace('/', '-'));
            git.run(List.of("clone", "--quiet", "--filter=blob:none", "--no-checkout", source.repository(), checkout.toString()));
            git.run(List.of("-C", checkout.toString(), "update-ref", "HEAD", source.commit()));
            checkouts.put(source.name(), checkout);
        }
        return checkouts;
    }

    /**
     * Throws unless the checkout's HEAD is the pinned commit of the source: the same sources at the same commits give the same
     * bytes, and any other commit would change the capture store without the pin saying so.
     */
    static void requirePinned(Source source, String head) throws IOException {
        if (!source.commit().equals(head)) {
            throw new IOException("The checkout of " + source.name() + " is at " + head + ", but " + FILE + " pins " + source.commit()
                + ": check out the pinned commit, or move the pin together with the regenerated data/behaviour/captures.nbt");
        }
    }
}
