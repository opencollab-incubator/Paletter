package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.internal.data.VanillaData;
import java.nio.file.Path;

/** What the server runs gave; fields stay null when a step was impossible. */
final class Runs {
    Path dumpLog;
    String dumpJson;
    Integer protocol;
    String bdsVersion;
    /** The Script API module versions of this build's docgen output (null if it wrote none). */
    java.util.List<String> scriptModules;
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
