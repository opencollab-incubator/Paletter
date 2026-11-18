package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.internal.BlockIdOrder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BDS numbers custom blocks in descending order of their file names, compared in upper case (Windows BDS), so '_' sorts
 * after letters: a:b_x gets the lowest id, although as plain text it is the last of the three.
 */
class BlockIdOrderTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static int blockId(Palette palette, String identifier) {
        return palette.blocks().blockProperties().stream().filter(property -> property.getName().equals(identifier)).findFirst().orElseThrow()
            .getProperties().getCompound("vanilla_block_data").getInt("block_id");
    }

    @Test
    void underscoreSortsAfterLettersInUpperCase() {
        PaletteLayer.Builder layer = PaletteLayer.builder("order");
        for (String identifier : new String[] {"a:bx", "a:by", "a:b_x"}) {
            layer.block(CustomBlock.builder(identifier).menuCategory(CreativeCategory.CONSTRUCTION).build());
        }
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer.build());

        assertEquals(10000, blockId(palette, "a:b_x"));
        assertEquals(10001, blockId(palette, "a:by"));
        assertEquals(10002, blockId(palette, "a:bx"));
    }

    /**
     * The order BDS 1.26.36.1 numbered the blocks of one pack in (captured, see the fixture): digits as text
     * ({@code b_transformation_99} before {@code b_transformation_100}, {@code _8} after {@code _80}), {@code _} after letters
     * ({@code b_block_entity_*} before {@code block_placer_*}). A block of a later behavior pack is numbered after the main
     * pack, whatever its name: {@code paletter:engine_0} would be first by name.
     */
    @Test
    void capturedBdsOrder() throws IOException {
        List<String> main = new ArrayList<>();
        List<String> laterPacks = new ArrayList<>();
        int lastMainId = 0;
        try (InputStream stream = BlockIdOrderTest.class.getResourceAsStream("/block-id-order/bds-1.26.36.1.txt")) {
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] row = line.trim().split(" ");
                if (row[1].startsWith("paletter:engine_")) {
                    assertTrue(Integer.parseInt(row[0]) > lastMainId, row[1]);
                    laterPacks.add(row[1]);
                } else {
                    assertTrue(laterPacks.isEmpty(), "a main pack block after a later pack's: " + row[1]);
                    lastMainId = Integer.parseInt(row[0]);
                    main.add(row[1]);
                }
            }
        }
        assertTrue(main.size() > 200, "only " + main.size() + " blocks");
        assertEquals(main, main.stream().sorted(BlockIdOrder.DESCENDING).toList());
        assertEquals(List.of("paletter:engine_0", "paletter:engine_1"), laterPacks);
        assertTrue(BlockIdOrder.DESCENDING.compare("paletter:engine_0", main.getFirst()) < 0);
    }
}
