package dev.opencollab.paletter.tools;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line as a whole: every command is wired and its options follow the one vocabulary. */
class CliTest {
    /** The spellings an option had before the build, channel and data version each got one name. */
    private static final Set<String> RETIRED = Set.of("--bds", "--preview", "--name", "--reference", "--bds-version");

    private static void walk(CommandLine command, List<CommandLine> into) {
        into.add(command);
        command.getSubcommands().values().stream().distinct().forEach(sub -> walk(sub, into));
    }

    private static List<CommandLine> commands() {
        List<CommandLine> all = new ArrayList<>();
        walk(Cli.commandLine(), all);
        return all;
    }

    @Test
    void everyCommandPrintsItsHelp() {
        for (CommandLine command : commands()) {
            CommandSpec spec = command.getCommandSpec();
            StringWriter out = new StringWriter();
            command.setOut(new PrintWriter(out));
            command.usage(command.getOut());
            assertTrue(out.toString().contains(spec.name()), spec.qualifiedName() + " prints no usage");
        }
    }

    @Test
    void everyOptionHasADescription() {
        List<String> without = new ArrayList<>();
        for (CommandLine command : commands()) {
            for (OptionSpec option : command.getCommandSpec().options()) {
                if (!option.usageHelp() && !option.versionHelp() && option.description().length == 0) {
                    without.add(command.getCommandSpec().qualifiedName() + " " + option.longestName());
                }
            }
        }
        assertEquals(List.of(), without, "options without a description");
    }

    @Test
    void buildChannelAndDataVersionHaveOneNameEach() {
        List<String> retired = new ArrayList<>();
        for (CommandLine command : commands()) {
            for (OptionSpec option : command.getCommandSpec().options()) {
                for (String name : option.names()) {
                    if (RETIRED.contains(name)) {
                        retired.add(command.getCommandSpec().qualifiedName() + " " + name);
                    }
                }
            }
        }
        assertEquals(List.of(), retired, "options under a name that is now --version, --channel or --data-version");
    }

    /** The invocations of .github/workflows and the recapture rehearsal: a renamed option fails here, not in a run. */
    @Test
    void theWorkflowsInvocationsParse() {
        CommandLine cli = Cli.commandLine();
        cli.parseArgs("update", "--channel", "preview", "--version", "1.26.70.24", "--verify-only");
        cli.parseArgs("update", "--channel", "release", "--version", "1.26.63.1", "--capture-only", "out", "--against", "1.26.36.1");
        cli.parseArgs("builds", "resolve", "--channel", "preview", "--output", "build.txt");
        cli.parseArgs("bds", "cache-key", "--channel", "preview", "--version", "1.26.70.24", "--output", "key.txt");
        cli.parseArgs("bds", "fetch", "--channel", "release", "--version", "1.26.63.1");
        cli.parseArgs("daily", "decide", "--channel", "both", "--commit", "a".repeat(40), "--issues", "issues.json", "--pulls", "pulls.json",
            "--outputs", "out.txt", "--summary", "summary.md");
        cli.parseArgs("daily", "decide", "--channel", "preview", "--version", "1.26.70.24", "--force", "--commit", "a".repeat(40),
            "--issues", "issues.json", "--pulls", "pulls.json", "--outputs", "out.txt");
        cli.parseArgs("builds", "plan", "--output", "matrix.json");
        cli.parseArgs("builds", "plan", "--output", "matrix.json", "--build", "1.26.63.1", "--build", "1.26.70.24");
        cli.parseArgs("behaviour", "import", "--cloudburst-data", "cb", "--allay", "allay");
        cli.parseArgs("whitebox-coverage");
        cli.parseArgs("voxel-shapes", "import");
        cli.parseArgs("check-mappings", "--strict");
        cli.parseArgs("export", "--data-version", "26.60", "--out", "out", "--all-experiments");
        cli.parseArgs("verify", "--source", "cb", "--data-version", "26.60", "--against", "out", "--report", "r.md");
    }

    @Test
    void aWrongChannelIsRefused() {
        CommandLine cli = Cli.commandLine();
        StringWriter err = new StringWriter();
        cli.setErr(new PrintWriter(err));
        assertEquals(2, cli.execute("update", "--channel", "nightly"));
        assertTrue(err.toString().contains("--channel"), err.toString());
    }
}
