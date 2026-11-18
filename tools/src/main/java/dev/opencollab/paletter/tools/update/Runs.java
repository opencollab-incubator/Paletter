package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.internal.data.VanillaData;
import java.nio.file.Path;

/** What the server runs gave; fields stay null when a step was impossible. */
final class Runs {
    Path dumpLog;
    String dumpJson;
    Integer protocol;
    String bdsVersion;
    /** The protocol of the codec that decoded the captures when it is not {@link #protocol} (the library has none for it), else null. */
    Integer codecProtocol;
    /** The Script API module versions of this build's docgen output (null if it wrote none). */
    java.util.List<String> scriptModules;
    /** The schema this run's docgen output gave (null if it gave none): the spec add-ons of this run use it, not the committed file. */
    dev.opencollab.paletter.tools.model.Schema schema;
    /** The format version of the add-on packs of this build: the model's, or the build's own if it is older. */
    String formatVersion;
    boolean netherNet;
    String existing;
    VanillaData reference;
    String referenceName;
    Path imported;
    Path vanilla;
    Path creative;
    /** The capture of the {@code /give} start ({@code block-items.json}), null without one. */
    Path give;
    /** The bundled version this run's network data belongs to (null without a capture). */
    String bundledName;
    /** Whether this run replaced the voxel shape captures of its build: the bundled voxel shapes follow. */
    boolean voxelShapesWritten;
    final ExperimentRun experiments = new ExperimentRun();
}
