package org.geysermc.paletter.item;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemInternerTest {
    static final Paletter PALETTER = Paletter.bundled();

    @Test
    void unchangedItemsAreSharedAcrossVersions() {
        List<DataVersion> versions = PALETTER.versions();
        ItemRegistry older = PALETTER.vanilla(versions.get(0)).items();
        ItemRegistry newer = PALETTER.vanilla(versions.get(versions.size() - 1)).items();
        int shared = 0;
        for (RegisteredItem item : older.definitions()) {
            RegisteredItem other = newer.getDefinition(item.getRuntimeId());
            if (other != null && other.getIdentifier().equals(item.getIdentifier()) && other.getVersion() == item.getVersion()
                && other.isComponentBased() == item.isComponentBased()
                && Objects.equals(other.getComponentData(), item.getComponentData())) {
                assertSame(item, other, item.getIdentifier());
                shared++;
            }
        }
        assertTrue(shared > 0, "no item is shared between " + versions.get(0) + " and " + versions.get(versions.size() - 1));
    }

    @Test
    void samePaletteTwiceIsTheSameItems() {
        DataVersion latest = PALETTER.latest();
        assertSame(PALETTER.vanilla(latest).items().item("minecraft:apple"), PALETTER.vanilla(latest).items().item("minecraft:apple"));
    }
}
