package dev.opencollab.paletter.tools;

import java.util.List;

/**
 * The paths a data update or a recapture may change, and the inputs of the spec add-on among them. The workflows read the
 * data paths from {@code .github/data-paths.txt} ({@link #FILE}), which {@code WorkflowConsistencyTest} keeps equal to
 * {@link #ALL}: the patch of a capture job holds only these paths, and the publish jobs apply nothing else.
 */
public final class DataPaths {
    /** The list the workflows read, one path per line, relative to the repository root. */
    public static final String FILE = ".github/data-paths.txt";
    /** The committed data, the bundled resources, the generated code and the API dumps of paletter and paletter-recipes. */
    public static final List<String> ALL = List.of("data", "paletter/src/main/resources/paletter", "paletter/src/generated", "paletter/api",
        "paletter-recipes/src/main/resources/paletter", "paletter-recipes/src/generated", "paletter-recipes/api");
    /**
     * What the spec add-on is generated from besides the code and a build's schema: when one of them changes, the captures of
     * every kept build answer for an add-on that is no longer the current one.
     */
    public static final List<String> ADDON_INPUTS = List.of("data/model/bds-feedback.json", "data/model/fixtures.json",
        "data/model/components.json", "data/model/bds-keys.json");

    private DataPaths() {
    }
}
