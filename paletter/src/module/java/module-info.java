/**
 * Paletter: Bedrock item and block palettes. The exported packages are the API (see {@code docs/api.md}); before 1.0
 * it may change in any release. Everything else, in particular {@code dev.opencollab.paletter.internal.*} (the encoders
 * and the generated wire shapes), is internal.
 * <p>
 * This descriptor is the single source of truth for what is API: the build compiles it against the main classes
 * ({@code :paletter:compileModuleInfo}), the API boundary test reads its exports, and the binary compatibility check
 * ({@code :paletter:apiCompat}) covers exactly these packages.
 * <p>
 * It is not packaged into the jar yet. Cloudburst's dependencies split packages over several jars (fastutil's
 * {@code it.unimi.dsi.fastutil.ints}, {@code ...objects} are in a dozen automatic modules), so no module graph containing
 * them resolves on the module path; a packaged descriptor could not be used by anyone. The jar declares
 * {@code Automatic-Module-Name: dev.opencollab.paletter} instead, and the descriptor ships once the dependencies are modular.
 * The fastutil requirement is left out here for the same reason.
 */
module dev.opencollab.paletter {
    requires transitive org.cloudburstmc.protocol.bedrock.codec;
    requires transitive org.cloudburstmc.protocol.common;
    requires transitive org.cloudburstmc.nbt;
    requires static org.checkerframework.checker.qual;

    exports dev.opencollab.paletter;
    exports dev.opencollab.paletter.annotation;
    exports dev.opencollab.paletter.behaviour;
    exports dev.opencollab.paletter.block;
    exports dev.opencollab.paletter.block.component;
    exports dev.opencollab.paletter.creative;
    exports dev.opencollab.paletter.data;
    exports dev.opencollab.paletter.item;
    exports dev.opencollab.paletter.item.component;
    exports dev.opencollab.paletter.property;
    exports dev.opencollab.paletter.vanilla;
    exports dev.opencollab.paletter.vanilla.state;
}
