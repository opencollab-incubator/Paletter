package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paletter owns the order of custom blocks: identifier descending, whatever order they are registered in. */
class CustomOrderTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock block(String identifier) {
        return CustomBlock.builder(identifier).creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION)).build();
    }

    private static Palette palette(String... identifiers) {
        PaletteLayer.Builder layer = PaletteLayer.builder("order");
        for (String identifier : identifiers) {
            layer.block(block(identifier));
        }
        return PALETTER.palette(PALETTER.latest().protocolVersion(), layer.build());
    }

    @Test
    void propertyListAndBlockItemIdsFollowTheIdentifierNotTheRegistration() {
        List<String> ascending = List.of("order:a", "order:b", "order:c", "order:d");
        List<String> shuffled = List.of("order:c", "order:a", "order:d", "order:b");
        Palette first = palette(ascending.toArray(String[]::new));
        Palette second = palette(shuffled.toArray(String[]::new));

        for (Palette palette : List.of(first, second)) {
            List<String> properties = new ArrayList<>();
            palette.blockProperties().forEach(property -> {
                if (property.getName().startsWith("order:")) {
                    properties.add(property.getName());
                }
            });
            assertEquals(List.of("order:d", "order:c", "order:b", "order:a"), properties);

            int previous = -1;
            for (String identifier : List.of("order:d", "order:c", "order:b", "order:a")) {
                int runtimeId = palette.items().getDefinition(identifier).getRuntimeId();
                assertTrue(runtimeId > previous, identifier + " is registered after the identifiers above it");
                previous = runtimeId;
            }
        }
        for (String identifier : ascending) {
            assertEquals(first.items().getDefinition(identifier).getRuntimeId(), second.items().getDefinition(identifier).getRuntimeId(), identifier);
        }
        assertTrue(first.blockProperties().equals(second.blockProperties()));
    }

    /** A consumer that registers block items itself (Geyser) decides their slot among the custom items. */
    @Test
    void explicitBlockItemsKeepTheirRegistrationOrder() {
        PaletteLayer.Builder layer = PaletteLayer.builder("explicit");
        for (String identifier : List.of("order:a", "order:c", "order:b")) {
            CustomBlock block = block(identifier);
            layer.blockItem(identifier, block.item(), null).block(block);
        }
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), layer.build());
        int a = palette.items().getDefinition("order:a").getRuntimeId();
        int c = palette.items().getDefinition("order:c").getRuntimeId();
        int b = palette.items().getDefinition("order:b").getRuntimeId();
        assertTrue(a < c && c < b, "registered a, c, b");
    }
}
