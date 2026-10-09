package org.geysermc.paletter.generator;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.palette.block.BlockProperties;
import org.geysermc.paletter.palette.block.BlockPropertiesPalette;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Discovers the real set of namespaced block component keys from block_properties.json, replacing
// the doc's hand-picked BedrockBlockComponentTypes slice with what's actually present in the data -
// same approach as ItemComponentsGenerator, but block_properties.json is a partial overlay (currently
// ~98 of ~1379 block types), so this only covers components used by blocks that happen to have an
// entry there. Value types aren't inferred, so every constant comes out as BedrockComponentType<?>.
public final class BlockComponentsGenerator extends Generator<BlockPropertiesPalette> {
    @Override
    protected String outputPackage() {
        return "org.geysermc.paletter.api.component";
    }

    @Override
    protected String outputClass() {
        return "BedrockBlockComponentTypes.java";
    }

    @Override
    protected BlockPropertiesPalette fromVersion(PaletteVersion version) {
        try {
            return PaletterPalettes.getOrLoad(version).blockPropertiesPalette();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected List<Field> generateFields(BlockPropertiesPalette value) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (BlockProperties block : value.blocks()) {
            for (String key : block.components().keySet()) {
                if (key.indexOf(':') < 0) {
                    continue;
                }
                fields.computeIfAbsent(key, name -> new Field(Visibility.PUBLIC, "BedrockComponentType<?>", constantName(name))
                        .asStatic()
                        .asFinal()
                        .withDefaultValue("BedrockComponentType.of(\"%s\")".formatted(name)));
            }
        }
        return new ArrayList<>(fields.values());
    }

    @Override
    protected List<Method> generateMethods(BlockPropertiesPalette value) {
        return List.of();
    }
}
