package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.CustomItem;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caches of extended palettes and canonical ids (weak maps keyed by the layer) must not keep a layer alive through
 * their values: a session layer that is no longer used has to be collectable, with everything compiled from it.
 */
class LayerLeakTest {
    private static final Paletter PALETTER = Palettes.paletter();

    /** Compiles a layer in every version, and drops every reference to it. */
    private static WeakReference<PaletteLayer> extendOnce(String id) {
        PaletteLayer layer = PaletteLayer.builder(id)
            .item(CustomItem.builder(id + ":ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build())
            .block(CustomBlock.builder(id + ":lamp")
                .property(BlockProperty.bool(id + ":lit"))
                .component(Geometry.of("minecraft:geometry.full_block"))
                .build())
            .build();
        PALETTER.versions().forEach(version -> {
            Palette extended = PALETTER.palette(version.protocolVersion()).extend(layer);
            assertTrue(extended.layers().contains(layer));
        });
        return new WeakReference<>(layer);
    }

    @Test
    void anExtendedLayerIsCollectableWhileTheBasePalettesLive() {
        WeakReference<PaletteLayer> layer = extendOnce("leak");
        for (int i = 0; i < 100 && layer.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertNull(layer.get(), "something keeps the layer alive: a value of a weak map references its key");
    }
}
