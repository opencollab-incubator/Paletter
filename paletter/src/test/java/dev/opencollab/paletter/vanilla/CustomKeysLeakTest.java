package dev.opencollab.paletter.vanilla;

import dev.opencollab.paletter.Lookup;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.item.BedrockItemStack;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identifiers that are not vanilla (a client, a config, a plugin chose them) must never accumulate in static state for the
 * life of the JVM: keys of unknown identifiers are plain values, equal by identifier, and nothing is interned.
 */
class CustomKeysLeakTest {
    private static final int KEYS = 20_000;
    private static final Paletter PALETTER = Paletter.bundled();

    /** Every static map and collection of the key classes, with its size. */
    private static Map<String, Integer> staticSizes() throws ReflectiveOperationException {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Class<?> type : List.of(BedrockItemType.class, BedrockBlockType.class, BedrockItemVariant.class, BedrockBlockState.class,
            VanillaTypes.class, BedrockState.class, BoolState.class, IntState.class, StringState.class, EnumState.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map) {
                    sizes.put(type.getSimpleName() + "." + field.getName(), map.size());
                } else if (value instanceof Collection<?> collection) {
                    sizes.put(type.getSimpleName() + "." + field.getName(), collection.size());
                }
            }
        }
        return sizes;
    }

    @Test
    void manyDistinctCustomKeysDoNotGrowAnyStaticStructure() throws ReflectiveOperationException {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        // Touch everything once, so lazily built structures are not counted as growth
        BedrockItemType.fromIdentifier("warmup:item");
        BedrockBlockType.fromIdentifier("warmup:block");
        palette.itemVariant("warmup:item", 0, Lookup.RENAMED);
        Map<String, Integer> before = staticSizes();
        assertTrue(before.containsKey("VanillaTypes.STATES"), "the fixture reads the static maps");

        List<BedrockItemType> kept = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            BedrockItemType type = BedrockItemType.fromIdentifier("attacker:item_" + i);
            BedrockItemVariant variant = BedrockItemVariant.fromIdentifier("attacker:variant_" + i, i);
            BedrockBlockType block = BedrockBlockType.fromIdentifier("attacker:block_" + i);
            block.defaultState();
            palette.itemVariant("attacker:lookup_" + i, i, Lookup.RENAMED);
            palette.encode(BedrockItemStack.of(type.variant(i)));
            Access.palette().blocks(palette).resolve(NbtMap.builder().putString("name", "attacker:state_" + i).putCompound("states", NbtMap.EMPTY).build());
            if (i % 1000 == 0) {
                kept.add(type);
                assertEquals(variant.type(), BedrockItemType.fromIdentifier("attacker:variant_" + i));
            }
        }
        assertEquals(before, staticSizes());
        assertEquals(KEYS / 1000, kept.size());
    }

    @Test
    void unknownKeysAreCollectable() {
        WeakReference<BedrockItemType> item = new WeakReference<>(BedrockItemType.fromIdentifier("gc:item"));
        WeakReference<BedrockBlockType> block = new WeakReference<>(BedrockBlockType.fromIdentifier("gc:block"));
        WeakReference<BedrockItemVariant> variant = new WeakReference<>(BedrockItemVariant.fromIdentifier("gc:variant", 3));
        for (int i = 0; i < 50 && (item.get() != null || block.get() != null || variant.get() != null); i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals(null, item.get(), "nothing holds an unknown item type");
        assertEquals(null, block.get(), "nothing holds an unknown block type");
        assertEquals(null, variant.get(), "nothing holds an unknown variant");
    }

    @Test
    void aCustomBlockTypeDoesNotKeepItsBlockAlive() {
        List<WeakReference<CustomBlock>> blocks = new ArrayList<>();
        BedrockBlockType type = typeOfTemporaryBlock(blocks);
        WeakReference<CustomBlock> block = blocks.getFirst();
        for (int i = 0; i < 100 && block.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals(null, block.get(), "the type references its block");
        assertEquals(2, type.defaultState().type().states().size());
    }

    private static BedrockBlockType typeOfTemporaryBlock(List<WeakReference<CustomBlock>> reference) {
        CustomBlock block = CustomBlock.builder("gc:custom").property(BlockProperty.bool("gc:lit")).build();
        reference.add(new WeakReference<>(block));
        BedrockBlockType type = block.type();
        type.states();
        return type;
    }

    @Test
    void customBlockTypesAreEqualByIdentifierAndStates() {
        CustomBlock lit = CustomBlock.builder("eq:custom").property(BlockProperty.bool("eq:lit")).build();
        CustomBlock same = CustomBlock.builder("eq:custom").property(BlockProperty.bool("eq:lit")).build();
        CustomBlock other = CustomBlock.builder("eq:custom").property(BlockProperty.bool("eq:open")).build();
        // Each block owns its type: equal blocks have equal types, not the same one
        assertNotSame(lit.type(), same.type());
        assertEquals(lit.type(), same.type());
        assertEquals(lit.type().hashCode(), same.type().hashCode());
        assertEquals(lit.type().defaultState(), same.type().defaultState());
        assertTrue(!lit.type().equals(other.type()));
        assertTrue(!lit.type().equals(BedrockBlockType.fromIdentifier("eq:custom")));
        assertEquals(BedrockBlockType.fromIdentifier("eq:custom"), CustomBlock.builder("eq:custom").build().type());
    }

    @Test
    void unknownKeysAreEqualByIdentifier() {
        BedrockItemType item = BedrockItemType.fromIdentifier("eq:item");
        assertNotSame(item, BedrockItemType.fromIdentifier("eq:item"));
        assertEquals(item, BedrockItemType.fromIdentifier("eq:item"));
        assertEquals(item.hashCode(), BedrockItemType.fromIdentifier("eq:item").hashCode());
        assertEquals(BedrockItemVariant.fromIdentifier("eq:item", 4), BedrockItemVariant.fromIdentifier("eq:item", 4));
        assertEquals(BedrockItemVariant.fromIdentifier("eq:item", 4).hashCode(), BedrockItemVariant.fromIdentifier("eq:item", 4).hashCode());
        assertTrue(!BedrockItemVariant.fromIdentifier("eq:item", 4).equals(BedrockItemVariant.fromIdentifier("eq:item", 5)));
        assertTrue(!item.equals(BedrockItemType.fromIdentifier("eq:other")));

        BedrockBlockType block = BedrockBlockType.fromIdentifier("eq:block");
        assertEquals(block, BedrockBlockType.fromIdentifier("eq:block"));
        assertEquals(block.hashCode(), BedrockBlockType.fromIdentifier("eq:block").hashCode());
        assertEquals(block.defaultState(), BedrockBlockType.fromIdentifier("eq:block").defaultState());
        assertEquals(block.defaultState().hashCode(), BedrockBlockType.fromIdentifier("eq:block").defaultState().hashCode());
        assertTrue(!block.equals(BedrockBlockType.fromIdentifier("eq:other")));
    }

    @Test
    void vanillaKeysStayIdentitySingletons() {
        assertSame(BedrockItems.APPLE, BedrockItemType.fromIdentifier("minecraft:apple"));
        assertSame(BedrockItems.APPLE.variant(), BedrockItemVariant.fromIdentifier("minecraft:apple", 0));
        assertSame(BedrockBlocks.STONE, BedrockBlockType.fromIdentifier("minecraft:stone"));
        assertSame(BedrockBlocks.STONE.defaultState(), BedrockBlockType.fromIdentifier("minecraft:stone").defaultState());
    }
}
