package org.geysermc.paletter.generator;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.palette.item.Item;
import org.geysermc.paletter.palette.item.ItemPalette;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ItemsGenerator extends Generator<ItemPalette> {
    @Override
    protected String outputPackage() {
        return "org.geysermc.paletter.api.item";
    }

    @Override
    protected String outputClass() {
        return "BedrockItems.java";
    }

    @Override
    protected ItemPalette fromVersion(PaletteVersion version) {
        try {
            return PaletterPalettes.getOrLoad(version).itemPalette();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected List<Field> generateFields(ItemPalette value) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Item item : value.items()) {
            fields.computeIfAbsent(item.name(), name -> new Field(Visibility.PUBLIC, "BedrockItem", constantName(name))
                    .asStatic()
                    .asFinal()
                    .withDefaultValue("BedrockItem.of(\"%s\")".formatted(name)));
        }
        return new ArrayList<>(fields.values());
    }

    @Override
    protected List<Method> generateMethods(ItemPalette value) {
        return List.of();
    }
}
