package org.geysermc.paletter.codegen;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Public entry point to the shape inference, for tools that compare the wire shapes of two sets of captures (the
 * generator's {@code update} pipeline). Same input layout as {@code :codegen:generateComponents}: {@code root} needs
 * {@code paletter/src/main/resources/paletter} (bundled palettes, may be empty) and may have {@code codegen/captures}.
 */
public final class ShapeInference {
    private ShapeInference() {
    }

    /**
     * @return the inferred spec, as written to {@code codegen/spec/inferred.json}, without protocol splits (one shape
     *         per component over all observations)
     */
    public static JsonObject infer(Path root, List<Path> extraCaptures) throws IOException {
        return infer(root, extraCaptures, new JsonObject());
    }

    /**
     * @param overrides the content of {@code codegen/overrides.json}; only {@code protocolSplits} matter here
     * @return the inferred spec, as written to {@code codegen/spec/inferred.json}
     */
    public static JsonObject infer(Path root, List<Path> extraCaptures, JsonObject overrides) throws IOException {
        return Inference.infer(root, extraCaptures, overrides);
    }
}
