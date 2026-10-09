package org.geysermc.paletter.generator;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.palette.item.ItemComponentData;
import org.geysermc.paletter.palette.item.ItemComponentPalette;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Discovers the real set of namespaced item component keys from item_components.nbt, replacing
// the doc's hand-picked BedrockItemComponentTypes slice with what's actually present in the data.
// Value types aren't inferred - every constant comes out as BedrockComponentType<?>.
public final class ItemComponentsGenerator extends Generator<ItemComponentPalette> {
    @Override
    protected String outputPackage() {
        return "org.geysermc.paletter.api.component";
    }

    @Override
    protected String outputClass() {
        return "BedrockItemComponentTypes.java";
    }

    @Override
    protected ItemComponentPalette fromVersion(PaletteVersion version) {
        try {
            return PaletterPalettes.getOrLoad(version).itemComponentPalette();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected List<Field> generateFields(ItemComponentPalette value) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (ItemComponentData item : value.items()) {
            for (String key : item.components().keySet()) {
                if (key.indexOf(':') < 0) {
                    // Skips "item_properties"/"item_tags" - synthetic buckets, not real component types.
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
    protected List<Method> generateMethods(ItemComponentPalette value) {
        return List.of();
    }
}
