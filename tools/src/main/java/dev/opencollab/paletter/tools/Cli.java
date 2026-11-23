package dev.opencollab.paletter.tools;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The one command line of the tooling: {@code ./gradlew :tools:run --args="<command> ..."}. Paths are relative to the
 * working directory, which is the repository root for Gradle; {@code --root} overrides it where a command reads the
 * repository. See docs/data-pipeline.md for what each command does. This class only dispatches: the commands live in
 * {@link DataCommands}, {@link BdsCommands}, {@link UpdateCommands}, {@link CaptureCommands}, {@link ModelCommands} and
 * {@link GeneratorCommands}.
 */
@Command(name = "paletter-tools", mixinStandardHelpOptions = true, subcommands = {
    DataCommands.Import.class, BdsCommands.BdsInventoryCommand.class, BdsCommands.BdsSchemaCommand.class, BdsCommands.WhiteboxCommand.class,
    BdsCommands.WhiteboxCoverageCommand.class, BdsCommands.BdsCompareCommand.class, UpdateCommands.UpdateCommand.class, UpdateCommands.Builds.class,
    UpdateCommands.SpecCaptureCommand.class,
    DataCommands.VerifyCommand.class, DataCommands.ExportCommand.class, DataCommands.BaselineCommand.class, DataCommands.CheckData.class,
    DataCommands.DataDiffCommand.class, UpdateCommands.CreativeProbeCommand.class, UpdateCommands.EducationCaptureCommand.class,
    DataCommands.ExperimentMarksCommand.class,
    CaptureCommands.CaptureCommand.class, CaptureCommands.Experiments.class, CaptureCommands.CompareCommand.class,
    ModelCommands.Docs.class, ModelCommands.Addon.class, ModelCommands.Infer.class, ModelCommands.CheckMappings.class, ModelCommands.Retire.class,
    GeneratorCommands.Constants.class, GeneratorCommands.Components.class, GeneratorCommands.Blocks.class, GeneratorCommands.InspectCommand.class,
    BdsCommands.BehaviourCommand.class, BdsCommands.BehaviourProbeCommand.class, BdsCommands.BehaviourDumpCommand.class,
    BdsCommands.ScriptDumpCommand.class,
    DataCommands.VoxelShapesCommand.class, UpdateCommands.RecipesCaptureCommand.class, DataCommands.RecipesDiffCommand.class,
    DataCommands.RecipesImportCommand.class
})
public final class Cli implements Runnable {
    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** The command line as {@link #main} runs it. */
    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new Cli());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        // A mistake of the caller (a missing or wrong option) is a message, not a stack trace
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            if (!(exception instanceof IllegalArgumentException)) {
                throw exception;
            }
            Console.error(exception.getMessage());
            return 1;
        });
        return commandLine;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
