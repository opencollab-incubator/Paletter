package org.geysermc.paletter.generator;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.palette.block.Block;
import org.geysermc.paletter.palette.block.BlockPalette;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public final class BlocksGenerator extends Generator<BlockPalette> {
    @Override
    protected String outputPackage() {
        return "org.geysermc.paletter.api.block";
    }

    @Override
    protected String outputClass() {
        return "BedrockBlocks.java";
    }

    @Override
    protected BlockPalette fromVersion(PaletteVersion version) {
        try {
            return PaletterPalettes.getOrLoad(version).blockPalette();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected List<Field> generateFields(BlockPalette value) {
        // Dedupes by identifier rather than BlockData/blockId - blockId is the pre-1.21.130 legacy
        // numeric id and no longer distinguishes block types reliably.
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Block block : value.blocks().stream().sorted(Comparator.comparing(Block::name)).toList()) {
            fields.computeIfAbsent(block.name(), name -> new Field(Visibility.PUBLIC, "BedrockBlock", constantName(name))
                    .asStatic()
                    .asFinal()
                    .withDefaultValue("BedrockBlock.of(\"%s\")".formatted(name)));
        }
        return new ArrayList<>(fields.values());
    }

    @Override
    protected List<Method> generateMethods(BlockPalette value) {
        return List.of();
    }
}
