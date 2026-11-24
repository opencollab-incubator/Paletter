package dev.opencollab.paletter.tools.daily;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.update.BuildHistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * {@code daily decide}: which builds the daily BDS data check captures (docs/data-pipeline.md, "The daily check"). Per
 * channel, the newest build B of the download links API (one request for both channels, none for an explicit version),
 * and the first row that applies:
 * <ol>
 *     <li>{@code --force}: capture;</li>
 *     <li>B is recorded in {@code data/bds/builds.json} or has a {@code data/bds/B} directory: skip, known;</li>
 *     <li>the open pull request of {@code bds-data/<channel>} (not from a fork) carries {@code <!-- bds-check: B -->}: skip,
 *     it waits there;</li>
 *     <li>the workflow's own open issue of the channel carries {@code <!-- bds-check: B <this commit> 2 -->}: skip, it
 *     failed twice at this commit;</li>
 *     <li>else capture, attempt 2 after one failure at this commit, else 1.</li>
 * </ol>
 * Issue and pull request text is data: only the workflow's own issue (its exact title, by github-actions) and a pull
 * request of a branch of this repository count, and only a marker on a line of its own.
 */
public final class DailyDecide {
    /** The channels, as the workflows name them. */
    public static final List<String> CHANNELS = List.of("release", "preview");
    /** The authors the workflow's own issues have, as {@code gh issue list --json author} names them. */
    static final Set<String> BOT_AUTHORS = Set.of("app/github-actions", "github-actions", "github-actions[bot]");
    private static final String BUILD = "(1(?:\\.[0-9]{1,9}){3})";
    private static final Pattern PULL_MARKER = Pattern.compile("(?m)^<!-- bds-check: " + BUILD + " -->\\r?$");
    private static final Pattern ISSUE_MARKER = Pattern.compile("(?m)^<!-- bds-check: " + BUILD + " ([0-9a-f]{40}) ([12]) -->\\r?$");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** The most attempts at one build per commit: one retry tells a flaky runner from a real failure. */
    public static final int ATTEMPTS = 2;

    /** The answer of the download links API, asked for at most once per run. */
    @FunctionalInterface
    public interface Links {
        String get() throws IOException, InterruptedException;
    }

    /** An open issue as {@code gh issue list --json number,title,author,body} gives it. */
    public record Issue(int number, String title, String author, String body) {
    }

    /** An open pull request as {@code gh pr list --json number,headRefName,isCrossRepository,body} gives it. */
    public record Pull(int number, String headRef, boolean crossRepository, String body) {
    }

    /**
     * What the run does with the newest build of a channel.
     *
     * @param attempt the attempt at this build and commit for a capture (1 or 2), 0 for a skip
     */
    public record Decision(String channel, String build, boolean capture, int attempt, String reason) {
        public Decision {
            if (!CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("Not a channel: " + channel);
            }
            BuildNumber.require(build);
            if (capture != (attempt >= 1 && attempt <= ATTEMPTS)) {
                throw new IllegalArgumentException("A capture is attempt 1 or 2, a skip none: " + attempt);
            }
        }
    }

    private DailyDecide() {
    }

    /** The title of the issue the workflow opens for a failed check of a channel. */
    public static String issueTitle(String channel) {
        return "BDS data: the " + channel + " check failed";
    }

    /**
     * Decides every channel.
     *
     * @param channels the channels to check, in order
     * @param version an explicit build (one channel only), or null for the newest of each channel
     * @param commit the commit the workflow runs at
     */
    public static List<Decision> decide(Path root, List<String> channels, String version, boolean force, String commit, List<Issue> issues,
                                        List<Pull> pulls, Links links) throws IOException, InterruptedException {
        if (!COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException("Not a commit: " + commit);
        }
        if (version != null && channels.size() != 1) {
            throw new IllegalArgumentException("An explicit version belongs to one channel: name it (--channel release or preview)");
        }
        String answer = null;
        List<Decision> decisions = new ArrayList<>();
        for (String channel : channels) {
            String build;
            if (version != null) {
                build = BuildNumber.require(version);
            } else {
                if (answer == null) {
                    answer = links.get();
                }
                build = BdsDistribution.newestBuild(answer, channel.equals("preview"));
            }
            decisions.add(decide(root, channel, build, force, commit, issues, pulls));
        }
        return decisions;
    }

    /** The first row of the table that applies to one channel's build. */
    static Decision decide(Path root, String channel, String build, boolean force, String commit, List<Issue> issues, List<Pull> pulls)
        throws IOException {
        int failures = failures(issues, channel, build, commit);
        int attempt = Math.min(failures + 1, ATTEMPTS);
        if (force) {
            return new Decision(channel, build, true, attempt, "forced");
        }
        if (BuildHistory.read(root).containsKey(build)) {
            return new Decision(channel, build, false, 0, "known: recorded in data/bds/builds.json");
        }
        if (Files.isDirectory(root.resolve(Layout.BDS).resolve(build))) {
            return new Decision(channel, build, false, 0, "known: data/bds/" + build + " exists");
        }
        for (Pull pull : pulls) {
            if (!pull.crossRepository() && pull.headRef().equals("bds-data/" + channel) && markers(PULL_MARKER, pull.body()).stream()
                .anyMatch(marker -> marker.group(1).equals(build))) {
                return new Decision(channel, build, false, 0, "waiting in #" + pull.number());
            }
        }
        if (failures >= ATTEMPTS) {
            return new Decision(channel, build, false, 0, "failed " + failures + " times at " + commit.substring(0, 12)
                + ": a new commit, a new build or `force` tries again");
        }
        return new Decision(channel, build, true, attempt, failures == 0 ? "new" : "new; it failed once at this commit, the one retry");
    }

    /** The attempts the workflow's own issue of the channel records for this build at this commit (0 when none). */
    static int failures(List<Issue> issues, String channel, String build, String commit) {
        int failures = 0;
        for (Issue issue : issues) {
            if (issue.title().equals(issueTitle(channel)) && BOT_AUTHORS.contains(issue.author())) {
                for (MatchResult marker : markers(ISSUE_MARKER, issue.body())) {
                    if (marker.group(1).equals(build) && marker.group(2).equals(commit)) {
                        failures = Math.max(failures, Integer.parseInt(marker.group(3)));
                    }
                }
            }
        }
        return failures;
    }

    private static List<MatchResult> markers(Pattern pattern, String body) {
        return body == null ? List.of() : pattern.matcher(body).results().toList();
    }

    /** The open issues of {@code gh issue list --json number,title,author,body}. */
    public static List<Issue> issues(String json) {
        List<Issue> issues = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject issue = element.getAsJsonObject();
            JsonElement author = issue.get("author");
            issues.add(new Issue(issue.get("number").getAsInt(), string(issue, "title"),
                author != null && author.isJsonObject() ? string(author.getAsJsonObject(), "login") : "", string(issue, "body")));
        }
        return issues;
    }

    /** The open pull requests of {@code gh pr list --json number,headRefName,isCrossRepository,body}. */
    public static List<Pull> pulls(String json) {
        List<Pull> pulls = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject pull = element.getAsJsonObject();
            // A pull request that does not say where it comes from counts as a fork's
            boolean cross = !pull.has("isCrossRepository") || pull.get("isCrossRepository").getAsBoolean();
            pulls.add(new Pull(pull.get("number").getAsInt(), string(pull, "headRefName"), cross, string(pull, "body")));
        }
        return pulls;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /** The captures as the {@code include} list of a GitHub Actions matrix: {@code channel}, {@code build}, {@code attempt}. */
    public static String matrix(List<Decision> decisions) {
        JsonArray include = new JsonArray();
        for (Decision decision : decisions) {
            if (decision.capture()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("channel", decision.channel());
                entry.addProperty("build", decision.build());
                entry.addProperty("attempt", decision.attempt());
                include.add(entry);
            }
        }
        JsonObject matrix = new JsonObject();
        matrix.add("include", include);
        return matrix.toString();
    }

    /** Every decision as a markdown table, for the job summary. */
    public static String summary(List<Decision> decisions) {
        StringBuilder text = new StringBuilder("| Channel | Newest build | Decision |\n|---|---|---|\n");
        for (Decision decision : decisions) {
            text.append("| ").append(decision.channel()).append(" | ").append(decision.build()).append(" | ")
                .append(decision.capture() ? "capture (attempt " + decision.attempt() + "): " : "skip: ").append(decision.reason()).append(" |\n");
        }
        return text.toString();
    }
}
