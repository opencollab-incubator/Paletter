package org.geysermc.paletter.generator;

import org.cloudburstmc.nbt.NbtType;
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

// Same source as ItemComponentsGenerator (item_components.nbt), but reads the "item_tags" bucket
// instead of the namespaced component keys - a distinct catalog: every distinct tag string seen
// across all items.
public final class ItemTagsGenerator extends Generator<ItemComponentPalette> {
    @Override
    protected String outputPackage() {
        return "org.geysermc.paletter.api.item";
    }

    @Override
    protected String outputClass() {
        return "BedrockItemTags.java";
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
            for (String tag : item.components().getList("item_tags", NbtType.STRING)) {
                fields.computeIfAbsent(tag, name -> new Field(Visibility.PUBLIC, "String", constantName(name))
                        .asStatic()
                        .asFinal()
                        .withDefaultValue("\"%s\"".formatted(name)));
            }
        }
        return new ArrayList<>(fields.values());
    }

    @Override
    protected List<Method> generateMethods(ItemComponentPalette value) {
        return List.of();
    }
}
