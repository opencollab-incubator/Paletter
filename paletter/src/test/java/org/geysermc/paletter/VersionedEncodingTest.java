package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockComponents;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Box;
import org.geysermc.paletter.block.component.Face;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.Material;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.RenderMethod;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.encode.BlockEncoder;
import org.geysermc.paletter.internal.encode.EncodeContext;
import org.geysermc.paletter.internal.encode.ItemEncoder;
import org.geysermc.paletter.internal.wire.BedrockComponent;
import org.geysermc.paletter.internal.wire.ComponentType;
import org.geysermc.paletter.internal.wire.Confidence;
import org.geysermc.paletter.internal.wire.block.BlockComponentTypes;
import org.geysermc.paletter.internal.wire.item.Damage;
import org.geysermc.paletter.internal.wire.item.ItemComponentTypes;
import org.geysermc.paletter.internal.wire.item.ItemProperties;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemComponents;
import org.geysermc.paletter.item.PaletteItem;
import org.geysermc.paletter.item.component.BlockDescriptor;
import org.geysermc.paletter.item.component.Color;
import org.geysermc.paletter.item.component.Cooldown;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.Enchantable;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.EquipmentSlot;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.KineticWeapon;
import org.geysermc.paletter.item.component.PiercingWeapon;
import org.geysermc.paletter.item.component.Rarity;
import org.geysermc.paletter.item.component.Reach;
import org.geysermc.paletter.item.component.RenderOffsets;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.Shooter;
import org.geysermc.paletter.item.component.Throwing;
import org.geysermc.paletter.item.component.UseAnimation;
import org.geysermc.paletter.item.component.UseModifiers;
import org.geysermc.paletter.item.component.Wearable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encoding is per version: every semantic value goes through the generated shape whose protocol window covers the
 * palette, and what a version cannot express is reported and left out, never a crash.
 */
class VersionedEncodingTest {
    private static final Paletter PALETTER = Paletter.bundled();

    /**
     * Every semantic item value at once.
     */
    static ItemComponents kitchenSinkItem() {
        return ItemComponents.builder()
            .icon(Map.of("default", "sink", "dyed", "sink_dyed"))
            .displayName("item.sink.name")
            .maxStackSize(1)
            .allowOffHand(true).handEquipped(true).glint(true).canDestroyInCreative(false).stackedByData(true)
            .liquidClipped(false).shouldDespawn(true).fireResistant(true)
            .durability(Durability.of(250).damageChance(50, 100))
            .attackDamage(7)
            .enchantable(Enchantable.of(Enchantable.ALL, 10))
            .dyeable(Color.WHITE)
            .kineticWeapon(KineticWeapon.builder().delayTicks(12).damageConditions(KineticWeapon.Conditions.of(-1, 0, 4.6f)).build())
            .piercingWeapon(PiercingWeapon.of(Reach.of(0, 3)).creativeReach(Reach.of(0, 5)).hitboxMargin(0.3f))
            .swingDuration(0.3f)
            .food(Food.of(4, 0.6f).canAlwaysEat(true).usingConvertsTo("minecraft:bowl"))
            .useAnimation(UseAnimation.EAT)
            .useModifiers(UseModifiers.of(1.6f).movementModifier(0.35f))
            .cooldown(Cooldown.of("sink", 2))
            .blockPlacer("minecraft:stone", false)
            .shooter(Shooter.of(Shooter.Ammunition.of("minecraft:arrow")).maxDrawDuration(1))
            .throwable(Throwing.of(true).launchPowerScale(1).maxLaunchPower(1))
            .entityPlacer(EntityPlacer.of("minecraft:pig").useOn(BlockDescriptor.block("minecraft:grass_block")))
            .compostable(30)
            .fuel(5)
            .repairable(Repairable.of(Repairable.Entry.items("minecraft:iron_ingot").amount(25)))
            .digger(Digger.of().speed(BlockDescriptor.ANY, 0).speed(BlockDescriptor.tags("q.any_tag('stone')"), 4))
            .rarity(Rarity.RARE)
            .wearable(Wearable.of(EquipmentSlot.HEAD, 2))
            .renderOffsets(new RenderOffsets(RenderOffsets.Hand.IDENTITY, RenderOffsets.Hand.IDENTITY))
            .tags("minecraft:is_tool")
            .build();
    }

    static BlockComponents kitchenSinkBlock() {
        return BlockComponents.builder()
            .displayName("tile.sink.name")
            .selectionBox(Box.FULL)
            .collisionBoxes(new Box(-8, 0, -8, 16, 8, 16))
            .geometry(Geometry.of(Geometry.FULL_BLOCK).uvLock(true).cullingLayer("minecraft:culling_layer.undefined"))
            .materials(Map.of(Material.ALL_FACES, Material.of("sink").renderMethod(RenderMethod.BLEND).ambientOcclusion(0.5f)),
                Map.of("up", "*"))
            .placementFilter(PlacementFilter.allowOn(Face.UP, Face.DOWN).blocks("minecraft:dirt"), PlacementFilter.allowOnAnyFace().tags("1"))
            .destructibleByMining(2)
            .friction(0.4f)
            .lightEmission(4)
            .lightDampening(15)
            .transformation(Transformation.builder().rotation(90, 0, 0).scale(0.5f, 0.5f, 0.5f).scalePivot(0, 0.5f, 0).build())
            .onPlayerPlacing("geyser:place_event")
            .tags("minecraft:stone")
            .build();
    }

    @Test
    void everySemanticValueEncodesForEveryBundledVersionWithoutWarnings() {
        for (DataVersion version : PALETTER.versions()) {
            PaletteReport report = new PaletteReport();
            EncodeContext context = EncodeContext.of(version, report, "test");
            NbtMap item = ItemEncoder.componentData(kitchenSinkItem(), "test:sink", 1000,
                CreativePlacement.group(CreativeCategory.EQUIPMENT, "itemGroup.name.sword"), context).getCompound("components");
            NbtMap block = BlockEncoder.propertyData(CustomBlock.builder("test:sink")
                .property(BlockProperty.ints("test:level", 0, 1))
                .components(kitchenSinkBlock())
                .permutation("q.block_state('test:level') == 1", BlockComponents.builder().lightEmission(15).build())
                .build(), 10000, context);
            assertTrue(report.warnings().isEmpty(), version + ": " + report.warnings());

            // Everything written is a component with a generated shape for this version, and reads back unchanged
            item.forEach((name, value) -> {
                if (name.equals("item_tags")) {
                    return;
                }
                ComponentType<?> type = ItemComponentTypes.byName(name, version.protocolVersion());
                assertNotNull(type, version + " " + name);
                assertEquals(value, reencode(type.read((NbtMap) value)), version + " " + name);
            });
            block.getCompound("components").forEach((name, value) -> {
                ComponentType<?> type = BlockComponentTypes.byName(name, version.protocolVersion());
                assertNotNull(type, version + " " + name);
            });
            assertTrue(item.size() > 30, item.keySet().toString());
        }
    }

    private static NbtMap reencode(Object wire) {
        return wire instanceof BedrockComponent component ? component.toNbt() : ((ItemProperties) wire).toNbt();
    }

    @Test
    void aShapeOutsideTheTargetWindowIsReportedAndLeftOut() {
        PaletteReport report = new PaletteReport();
        EncodeContext context = EncodeContext.of(new DataVersion("26.60", 2225), report, "layer");
        NbtMapBuilder out = NbtMap.builder();

        // A component whose shape for this version is a newer one than the encoder produces
        ComponentType<Damage> oldShape = new ComponentType<>("minecraft:damage", ComponentType.Kind.ITEM, Confidence.HIGH, null, false,
            ComponentType.UNBOUNDED_SINCE, 2200, Damage::fromNbt);
        assertFalse(context.put(out, "test:item", oldShape, () -> Damage.of((short) 1)));
        // A component the version does not have at all
        ComponentType<Damage> future = new ComponentType<>("minecraft:future", ComponentType.Kind.ITEM, Confidence.HIGH, null, false,
            9999, ComponentType.UNBOUNDED_UNTIL, Damage::fromNbt);
        assertFalse(context.put(out, "test:item", future, () -> Damage.of((short) 1)));

        assertTrue(out.build().isEmpty());
        List<PaletteReport.Entry> warnings = report.warnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals("layer", warnings.get(0).source());
        assertTrue(warnings.get(0).message().contains("minecraft:damage has a network shape Paletter does not encode yet"), warnings.toString());
        assertTrue(warnings.get(1).message().contains("minecraft:future is not supported in 26.60 (2225)"), warnings.toString());
    }

    @Test
    void generatedShapesCarryProtocolWindows() {
        for (ComponentType<?> type : ItemComponentTypes.ALL) {
            assertTrue(type.sinceProtocol() < type.untilProtocol(), type.toString());
            // At most one shape per name and protocol
            for (DataVersion version : PALETTER.versions()) {
                long matching = ItemComponentTypes.windows(type.name()).stream().filter(t -> t.supports(version.protocolVersion())).count();
                assertTrue(matching <= 1, type + " " + version);
            }
        }
    }

    @Test
    void whatAVersionCannotExpressNeverFailsCompilation() {
        PaletteLayer layer = PaletteLayer.builder("tags")
            .block(CustomBlock.builder("test:lamp")
                .property(BlockProperty.bool("test:lit"))
                .permutation("q.block_state('test:lit') == 1", BlockComponents.builder().tags("test:lit").build())
                .build())
            .item(CustomItem.builder("test:sink").components(kitchenSinkItem()).build())
            .build();
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion(), layer);
            assertEquals(1, palette.report().warnings().size(), palette.report().warnings().toString());
            assertTrue(palette.report().warnings().getFirst().message().contains("test:lit"));
            PaletteItem sink = palette.items().getDefinition("test:sink");
            assertNotNull(sink);
            assertTrue(sink.getComponentData().getCompound("components").containsKey("minecraft:kinetic_weapon"));
            BlockPropertyData lamp = palette.blocks().blockProperties().stream().filter(p -> p.getName().equals("test:lamp")).findFirst().orElseThrow();
            assertFalse(lamp.getProperties().containsKey("blockTags"));
        }
    }
}
