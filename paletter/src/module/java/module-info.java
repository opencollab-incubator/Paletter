/**
 * Paletter: Bedrock item and block palettes. The exported packages are the API, versioned by semantic versioning (see
 * {@code API.md}); everything else, in particular {@code org.geysermc.paletter.internal.*} (the encoders and the generated
 * wire shapes), is internal and may change in any release.
 * <p>
 * This descriptor is the single source of truth for what is API: the build compiles it against the main classes
 * ({@code :paletter:compileModuleInfo}), the API boundary test reads its exports, and the binary compatibility check
 * ({@code :paletter:apiCompat}) covers exactly these packages.
 * <p>
 * It is not packaged into the jar yet. Cloudburst's dependencies split packages over several jars (fastutil's
 * {@code it.unimi.dsi.fastutil.ints}, {@code ...objects} are in a dozen automatic modules), so no module graph containing
 * them resolves on the module path; a packaged descriptor could not be used by anyone. The jar declares
 * {@code Automatic-Module-Name: org.geysermc.paletter} instead, and the descriptor ships once the dependencies are modular.
 * The fastutil requirement is left out here for the same reason.
 */
module org.geysermc.paletter {
    requires transitive org.cloudburstmc.protocol.bedrock.codec;
    requires transitive org.cloudburstmc.protocol.common;
    requires transitive org.cloudburstmc.nbt;
    requires static org.checkerframework.checker.qual;

    exports org.geysermc.paletter;
    exports org.geysermc.paletter.annotation;
    exports org.geysermc.paletter.block;
    exports org.geysermc.paletter.block.component;
    exports org.geysermc.paletter.creative;
    exports org.geysermc.paletter.data;
    exports org.geysermc.paletter.item;
    exports org.geysermc.paletter.item.component;
    exports org.geysermc.paletter.property;
    exports org.geysermc.paletter.vanilla;
    exports org.geysermc.paletter.vanilla.state;
}
