package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.data.BundledFiles;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

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
    ModelCommands.Docs.class, ModelCommands.Addon.class, ModelCommands.Infer.class, ModelCommands.CheckMappings.class,
    GeneratorCommands.Constants.class, GeneratorCommands.Components.class, GeneratorCommands.InspectCommand.class,
    BdsCommands.BehaviourCommand.class, BdsCommands.BehaviourProbeCommand.class, BdsCommands.BehaviourDumpCommand.class,
    BdsCommands.ScriptDumpCommand.class,
    DataCommands.VoxelShapesCommand.class, UpdateCommands.RecipesCaptureCommand.class, DataCommands.RecipesDiffCommand.class,
    DataCommands.RecipesImportCommand.class
})
public final class Cli implements Runnable {
    /** The docs commit the committed model was generated from (MicrosoftDocs/minecraft-creator main, 2026-11-06). */
    public static final String DEFAULT_COMMIT = "457cdeaa5aa428429884b092895f3979b1f69ad3";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** The bundled Paletter version {@code name} from the library jar. */
    public static VanillaData reference(String name) throws IOException {
        return dev.opencollab.paletter.data.PaletteDataSource.bundled().load(new dev.opencollab.paletter.data.DataVersion(name, 0));
    }

    static VanillaData readPalette(Path file) throws IOException {
        return BundledFiles.read(file);
    }

    static void put(Map<String, String> options, String name, String value) {
        if (value != null) {
            options.put(name, value);
        }
    }

    static void flag(Map<String, String> options, String name, boolean value) {
        if (value) {
            options.put(name, "true");
        }
    }
}
