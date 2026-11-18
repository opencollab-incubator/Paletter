package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vanilla item override against what BDS 1.26.63.1 sent for an add-on that overrides {@code minecraft:apple}
 * (data/bds/1.26.63.1/override). Experimental: the client's reaction to Paletter's overrides is unverified.
 */
class ItemOverrideTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int LATEST = PALETTER.versions().getLast().protocolVersion();

    @Test
    void replacingTheAppleSendsWhatBdsSentForTheOverriddenApple() throws IOException {
        Palette palette = Palettes.palette(LATEST, PaletteLayer.builder("override")
            .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(3))).build());
        RegisteredItem apple = palette.items().getDefinition("minecraft:apple");
        assertEquals(ItemVersion.DATA_DRIVEN, apple.getVersion());
        assertTrue(apple.isComponentBased());

        NbtMap bds = bdsApple();
        assertEquals(bds.getCompound("data").getCompound("components"), apple.getComponentData().getCompound("components"));
        // The id differs on purpose: BDS moved the overridden item from 883 to 853, Paletter keeps the vanilla id
        // (unverified on a client)
        assertEquals(853, ((Number) bds.get("id")).intValue());
        assertEquals(883, apple.getRuntimeId());
    }

    private static NbtMap bdsApple() throws IOException {
        Path capture = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds/1.26.63.1/override/raw_item_registry.nbt");
        try (InputStream stream = Files.newInputStream(capture); NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            NbtMap root = (NbtMap) input.readTag();
            List<NbtMap> items = root.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND);
            return items.stream().filter(item -> item.getString("name").equals("minecraft:apple")).findFirst().orElseThrow();
        }
    }
}
