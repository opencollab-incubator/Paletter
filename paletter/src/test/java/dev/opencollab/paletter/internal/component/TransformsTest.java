package dev.opencollab.paletter.internal.component;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.Molang;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.CollisionBox;
import dev.opencollab.paletter.block.component.DestructibleByMining;
import dev.opencollab.paletter.block.component.EmbeddedVisual;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.ItemVisual;
import dev.opencollab.paletter.block.component.LightEmission;
import dev.opencollab.paletter.block.component.MaterialInstances;
import dev.opencollab.paletter.block.component.PlacementFilter;
import dev.opencollab.paletter.block.component.SelectionBox;
import dev.opencollab.paletter.block.component.UvLock;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.item.component.BlockPlacer;
import dev.opencollab.paletter.item.component.Dyeable;
import dev.opencollab.paletter.item.component.ItemsOrTags;
import dev.opencollab.paletter.item.component.HoverTextColor;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.RepairAmount;
import dev.opencollab.paletter.item.component.Repairable;
import dev.opencollab.paletter.item.component.Shooter;
import dev.opencollab.paletter.item.component.UseAnimation;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every hand transform, on its own: what is particular to it, and what no captured variant shows (protocol windows,
 * report warnings, clamping, tables no accepted variant sends). The full wire forms of the variants BDS accepted are the
 * conformance suite's in the tools, which compares them with the captures; {@code HandTransformsTest} there checks that
 * every hand transform has such variants.
 */
class TransformsTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final VanillaData DATA = PALETTER.vanillaData(PALETTER.latest());

    private final PaletteReportBuilder report = new PaletteReportBuilder();
    private final WireContext ctx = WireContext.of(DATA, report, "test").subject("test:thing");
    /** The data version of the capture of BDS 1.26.52.3 (26.50), before the shapes that changed in 1.26.63.1. */
    private final WireContext ctx26_50 = at(ComponentIndex.V26_50);
    /** The data version of the capture of BDS 1.26.36.1 (26.30). */
    private final WireContext ctx26_30 = at(ComponentIndex.V26_30);

    private WireContext at(int protocol) {
        return WireContext.of(PALETTER.vanillaData(PALETTER.version(protocol)), report, "test").subject("test:thing");
    }

    private static NbtMap compound(WireNode node, String path) {
        return node.build().getCompound(path);
    }

    @Test
    void collisionBoxes() {
        WireNode out = new WireNode();
        CollisionBox box = CollisionBox.builder().boxes(CollisionBox.Entry.builder().origin(1, 2, 3).size(1, 2, 3).build()).build();
        Transforms.collisionBoxes(ctx, out, box);
        NbtMap wire = compound(out, "minecraft:collision_box");
        assertEquals((byte) 1, wire.getByte("enabled"));
        NbtMap b = wire.getList("boxes", NbtType.COMPOUND).getFirst();
        // x mirrored: 8 - origin - size .. 8 - origin; y as is; z shifted by 8
        assertEquals(6f, b.getFloat("minX"));
        assertEquals(7f, b.getFloat("maxX"));
        assertEquals(2f, b.getFloat("minY"));
        assertEquals(4f, b.getFloat("maxY"));
        assertEquals(11f, b.getFloat("minZ"));
        assertEquals(14f, b.getFloat("maxZ"));
        // The boolean form is the generated rules' business
        WireNode untouched = new WireNode();
        Transforms.collisionBoxes(ctx, untouched, CollisionBox.builder().enabled(false).build());
        assertTrue(untouched.isEmpty());
    }

    @Test
    void collisionBoxesBefore26_60AreNotMirrored() {
        WireNode out = new WireNode();
        CollisionBox box = CollisionBox.builder().boxes(CollisionBox.Entry.builder().origin(1, 2, 3).size(1, 2, 3).build()).build();
        Transforms.collisionBoxes(ctx26_50, out, box);
        NbtMap wire = compound(out, "minecraft:collision_box");
        assertEquals((byte) 1, wire.getByte("enabled"));
        NbtMap b = wire.getList("boxes", NbtType.COMPOUND).getFirst();
        // x like z: 8 + origin .. + size; y as is
        assertEquals(9f, b.getFloat("minX"));
        assertEquals(10f, b.getFloat("maxX"));
        assertEquals(2f, b.getFloat("minY"));
        assertEquals(4f, b.getFloat("maxY"));
        assertEquals(11f, b.getFloat("minZ"));
        assertEquals(14f, b.getFloat("maxZ"));
        WireNode untouched = new WireNode();
        Transforms.collisionBoxes(ctx26_50, untouched, CollisionBox.builder().enabled(false).build());
        assertTrue(untouched.isEmpty());
    }

    @Test
    void molangRepairAmountBefore26_40IsACompound() {
        Repairable repairable = Repairable.builder().repairItems(Repairable.RepairItems.builder().items(ItemsOrTags.items(List.of("minecraft:stick")))
            .repairAmount(RepairAmount.expression(Molang.of("q.max_durability * 0.25"))).build()).build();
        WireNode now = new WireNode();
        Transforms.itemDescriptorList(ctx, now, repairable);
        assertEquals("q.max_durability * 0.25", now.build().getCompound("minecraft:repairable").getList("repair_items", NbtType.COMPOUND).getFirst().getString("repair_amount"));
        WireNode before = new WireNode();
        Transforms.itemDescriptorList(ctx26_30, before, repairable);
        NbtMap amount = before.build().getCompound("minecraft:repairable").getList("repair_items", NbtType.COMPOUND).getFirst().getCompound("repair_amount");
        assertEquals("q.max_durability * 0.25", amount.getString("expression"));
        assertEquals((short) 0, amount.getShort("version"));
    }

    private static Object repairAmount(WireContext at, Molang amount) {
        WireNode out = new WireNode();
        Transforms.itemDescriptorList(at, out, Repairable.builder().repairItems(Repairable.RepairItems.builder().items("minecraft:stick")
            .repairAmount(RepairAmount.expression(amount)).build()).build());
        return compound(out, "minecraft:repairable").getList("repair_items", NbtType.COMPOUND).getFirst().get("repair_amount");
    }

    /** The version of a repair amount, as BDS 1.26.36.1 to 1.26.70.24 send {"expression": "paletter_a", "version": n}. */
    @Test
    void theVersionOfARepairAmount() {
        // -1 to 13 as written in 1.26.36.1's compound; later builds send the expression alone
        for (int version : new int[] {-1, 0, 1, 7, 13}) {
            assertEquals(NbtMap.builder().putString("expression", "paletter_a").putShort("version", (short) version).build(),
                repairAmount(ctx26_30, Molang.of("paletter_a", version)));
            assertEquals("paletter_a", repairAmount(ctx, Molang.of("paletter_a", version)));
        }
        // an empty expression is version -1 whatever its version
        assertEquals((short) -1, ((NbtMap) repairAmount(ctx26_30, Molang.of("", 1))).getShort("version"));
        assertTrue(report.entries().isEmpty(), report.entries().toString());
        // past the newest Molang version (13): the fixed amount 0.0, in every build
        for (int version : new int[] {14, 20, 100}) {
            assertEquals(0.0f, repairAmount(ctx26_30, Molang.of("paletter_a", version)));
            assertEquals(0.0f, repairAmount(ctx, Molang.of("paletter_a", version)));
        }
        assertEquals(6, report.warnings().size(), report.entries().toString());
    }

    @Test
    void aMolangVersionOutsideTheTriedRangeIsRefused() {
        assertEquals(Molang.of("a", -1).version(), -1);
        assertEquals(Molang.of("a", 100).version(), 100);
        assertNull(Molang.of("a").version());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Molang.of("a", -2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Molang.of("a", 101));
    }

    /** bone_visibility {"expression": ..., "version": n} as BDS 1.26.36.1 to 1.26.70.24 send it. */
    @Test
    void theVersionOfABoneVisibility() {
        Map<String, Molang> bones = new java.util.LinkedHashMap<>();
        bones.put("arm", Molang.of("q.block_state('lit')", 7));
        bones.put("lid", Molang.of("q.block_state('lit')"));
        bones.put("base", Molang.of("1", 1));
        WireNode oldest = new WireNode();
        Transforms.geometry(ctx26_30, oldest, Geometry.builder().identifier("geometry.lamp").boneVisibility(bones).build());
        NbtMap sent = compound(oldest, "minecraft:geometry").getCompound("bone_visibility");
        // an expression keeps its version; one without and a number get the block's Molang version (13)
        assertEquals((short) 7, sent.getCompound("arm").getShort("version"));
        assertEquals((short) 13, sent.getCompound("lid").getShort("version"));
        assertEquals(NbtMap.builder().putString("expression", "1.000000").putShort("version", (short) 13).build(), sent.getCompound("base"));
        assertTrue(report.entries().isEmpty(), report.entries().toString());
        // later builds send the expression alone
        WireNode now = new WireNode();
        Transforms.geometry(ctx, now, Geometry.builder().identifier("geometry.lamp").boneVisibility(bones).build());
        assertEquals("q.block_state('lit')", compound(now, "minecraft:geometry").getCompound("bone_visibility").getString("arm"));
        // BDS logs a bad expression for -1 and past 13, and sends it as written
        WireNode bad = new WireNode();
        Transforms.geometry(ctx26_30, bad, Geometry.builder().identifier("geometry.lamp")
            .boneVisibility(Map.of("arm", Molang.of("q.block_state('lit')", 14), "lid", Molang.of("q.block_state('lit')", -1))).build());
        assertEquals((short) 14, compound(bad, "minecraft:geometry").getCompound("bone_visibility").getCompound("arm").getShort("version"));
        assertEquals((short) -1, compound(bad, "minecraft:geometry").getCompound("bone_visibility").getCompound("lid").getShort("version"));
        assertEquals(2, report.warnings().size(), report.entries().toString());
    }

    /** A map of Molang expressions without versions. */
    private static Map<String, Molang> molang(Map<String, String> texts) {
        Map<String, Molang> out = new java.util.LinkedHashMap<>();
        texts.forEach((bone, text) -> out.put(bone, Molang.of(text)));
        return out;
    }

    @Test
    void embeddedGeometryBefore26_60HasNoRotation() {
        EmbeddedVisual visual = EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.cross")
                .nWayVisualRotation(EmbeddedVisual.NWayVisualRotation.builder().x("a").build()).build())
            .materialInstances(MaterialInstances.builder().build()).build();
        WireNode now = new WireNode();
        Transforms.embeddedGeometry(ctx, now, visual);
        NbtMap rotation = compound(now, "minecraft:embedded_visual").getCompound("geometry").getCompound("n_way_visual_rotation");
        assertEquals(Map.of("x", "a", "y", "", "z", ""), rotation, "an axis that is not set is sent empty");
        WireNode before = new WireNode();
        Transforms.embeddedGeometry(ctx26_50, before, visual);
        assertFalse(compound(before, "minecraft:embedded_visual").getCompound("geometry").containsKey("n_way_visual_rotation"));
    }

    @Test
    void blockNameIsEmptyForAVanillaBlockTheVersionLacks() {
        BlockPlacer vanilla = BlockPlacer.builder().block("minecraft:stone").build();
        WireNode out = new WireNode();
        Transforms.blockName(ctx, out, vanilla);
        assertEquals("minecraft:stone", out.build().getCompound("minecraft:block_placer").getString("block"));
        assertTrue(report.entries().isEmpty());

        WireNode missing = new WireNode();
        Transforms.blockName(ctx, missing, BlockPlacer.builder().block("minecraft:no_such_block").build());
        assertEquals("", missing.build().getCompound("minecraft:block_placer").getString("block"));
        assertEquals(1, report.warnings().size());

        WireNode custom = new WireNode();
        Transforms.blockName(ctx, custom, BlockPlacer.builder().block("test:lamp").build());
        assertEquals("test:lamp", custom.build().getCompound("minecraft:block_placer").getString("block"), "a custom block may be registered later");
    }

    @Test
    void boneVisibilityIsMolangTextInEveryGeometry() {
        Map<String, Molang> bones = molang(Map.of("shade", "true", "bulb", "false", "arm", "q.block_state('lit')", "base", "1.0"));
        ItemVisual.VisualGeometry geometry = ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").boneVisibility(bones).build();
        WireNode out = new WireNode();
        Transforms.itemVisualBoneVisibility(ctx, out, geometry);
        NbtMap description = compound(out, "minecraft:item_visual").getCompound("geometryDescription");
        Map<String, String> sent = Map.of("shade", "1.000000", "bulb", "0.000000", "arm", "q.block_state('lit')", "base", "1.000000");
        assertEquals(Map.of("bone_visibility", sent), description, "only this leaf: the other geometry fields of item_visual have inferred rules");

        WireNode block = new WireNode();
        Transforms.geometry(ctx, block, Geometry.builder().identifier("geometry.lamp").boneVisibility(bones).build());
        assertEquals(sent, compound(block, "minecraft:geometry").getCompound("bone_visibility"));
        WireNode embedded = new WireNode();
        Transforms.embeddedGeometry(ctx, embedded, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("geometry.lamp").boneVisibility(bones).build())
            .materialInstances(MaterialInstances.builder().build()).build());
        assertEquals(sent, compound(embedded, "minecraft:embedded_visual").getCompound("geometry").getCompound("bone_visibility"));
        assertTrue(report.entries().isEmpty());
    }

    @Test
    void boneVisibilityIsTheSameInEveryBuildAndACompoundBefore26_40() {
        Map<String, Molang> bones = molang(Map.of("shade", "true", "bulb", "false", "arm", "q.block_state('lit')", "base", "1.0"));
        Map<String, String> sent = Map.of("shade", "1.000000", "bulb", "0.000000", "arm", "q.block_state('lit')", "base", "1.000000");
        // BDS 1.26.45.1 and 1.26.52.3 refuse the JSON boolean and take the text for it, which is what the map holds
        WireNode before = new WireNode();
        Transforms.geometry(ctx26_50, before, Geometry.builder().identifier("geometry.lamp").boneVisibility(bones).build());
        assertEquals(sent, compound(before, "minecraft:geometry").getCompound("bone_visibility"));

        // BDS 1.26.36.1 sends each expression with the Molang version of the block
        WireNode oldest = new WireNode();
        Transforms.itemVisualBoneVisibility(ctx26_30, oldest, ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").boneVisibility(bones).build());
        NbtMap visibility = compound(oldest, "minecraft:item_visual").getCompound("geometryDescription").getCompound("bone_visibility");
        WireNode envelope = new WireNode();
        BlockWire.envelope(ctx26_30, envelope);
        short version = (short) envelope.build().getInt("molangVersion");
        assertEquals(13, version);
        Map<String, Object> compounds = new java.util.HashMap<>();
        sent.forEach((bone, expression) -> compounds.put(bone, Map.of("expression", expression, "version", version)));
        assertEquals(compounds, visibility);
        assertTrue(report.entries().isEmpty());
    }

    @Test
    void aMolangNumberIsTheFloatInSixDecimals() {
        // every shape of the captures of BDS 1.26.36.1 to 1.26.70.24, as BDS sent it
        Map<String, String> captured = new java.util.LinkedHashMap<>();
        captured.put("0", "0.000000");
        captured.put("0.0", "0.000000");
        captured.put("1", "1.000000");
        captured.put("1.0", "1.000000");
        captured.put("0.5", "0.500000");
        captured.put("-1", "-1.000000");
        captured.put("0.1234567", "0.123457");
        captured.put("0.0078125", "0.007812");
        captured.put("16777217", "16777216.000000");
        captured.put("2", "2.000000");
        captured.put("-0", "-0.000000");
        captured.put("-0.5", "-0.500000");
        captured.put(".5", "0.500000");
        captured.put("1.", "1.000000");
        captured.put(" 1 ", "1.000000");
        captured.put("1e3", "1000.000000");
        captured.put("1e-3", "0.001000");
        captured.put("1.5E2", "150.000000");
        captured.forEach((text, sent) -> assertEquals(sent, Wire.molangNumber(text), text));
        for (String text : List.of("+1", "1 + 1", "1e+3", "0x10", "1f", "NaN", "Infinity", "1e39", "", "q.life_time")) {
            assertNull(Wire.molangNumber(text), text + " is not a number of a captured shape");
        }
    }

    @Test
    void anExpressionWithoutAQueryIsSentAsWrittenWithAWarning() {
        Map<String, String> bones = Map.of("arm", "+1", "lid", "!query.block_state('lit')", "base", "math.pi");
        WireNode out = new WireNode();
        Transforms.geometry(ctx, out, Geometry.builder().identifier("geometry.lamp").boneVisibility(molang(bones)).build());
        assertEquals(bones, compound(out, "minecraft:geometry").getCompound("bone_visibility"));
        // BDS sends "+1" as written too (a bad expression, says its log); what it can compute it sends as a number
        assertEquals(2, report.warnings().size(), report.entries().toString());
        assertTrue(report.warnings().stream().noneMatch(w -> w.message().contains("lid")), "an expression with a query is sent as written by BDS");
    }

    @Test
    void replaceBlockItemOnlyForTheBlockOfTheItemsIdentifier() {
        BlockPlacer own = BlockPlacer.builder().block("test:lamp").replaceBlockItem(true).build();
        WireNode replaced = new WireNode();
        Transforms.replaceBlockItem(ctx.subject("test:lamp"), replaced, own);
        assertEquals(1, replaced.build().getCompound("minecraft:block_placer").getByte("replaceBlockItem"));
        assertTrue(report.entries().isEmpty());

        WireNode other = new WireNode();
        Transforms.replaceBlockItem(ctx.subject("test:lamp_item"), other, own);
        assertEquals(0, other.build().getCompound("minecraft:block_placer").getByte("replaceBlockItem"), "BDS ignores it for another block");
        assertEquals(1, report.warnings().size());

        WireNode off = new WireNode();
        Transforms.replaceBlockItem(ctx.subject("test:lamp"), off, BlockPlacer.builder().block("test:lamp").build());
        assertEquals(0, off.build().getCompound("minecraft:block_placer").getByte("replaceBlockItem"));
        assertEquals(1, report.warnings().size(), "false is no warning");
    }

    @Test
    void geometry() {
        WireNode out = new WireNode();
        Transforms.geometry(ctx, out, Geometry.builder().identifier("geometry.lamp").boneVisibility(molang(Map.of("lid", "false", "door", "q.block_property('open') == 'true'", "base", "true"))).uvLock(true).build());
        NbtMap g = compound(out, "minecraft:geometry");
        assertEquals("geometry.lamp", g.getString("identifier"));
        assertEquals("0.000000", g.getCompound("bone_visibility").getString("lid"));
        assertEquals("1.000000", g.getCompound("bone_visibility").getString("base"));
        assertEquals("q.block_property('open') == 'true'", g.getCompound("bone_visibility").getString("door"), "Molang is kept as a string");

        WireNode embedded = new WireNode();
        Transforms.embeddedGeometry(ctx, embedded, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.cross").build())
            .materialInstances(MaterialInstances.builder().build()).build());
        NbtMap e = compound(embedded, "minecraft:embedded_visual").getCompound("geometry");
        assertEquals("minecraft:geometry.cross", e.getString("identifier"));
        assertFalse(e.containsKey("isV1Fullblock"));
    }

    @Test
    void materialInstancesPackedInlineAndAliases() {
        MaterialInstances materials = MaterialInstances.builder()
            .all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("stone").faceDimming(false).isotropic(true).build()))
            .up(MaterialInstances.InstanceOrAlias.alias("*"))
            .build();
        WireNode out = new WireNode();
        Transforms.materialInstances(ctx, out, materials);
        NbtMap m = compound(out, "minecraft:material_instances");
        NbtMap all = m.getCompound("materials").getCompound("*");
        assertEquals((byte) 6, all.getByte("packed_bools"), "bit 2 always, bit 1 isotropic, no face dimming");
        assertEquals("*", m.getCompound("mappings").getCompound("up").getString("value"));

        WireNode visual = new WireNode();
        Transforms.materialInstancesPacked(ctx, visual, ItemVisual.builder().geometry(ItemVisual.VisualGeometry.of("minecraft:geometry.full_block"))
            .materialInstances(materials).build());
        assertEquals(m, compound(visual, "minecraft:item_visual").getCompound("materialInstancesDescription"));

        WireNode inline = new WireNode();
        Transforms.materialInstancesInline(ctx, inline, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.full_block").build()).materialInstances(materials).build());
        NbtMap face = compound(inline, "minecraft:embedded_visual").getCompound("material_instances").getCompound("*");
        assertEquals((byte) 0, face.getByte("face_dimming"));
        assertEquals((byte) 1, face.getByte("isotropic"));
    }

    @Test
    void anEmbeddedAlphaMaskedTintIsSentAsZeroAndReported() {
        WireNode inline = new WireNode();
        Transforms.materialInstancesInline(ctx, inline, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.full_block").build())
            .materialInstances(MaterialInstances.builder().all(MaterialInstances.InstanceOrAlias.define(
                MaterialInstances.Instance.builder().texture("stone").alphaMaskedTint(true).build())).build()).build());
        assertEquals((byte) 0, compound(inline, "minecraft:embedded_visual").getCompound("material_instances").getCompound("*").getByte("alpha_masked_tint"));
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().getFirst().message().contains("alpha_masked_tint"));
    }

    @Test
    void aPermutationConditionNumberIsAMolangNumber() {
        assertEquals("1.000000", Transforms.condition("1"));
        assertEquals("0.007812", Transforms.condition("0.0078125"));
        assertEquals("16777216.000000", Transforms.condition("16777217"));
        assertEquals("0.500000", Transforms.condition(".5"));
        assertEquals("q.block_state('a')", Transforms.condition("q.block_state('a')"));
        assertEquals("paletter_b", Transforms.condition("paletter_b"));
    }

    @Test
    void aColorOtherThanSixHexDigitsOrThreeChannelsIsDropped() {
        for (Dyeable bad : List.of(Dyeable.builder().defaultColor("#f00").build(), Dyeable.builder().defaultColor("#ffff0000").build(),
            Dyeable.builder().defaultColor("-ff").build(), Dyeable.builder().defaultColor(List.of(3, 7)).build(),
            Dyeable.builder().defaultColor(List.of()).build())) {
            WireNode out = new WireNode();
            Transforms.rgbColor(ctx, out, bad);
            assertTrue(out.isEmpty(), bad.toString());
        }
        assertEquals(5, report.warnings().size());
    }

    @Test
    void descriptorStatesAreIntsAndAStringIsReportedAsZero() {
        // BDS 1.26.36.1 to 1.26.70.24: names the block lacks and blocks the registry lacks are sent as given
        WireNode unknown = new WireNode();
        Wire.blockDescriptor(ctx, unknown, "", "minecraft:oak_log", Map.of("paletter_state", 1), null);
        Wire.blockDescriptor(ctx, new WireNode(), "", "minecraft:paletter_unknown", Map.of("a", 1), null);
        assertEquals(1, unknown.build().getCompound("states").getInt("paletter_state"));
        assertTrue(report.entries().isEmpty(), report.entries().toString());
        // a string is 0 (pillar_axis x, y, z and w alike), a boolean 1 or 0
        WireNode lossy = new WireNode();
        Wire.blockDescriptor(ctx, lossy, "", "minecraft:lever", Map.of("lever_direction", "east", "open_bit", true), null);
        assertEquals(NbtMap.builder().putInt("lever_direction", 0).putInt("open_bit", 1).build(), lossy.build().getCompound("states"));
        assertEquals(1, report.warnings().size(), "the string's value is lost");
    }

    @Test
    void materialInstancesWithCustomNames() {
        MaterialInstances materials = MaterialInstances.builder()
            .all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("stone").build()))
            .instances(Map.of("glass", MaterialInstances.InstanceOrAlias.define(
                MaterialInstances.Instance.builder().texture("glass").renderMethod(MaterialInstances.RenderMethod.BLEND).build()),
                "pane", MaterialInstances.InstanceOrAlias.alias("glass")))
            .build();
        WireNode out = new WireNode();
        Transforms.materialInstances(ctx, out, materials);
        NbtMap m = compound(out, "minecraft:material_instances");
        assertEquals("glass", m.getCompound("materials").getCompound("glass").getString("texture"));
        assertEquals("blend", m.getCompound("materials").getCompound("glass").getString("render_method"));
        assertEquals("stone", m.getCompound("materials").getCompound("*").getString("texture"));
        assertEquals("glass", m.getCompound("mappings").getCompound("pane").getString("value"));

        WireNode inline = new WireNode();
        Transforms.materialInstancesInline(ctx, inline, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("minecraft:geometry.full_block").build()).materialInstances(materials).build());
        assertEquals("glass", compound(inline, "minecraft:embedded_visual").getCompound("material_instances").getCompound("glass").getString("texture"));
    }

    @Test
    void blockDescriptorsAreTheFullTriple() {
        WireNode placer = new WireNode();
        BlockPlacer.UseOn use = BlockPlacer.UseOn.of(BedrockBlocks.WHITE_CANDLE.defaultState());
        Wire.blockDescriptor(ctx, placer, "", use.name(), use.states(), use.tags());
        NbtMap states = placer.build().getCompound("states");
        assertEquals("minecraft:white_candle", placer.build().getString("name"));
        assertEquals("", placer.build().getString("tags"), "item descriptors are always the full triple");
        assertEquals(NbtMap.builder().putInt("candles", 0).putInt("lit", 0).build(), states);

        WireNode entity = new WireNode();
        Wire.blockDescriptor(ctx, entity, "", null, Map.of(), "q.any_tag('stone')");
        assertEquals("", entity.build().getString("name"));
        assertEquals("q.any_tag('stone')", entity.build().getString("tags"));

        WireNode digger = new WireNode();
        Wire.blockDescriptor(ctx, digger, "block", "minecraft:stone", Map.of(), null);
        assertEquals(NbtMap.EMPTY, digger.build().getCompound("block").getCompound("states"));

        WireNode filter = new WireNode();
        Transforms.blockDescriptorStates(ctx, filter, PlacementFilter.BlockFilter.builder().name("minecraft:dirt").states(Map.of("x", 1)).build());
        NbtMap entry = filter.build().getList("states", NbtType.COMPOUND).getFirst();
        assertEquals("x", entry.getString("state"));
        assertEquals(2, entry.getInt("type"));
        assertEquals(1, entry.getInt("value"));
        // BDS 1.26.36.1 to 1.26.70.24: a boolean is an int (type 2), a string type 1 with the string
        WireNode typed = new WireNode();
        Transforms.blockDescriptorStates(ctx, typed, PlacementFilter.BlockFilter.builder().name("minecraft:lever")
            .states(new java.util.LinkedHashMap<>(Map.of("open_bit", true))).build());
        NbtMap bool = typed.build().getList("states", NbtType.COMPOUND).getFirst();
        assertEquals(2, bool.getInt("type"));
        assertEquals(1, bool.get("value"));
        WireNode text = new WireNode();
        Transforms.blockDescriptorStates(ctx, text, PlacementFilter.BlockFilter.builder().name("minecraft:oak_log").states(Map.of("pillar_axis", "x")).build());
        NbtMap string = text.build().getList("states", NbtType.COMPOUND).getFirst();
        assertEquals(1, string.getInt("type"));
        assertEquals("x", string.getString("value"));
        WireNode empty = new WireNode();
        Transforms.blockDescriptorStates(ctx, empty, PlacementFilter.BlockFilter.builder().name("minecraft:dirt").build());
        assertTrue(empty.isEmpty(), "placement filters send no states when there are none");
    }

    @Test
    void itemDescriptors() {
        WireNode repair = new WireNode();
        Transforms.itemDescriptorList(ctx, repair, Repairable.builder().repairItems(
            Repairable.RepairItems.builder().items("minecraft:stick").repairAmount(1.5f).build(),
            Repairable.RepairItems.builder().items(ItemsOrTags.items(List.of())).repairAmount(RepairAmount.expression(Molang.of("q.max_durability * 0.25"))).build(),
            Repairable.RepairItems.builder().items(ItemsOrTags.tagQuery("1")).repairAmount(1f).build()).build());
        NbtList<NbtMap> entries = (NbtList<NbtMap>) compound(repair, "minecraft:repairable").getList("repair_items", NbtType.COMPOUND);
        assertEquals("minecraft:stick", entries.get(0).getList("items", NbtType.COMPOUND).getFirst().getString("name"));
        assertEquals(1.5f, entries.get(0).getFloat("repair_amount"));
        assertEquals("q.max_durability * 0.25", entries.get(1).getString("repair_amount"));
        assertTrue(entries.get(1).getList("items", NbtType.COMPOUND).isEmpty());
        List<NbtMap> tagged = entries.get(2).getList("items", NbtType.COMPOUND);
        assertEquals(1, tagged.size());
        assertEquals("1", tagged.getFirst().getString("tags"), "a tag query descriptor is {tags}");
        assertFalse(tagged.getFirst().containsKey("name"));
    }

    @Test
    void formattingCode() {
        WireNode out = new WireNode();
        Transforms.formattingCode(ctx, out, HoverTextColor.builder().value("dark_purple").build());
        assertEquals("dark_purple", compound(out, "minecraft:hover_text_color").getString("value"));
        assertEquals("§5", compound(out, "item_properties").getString("hover_text_color"));
        WireNode unknown = new WireNode();
        Transforms.formattingCode(ctx, unknown, HoverTextColor.builder().value("ultraviolet").build());
        assertFalse(unknown.build().containsKey("item_properties"));
    }

    @Test
    void permutationComponents() {
        WireNode block = new WireNode();
        Transforms.permutationComponents(ctx, block, CustomBlock.Permutation.of("q.block_state('a')", List.of(LightEmission.of(15))));
        Transforms.permutationComponents(ctx, block, CustomBlock.Permutation.of("q.block_state('b')", List.of()));
        NbtList<NbtMap> permutations = (NbtList<NbtMap>) block.build().getList("permutations", NbtType.COMPOUND);
        assertEquals(1, permutations.size(), "a permutation without components is not sent");
        assertEquals((byte) 15, permutations.getFirst().getCompound("components").getCompound("minecraft:light_emission").getByte("emission"));
        assertEquals("q.block_state('a')", permutations.getFirst().getString("condition"));
    }

    @Test
    void iconColorDestructionSelectionAndAnimation() {
        WireNode icon = new WireNode();
        Transforms.iconTextures(ctx, icon, Icon.of("ruby"));
        assertEquals("ruby", compound(icon, "item_properties").getCompound("minecraft:icon").getCompound("textures").getString("default"));

        WireNode color = new WireNode();
        Transforms.rgbColor(ctx, color, Dyeable.builder().defaultColor("#ff0080").build());
        assertEquals(List.of(255, 0, 128), compound(color, "minecraft:dyeable").getList("default_color", NbtType.INT));
        WireNode clamped = new WireNode();
        Transforms.rgbColor(ctx, clamped, Dyeable.builder().defaultColor(10, 20, 270).build());
        assertEquals(List.of(10, 20, 255), compound(clamped, "minecraft:dyeable").getList("default_color", NbtType.INT));
        WireNode white = new WireNode();
        Transforms.rgbColor(ctx, white, Dyeable.builder().build());
        assertEquals(List.of(255, 255, 255), compound(white, "minecraft:dyeable").getList("default_color", NbtType.INT));

        WireNode mining = new WireNode();
        Transforms.destructibleByMining(ctx, mining, DestructibleByMining.builder().secondsToDestroy(2.5f).build());
        assertEquals(2.5f, compound(mining, "minecraft:destructible_by_mining").getFloat("value"));
        assertFalse(compound(mining, "minecraft:destructible_by_mining").containsKey("item_specific_speeds"));
        WireNode indestructible = new WireNode();
        Transforms.destructibleByMining(ctx, indestructible, DestructibleByMining.of(false));
        assertEquals(-1f, compound(indestructible, "minecraft:destructible_by_mining").getFloat("value"));

        WireNode selection = new WireNode();
        Transforms.selectionBox(ctx, selection, SelectionBox.of(false));
        assertEquals((byte) 0, compound(selection, "minecraft:selection_box").getByte("enabled"));

        WireNode animation = new WireNode();
        Transforms.useAnimationId(ctx, animation, UseAnimation.of(UseAnimation.Value.of("spear")));
        assertEquals(6, compound(animation, "item_properties").getInt("use_animation"));
        assertEquals("spear", compound(animation, "minecraft:use_animation").getString("value"));
        WireNode none = new WireNode();
        Transforms.useAnimationId(ctx, none, UseAnimation.builder().build());
        assertEquals(0, compound(none, "item_properties").getInt("use_animation"));
    }

    @Test
    void uvLockAsAListOfBonesNamesEachBoneOnce() {
        WireNode out = new WireNode();
        Transforms.geometry(ctx, out, Geometry.builder().identifier("geometry.lamp").uvLock("lid", "base", "lid").build());
        assertEquals(List.of("base", "lid"), compound(out, "minecraft:geometry").getList("uv_lock", NbtType.STRING));

        WireNode empty = new WireNode();
        Transforms.geometry(ctx, empty, Geometry.builder().identifier("geometry.lamp").uvLock(UvLock.bones(List.of())).build());
        assertEquals(Wire.EMPTY_LIST, compound(empty, "minecraft:geometry").get("uv_lock"), "BDS sends [] without element type");
        assertTrue(report.entries().isEmpty());
    }

    @Test
    void uvLockBonesAreInBdsOrder() {
        // the JSON list, then the list BDS 1.26.63.1 sent for it
        assertEquals(List.of("c", "b", "a"), Wire.byNameHash(List.of("a", "b", "c")));
        assertEquals(List.of("alpha", "beta", "zeta", "mid"), Wire.byNameHash(List.of("zeta", "alpha", "mid", "beta")));
        assertEquals(List.of("bone_a"), Wire.byNameHash(List.of("bone_a", "bone_a")));
        assertEquals(List.of("", "up", "body", "pivot", "root", "0", "Top", "lid"),
            Wire.byNameHash(List.of("lid", "pivot", "root", "up", "body", "0", "", "Top")));
        assertEquals(List.of("ÿ", "é", "ñandú", "🦴", "bone_ä", "~", "z", "e", "骨"),
            Wire.byNameHash(List.of("é", "e", "ñandú", "骨", "🦴", "z", "ÿ", "~", "bone_ä")));
    }

    @Test
    void itemVisualUvLockIsTheOneOfGeometry() {
        // what BDS 1.26.63.1 sent for item_visual: true as 1, ["bone_a", "paletter_b"] as [paletter_b, bone_a]
        WireNode all = new WireNode();
        Transforms.itemVisualUvLock(ctx, all, ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").uvLock(true).build());
        assertEquals((byte) 1, compound(all, "minecraft:item_visual").getCompound("geometryDescription").getByte("uv_lock"));

        WireNode bones = new WireNode();
        Transforms.itemVisualUvLock(ctx, bones, ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").uvLock("bone_a", "paletter_b", "bone_a").build());
        assertEquals(List.of("paletter_b", "bone_a"),
            compound(bones, "minecraft:item_visual").getCompound("geometryDescription").getList("uv_lock", NbtType.STRING));

        WireNode unset = new WireNode();
        Transforms.itemVisualUvLock(ctx, unset, ItemVisual.VisualGeometry.of("geometry.lamp"));
        assertEquals((byte) 0, compound(unset, "minecraft:item_visual").getCompound("geometryDescription").getByte("uv_lock"));
        assertTrue(report.entries().isEmpty());
    }

    @Test
    void geometryRotationSendsEveryAxisFrom26_60() {
        Geometry geometry = Geometry.builder().identifier("geometry.lamp")
            .nWayVisualRotation(Geometry.NWayVisualRotation.builder().y("minecraft:cardinal_direction").build()).build();
        WireNode now = new WireNode();
        Transforms.geometry(ctx, now, geometry);
        NbtMap rotation = compound(now, "minecraft:geometry").getCompound("n_way_visual_rotation");
        assertEquals(Map.of("x", "", "y", "minecraft:cardinal_direction", "z", ""), rotation);

        WireNode before = new WireNode();
        Transforms.geometry(ctx26_50, before, geometry);
        assertFalse(compound(before, "minecraft:geometry").containsKey("n_way_visual_rotation"));
    }

    @Test
    void anItemSpecificSpeedWithoutAnItemKeepsTheListAndSendsNoEntry() {
        WireNode out = new WireNode();
        Transforms.destructibleByMining(ctx, out, DestructibleByMining.builder().itemSpecificSpeeds(
            DestructibleByMining.ItemSpecificSpeeds.builder().destroySpeed(1.5f).build()).build());
        NbtMap wire = compound(out, "minecraft:destructible_by_mining");
        assertTrue(wire.containsKey("item_specific_speeds"));
        assertTrue(wire.getList("item_specific_speeds", NbtType.COMPOUND).isEmpty());

        WireNode named = new WireNode();
        Transforms.destructibleByMining(ctx, named, DestructibleByMining.builder().itemSpecificSpeeds(
            DestructibleByMining.ItemSpecificSpeeds.builder().destroySpeed(1.5f).item("minecraft:stick").build(),
            DestructibleByMining.ItemSpecificSpeeds.builder().destroySpeed(2f).build()).build());
        List<NbtMap> speeds = compound(named, "minecraft:destructible_by_mining").getList("item_specific_speeds", NbtType.COMPOUND);
        assertEquals(1, speeds.size());
        assertEquals("minecraft:stick", speeds.getFirst().getCompound("item").getString("Name"));
    }

    @Test
    void anAmmunitionItemIsADescriptorThatIsEmptyWhenItNamesNoKnownItem() {
        // An entry always names an item: BDS's schema requires it (Shooter.Ammunition.item is required)
        WireNode known = new WireNode();
        Transforms.ammunitionItemDescriptor(ctx, known, Shooter.Ammunition.builder().item("minecraft:stick").build());
        assertEquals("minecraft:stick", compound(known, "item").getString("name"));
        assertTrue(report.entries().isEmpty());

        WireNode unknown = new WireNode();
        Transforms.ammunitionItemDescriptor(ctx, unknown, Shooter.Ammunition.builder().item("minecraft:no_such_item").build());
        assertTrue(compound(unknown, "item").isEmpty());
        assertTrue(unknown.build().containsKey("item"), "{} is sent, not left out");
        assertEquals(1, report.warnings().size());
    }
}
