package org.geysermc.paletter.behaviour;

import org.geysermc.paletter.Fallbacks;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.ResolvedBlock;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.block.BlockResolution;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour is reached through what a palette resolved: the definition sent, whether it is a fallback, and its values. */
class ResolvedBehaviourTest {
    private static final Paletter PALETTER = Paletter.builder().build();
    private static final Paletter FORCED = Paletter.builder().fallbacks(Fallbacks.builder()
        .block(BedrockBlocks.DIRT, BlockFallback.of(BedrockBlocks.ICE).forced())
        .item(BedrockItems.ENDER_PEARL, ItemFallback.keepingAux(BedrockItems.DIAMOND_SWORD).forced())
        .build()).build();

    private static Palette latest() {
        return PALETTER.vanilla(PALETTER.latest());
    }

    @Test
    void aResolvedBlockHasTheDefinitionAndTheValuesOfThePalette() {
        Palette palette = latest();
        for (BedrockBlockState state : new BedrockBlockState[] {BedrockBlocks.ICE.defaultState(), BedrockBlocks.STONE.defaultState(),
            BedrockBlocks.OAK_STAIRS.defaultState(), BedrockBlocks.AIR.defaultState()}) {
            ResolvedBlock resolved = palette.resolve(state);
            RegisteredBlockState definition = palette.blockDefinition(state);
            assertSame(definition, resolved.definition());
            assertFalse(resolved.fallback(), state.toString());
            // The values the removed blockBehaviour gave: the position of the block in the palette's values
            assertEquals(palette.behaviour().block(palette.blocks().indexOf(definition)), resolved.behaviour());
            assertSame(definition, resolved.behaviour().state());
            assertEquals(resolved.behaviour(), palette.resolveBlock(definition.getRuntimeId()).behaviour());
        }
        assertEquals(0.98f, palette.resolve(BedrockBlocks.ICE.defaultState()).behaviour().get(BlockBehaviour.FRICTION));
        assertEquals(BlockShape.FULL_CUBE, palette.resolve(BedrockBlocks.STONE.defaultState()).behaviour().get(BlockBehaviour.COLLISION_SHAPE));
    }

    @Test
    void aRuntimeIdIsTheBlockAsSent() {
        Palette palette = latest();
        int id = palette.runtimeId(BedrockBlocks.ICE.defaultState());
        ResolvedBlock resolved = Objects.requireNonNull(palette.resolveBlock(id));
        assertSame(palette.blockDefinition(id), resolved.definition());
        assertFalse(resolved.fallback());
        assertEquals(palette.resolve(BedrockBlocks.ICE.defaultState()), resolved);
        assertNull(palette.resolveBlock(0x7eadbeef));
    }

    @Test
    void aFallbackStateHasTheValuesOfTheBlockSentForIt() {
        Palette palette = FORCED.vanilla(FORCED.latest());
        ResolvedBlock dirt = palette.resolve(BedrockBlocks.DIRT.defaultState());
        assertTrue(dirt.fallback());
        assertSame(palette.blockDefinition(BedrockBlocks.ICE.defaultState()), dirt.definition());
        assertEquals(0.98f, dirt.behaviour().get(BlockBehaviour.FRICTION), "dirt is sent as ice, so it behaves like ice");
        assertEquals(0.6f, latest().resolve(BedrockBlocks.DIRT.defaultState()).behaviour().get(BlockBehaviour.FRICTION));
        assertFalse(palette.resolve(BedrockBlocks.ICE.defaultState()).fallback());
        // By runtime id it is the block as sent, never a fallback
        assertFalse(Objects.requireNonNull(palette.resolveBlock(dirt.definition().getRuntimeId())).fallback());
    }

    @Test
    void aStateTheOldestVersionLacksIsAFallbackThere() {
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst());
        CanonicalBlocks canonical = oldest.blocks().canonical();
        int found = 0;
        for (int id = 0; id < canonical.size() && found < 50; id++) {
            if (!oldest.blocks().isMissing(id)) {
                continue;
            }
            found++;
            BedrockBlockState state = Objects.requireNonNull(canonical.handle(id));
            ResolvedBlock resolved = oldest.resolve(state);
            assertTrue(resolved.fallback(), state.toString());
            assertSame(oldest.blockDefinition(state), resolved.definition());
            // The values of the stand-in that is sent
            assertSame(resolved.definition(), resolved.behaviour().state());
        }
        assertTrue(found > 0, "the oldest version lacks states of the newest");
    }

    @Test
    void aStateOnlyAdaptedToTheVersionIsNotAFallback() {
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst());
        CanonicalBlocks canonical = oldest.blocks().canonical();
        int found = 0;
        for (int id = 0; id < canonical.size() && found < 50; id++) {
            if (oldest.blocks().resolve(canonical.state(id)).kind() != BlockResolution.Kind.ADAPTED) {
                continue;
            }
            found++;
            ResolvedBlock resolved = oldest.resolve(Objects.requireNonNull(canonical.handle(id)));
            assertFalse(resolved.fallback(), canonical.handle(id).toString());
        }
        assertTrue(found > 0, "the oldest version adapts states of the newest, e.g. stairs");
    }

    @Test
    void theOrElseAndNullPathsOfAStateThePaletteLacks() {
        Palette base = latest();
        BedrockBlockState lamp = CustomBlock.builder("resolve:lamp").build().type().defaultState();
        assertThrows(IllegalArgumentException.class, () -> base.resolve(lamp));
        assertNull(base.resolve(lamp, null));
        ResolvedBlock air = base.resolve(BedrockBlocks.AIR.defaultState());
        assertSame(air, base.resolve(lamp, air));
        // A state it has ignores orElse
        assertEquals(base.resolve(BedrockBlocks.ICE.defaultState()), base.resolve(BedrockBlocks.ICE.defaultState(), air));
    }

    @Test
    void anExtendedPaletteResolvesWithItsOwnValuesAndSharesTheBases() {
        Palette base = latest();
        CustomBlock block = CustomBlock.builder("resolve:block").component(Friction.of(0.1f)).build();
        CustomItem item = CustomItem.builder("resolve:item").component(MaxStackSize.of(16)).build();
        Palette session = base.extend(PaletteLayer.builder("session").block(block).item(item)
            .overrideItem(BedrockItems.APPLE, apple -> apple.replaceComponents(MaxStackSize.of(3))).build());

        // A shared state has the parent's values; a custom one the session's, and the parent does not have it
        assertEquals(base.resolve(BedrockBlocks.STONE.defaultState()), session.resolve(BedrockBlocks.STONE.defaultState()));
        ResolvedBlock custom = session.resolve(block.type().defaultState());
        assertEquals(0.9f, custom.behaviour().get(BlockBehaviour.FRICTION), 1e-6f);
        assertNull(base.resolve(block.type().defaultState(), null));
        assertNull(base.resolveBlock(custom.definition().getRuntimeId()));
        assertEquals(custom, session.resolveBlock(custom.definition().getRuntimeId()));

        // Items: each palette's own values, also for an override of a vanilla item
        assertEquals(3, session.resolve(BedrockItems.APPLE).behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(64, base.resolve(BedrockItems.APPLE).behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(16, session.resolve(item.type()).behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
        ItemValues pearl = session.resolve(BedrockItems.ENDER_PEARL).behaviour();
        assertEquals(base.resolve(BedrockItems.ENDER_PEARL).behaviour().get(ItemBehaviour.MAX_STACK_SIZE), pearl.get(ItemBehaviour.MAX_STACK_SIZE));
        assertSame(session.itemDefinition(BedrockItems.ENDER_PEARL), pearl.item());
    }

    @Test
    void anExtensionThatOnlyAddsBlocksKeepsItsItemResolutions() {
        Palette base = latest();
        Palette session = base.extend(PaletteLayer.builder("blocks")
            .block(CustomBlock.builder("resolve:only").component(Friction.of(0.3f)).build()).build());
        ResolvedItem resolved = Objects.requireNonNull(session.resolve(BedrockItems.APPLE));
        assertSame(base.resolve(BedrockItems.APPLE).definition(), resolved.definition());
        assertFalse(resolved.fallback());
        RegisteredItem apple = Objects.requireNonNull(session.itemDefinition(BedrockItems.APPLE));
        assertSame(apple, resolved.behaviour().item());
        assertEquals(session.behaviour().item(apple).get(ItemBehaviour.MAX_STACK_SIZE), resolved.behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
    }

    @Test
    void oneItemHasOneHandleWhicheverVariantResolvedToIt() {
        Palette base = latest();
        Palette session = base.extend(PaletteLayer.builder("handles")
            .block(CustomBlock.builder("resolve:handles").component(Friction.of(0.3f)).build()).build());
        for (Palette palette : new Palette[] {base, session}) {
            ItemValues canonical = palette.resolve(BedrockItems.APPLE).behaviour();
            ItemValues other = palette.resolve(BedrockItems.APPLE.variant(5)).behaviour();
            assertEquals(canonical, other, "a variant that is not canonical resolves to the same item");
            assertEquals(canonical.hashCode(), other.hashCode());
        }
        // An extension that did not change the item has the handle of its base
        assertEquals(base.resolve(BedrockItems.APPLE).behaviour(), session.resolve(BedrockItems.APPLE.variant(5)).behaviour());
        assertEquals(base.resolve(BedrockItems.APPLE).behaviour(), session.resolve(BedrockItems.APPLE).behaviour());
    }

    @Test
    void anItemResolvedAsAFallbackHasTheFallbacksValues() {
        Palette palette = FORCED.vanilla(FORCED.latest());
        ResolvedItem pearl = Objects.requireNonNull(palette.resolve(BedrockItems.ENDER_PEARL));
        assertTrue(pearl.fallback());
        ItemValues values = pearl.behaviour();
        assertSame(palette.itemDefinition(BedrockItems.DIAMOND_SWORD), values.item());
        assertEquals(1561, values.get(ItemBehaviour.MAX_DAMAGE));
        assertEquals(16, latest().resolve(BedrockItems.ENDER_PEARL).behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
        ResolvedItem direct = Objects.requireNonNull(palette.resolve(BedrockItems.DIAMOND_SWORD));
        assertFalse(direct.fallback());
        assertEquals(direct.behaviour().item(), values.item());
    }
}
