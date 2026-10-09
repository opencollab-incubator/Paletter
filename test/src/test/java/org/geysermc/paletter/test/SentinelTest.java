package org.geysermc.paletter.test;

import org.geysermc.paletter.api.BedrockData;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlock;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.block.CustomBedrockBlock;
import org.geysermc.paletter.api.block.VersionedBedrockBlock;
import org.geysermc.paletter.api.component.BedrockComponentType;
import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.component.BedrockItemComponentTypes;
import org.geysermc.paletter.api.data.BedrockBlockData;
import org.geysermc.paletter.api.data.BedrockItemData;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.item.CustomBedrockItem;
import org.geysermc.paletter.api.item.VersionedBedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// A small hand-picked smoke check, independent of a full golden-file snapshot - see §7 of the concept doc.
public class SentinelTest {
    private static final BedrockVersion VERSION = BedrockVersion.of("26.50");

    @Test
    public void stoneExists() {
        BlockPalette palette = BedrockData.blockPalette(VERSION);
        BedrockBlock stone = BedrockBlock.of("minecraft:stone");

        BedrockBlockState defaultState = palette.get(stone).defaultState();
        assertEquals(stone, defaultState.block());

        BedrockBlockData data = palette.dataFromState(defaultState);
        assertNotNull(data.components());
        assertEquals(1.5, data.attributes().orElseThrow().hardness());
        // byRuntimeId resolves fully too - no follow-up get(state) needed.
        assertEquals(defaultState, palette.byRuntimeId(data.runtimeId()).orElseThrow());

        // Resolving once via BlockPalette.block(...) gives a VersionedBedrockBlock that no longer
        // needs the palette passed back in for states()/attributes().
        VersionedBedrockBlock versionedStone = palette.get(stone);
        assertTrue(versionedStone.states().contains(defaultState));
        assertEquals(data.attributes(), versionedStone.attributes());
    }

    @Test
    public void appleExists() {
        ItemPalette palette = BedrockData.itemPalette(VERSION);
        BedrockItem apple = BedrockItem.of("minecraft:apple");

        VersionedBedrockItem data = palette.get(apple);
        assertFalse(data.isFallback());
        assertEquals(apple, data.item());

        // runtimeId() lives on the resolved item itself now, not the palette.
        int runtimeId = data.runtimeId();
        // byRuntimeId/byIdentifier/items() all resolve fully too - one palette call is enough
        // to get an item that answers everything below without touching the palette again.
        VersionedBedrockItem byId = palette.byRuntimeId(runtimeId).orElseThrow();
        assertEquals(apple, byId.item());
        assertEquals("minecraft:apple", byId.identifier());
        assertEquals(data.components(), byId.components());
        assertEquals(Optional.of(byId.item()), palette.byIdentifier("minecraft:apple").map(VersionedBedrockItem::item));
        assertTrue(palette.items().contains(byId));
        // Per the concept doc's own §7 sentinel example: "apple has FOOD".
        assertTrue(data.components().has(BedrockItemComponentTypes.FOOD));
        // The resolved item is itself a BedrockItem - no palette needed for this lookup.
        assertEquals("minecraft:apple", data.identifier());
    }

    // Component types generated from real data (BedrockItemComponentTypes) come out as
    // BedrockComponentType<?> - can't be used with Builder.set(type, value) due to wildcard
    // capture, so this test declares its own concretely-typed one instead.
    private static final BedrockComponentType<Integer> TEST_DAMAGE = BedrockComponentType.of("geyser:test_damage");

    @Test
    public void customItemRegistersWithFallback() {
        ItemPalette base = BedrockData.itemPalette(VERSION);
        BedrockItem missing = BedrockItem.of("geyser:not_a_real_item");
        BedrockItem ironIngot = BedrockItem.of("minecraft:iron_ingot");

        ItemPalette withCustom = base.toBuilder()
                .register(new CustomBedrockItem("geyser:ruby_sword", BedrockComponents.builder()
                        .set(TEST_DAMAGE, 500)
                        .build()))
                .fallback(missing, ironIngot)
                .build();

        BedrockItem ruby = BedrockItem.of("geyser:ruby_sword");
        BedrockItemData rubyData = withCustom.get(ruby);
        assertFalse(rubyData.isFallback());
        assertEquals(500, rubyData.components().get(TEST_DAMAGE).orElseThrow());
        assertTrue(rubyData.runtimeId() > 0);

        BedrockItemData fallbackData = withCustom.get(missing);
        assertTrue(fallbackData.isFallback());
        assertEquals(ironIngot, fallbackData.item());

        // The base palette this was built from is untouched.
        assertThrows(IllegalArgumentException.class, () -> base.get(ruby));
    }

    @Test
    public void customBlockRegisters() {
        BlockPalette base = BedrockData.blockPalette(VERSION);

        BlockPalette withCustom = base.toBuilder()
                .register(new CustomBedrockBlock("geyser:test_block", BedrockComponents.builder().build(), Map.of()))
                .build();

        BedrockBlock custom = BedrockBlock.of("geyser:test_block");
        BedrockBlockState state = withCustom.get(custom).defaultState();
        int runtimeId = withCustom.dataFromState(state).runtimeId();

        assertEquals(state, withCustom.byRuntimeId(runtimeId).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> base.get(custom).defaultState());
    }

    @Test
    public void convertFallsBackToDefaultStateForUnknownStateValue() {
        BedrockBlock stone = BedrockBlock.of("minecraft:stone");
        BedrockBlockState madeUpState = new BedrockBlockState(stone, Map.of("this_state_does_not_exist", "nope"));

        Optional<BedrockBlockState> converted = BedrockData.convert(madeUpState, VERSION, VERSION);
        assertTrue(converted.isPresent());
        assertEquals(BedrockData.blockPalette(VERSION).get(stone).defaultState(), converted.get());
    }
}
