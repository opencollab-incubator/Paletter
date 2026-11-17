package org.geysermc.paletter;

import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockHashes;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer that cannot be compiled throws a PaletteException naming it and changes nothing, session or global; lazy compilation reports each palette once;
 * {@code applyTo(StartGamePacket)} is idempotent; the built Paletter does not share its vanilla content with the builder.
 */
class ResilienceTest {
    private static final Paletter PALETTER = Palettes.paletter();
    private static final DataVersion LATEST = PALETTER.latest();

    private static CustomBlock block(String identifier) {
        return CustomBlock.builder(identifier).property(BlockProperty.bool("example:on")).build();
    }

    /**
     * The extension throws a PaletteException naming the layer, the version, the cause and, if given, the identifier;
     * the base and everything shared with it are untouched, and the same layer fails again (nothing is cached).
     */
    private static void assertRejected(Palette base, PaletteLayer layer, String cause, String identifier) {
        BlockRegistry blocks = base.blocks();
        ItemRegistry items = base.items();
        List<PaletteLayer> layers = base.layers();
        int entries = base.report().entries().size();
        PaletteException error = assertThrows(PaletteException.class, () -> base.extend(layer));
        assertEquals(layer.id(), error.layer());
        // Several identifiers separated by | : any of them (a hash collision names the state that comes second)
        assertEquals(identifier == null, error.identifier() == null);
        assertTrue(identifier == null || List.of(identifier.split("[|]")).contains(error.identifier()), error.identifier());
        assertTrue(error.getMessage().contains(layer.id()), error.getMessage());
        assertTrue(error.getMessage().contains(base.version().toString()), error.getMessage());
        assertTrue(error.getMessage().contains(cause), error.getMessage());
        assertSame(blocks, base.blocks());
        assertSame(items, base.items());
        assertEquals(layers, base.layers());
        assertEquals(entries, base.report().entries().size());
        assertThrows(PaletteException.class, () -> base.extend(layer), "a failure is not cached");
        // Still usable: a later valid extension works
        Palette good = base.extend(PaletteLayer.builder("later").block(block("example:later")).build());
        assertNotNull(good.blocks().block("example:later"));
        assertEquals(layers.size() + 1, good.layers().size());
        assertNull(base.blocks().block("example:later"));
    }

    @Test
    void aDuplicateCustomBlockThrowsWithLayerAndIdentifier() {
        Palette base = PALETTER.palette(LATEST.protocolVersion()).extend(PaletteLayer.builder("first").block(block("example:dup")).build());
        assertRejected(base, PaletteLayer.builder("second").block(block("example:dup")).build(), "example:dup", "example:dup");
        // The same identifier twice in one layer, and a vanilla identifier
        assertRejected(base, PaletteLayer.builder("same").block(block("example:x")).block(block("example:x")).build(), "example:x", "example:x");
        Palette vanilla = PALETTER.palette(LATEST.protocolVersion());
        assertRejected(vanilla, PaletteLayer.builder("v").block(block("minecraft:stone")).build(), "minecraft:stone", "minecraft:stone");
    }

    @Test
    void aDuplicateVoxelShapeAcrossLayersThrows() {
        VoxelShape shape = VoxelShape.ofBoxes("example:shape", 0, 0, 0, 1, 0.5f, 1);
        Palette base = PALETTER.palette(LATEST.protocolVersion()).extend(PaletteLayer.builder("first").voxelShape("example:shape", shape).build());
        assertRejected(base, PaletteLayer.builder("second").voxelShape("example:shape", shape).build(), "example:shape", "example:shape");
    }

    @Test
    void aHashCollisionThrows() {
        Map<Integer, String> seen = new HashMap<>();
        String first = null;
        String second = null;
        for (int i = 0; first == null; i++) {
            String name = "test:block_" + i;
            String previous = seen.put(BlockHashes.hash(BlockStates.of(name)), name);
            if (previous != null) {
                first = previous;
                second = name;
            }
        }
        Palette base = PALETTER.palette(LATEST.protocolVersion());
        PaletteLayer layer = PaletteLayer.builder("colliding").block(CustomBlock.builder(first).build()).block(CustomBlock.builder(second).build()).build();
        assertRejected(base, layer, "collision", first + "|" + second);
    }

    @Test
    void aDuplicateItemThrows() {
        Palette base = PALETTER.palette(LATEST.protocolVersion());
        CustomItem stick = CustomItem.builder("minecraft:stick").component(MaxStackSize.of(4)).build();
        assertRejected(base, PaletteLayer.builder("items").item(stick).build(), "minecraft:stick", "minecraft:stick");
    }

    @Test
    void anExceptionFromACallbackThrowsWithTheLayer() {
        Palette base = PALETTER.palette(LATEST.protocolVersion());
        PaletteLayer modifier = PaletteLayer.builder("modifier")
            .overrideItem("minecraft:stick", override -> {
                throw new IllegalStateException("modifier broke");
            }).build();
        assertRejected(base, modifier, "modifier broke", null);
        PaletteLayer creative = PaletteLayer.builder("creative")
            .creative(editor -> {
                throw new UnsupportedOperationException("edit broke");
            }).build();
        assertRejected(base, creative, "edit broke", null);
        // A message-less exception still names its type
        PaletteLayer silent = PaletteLayer.builder("silent").creative(editor -> {
            throw new NullPointerException();
        }).build();
        assertRejected(base, silent, "NullPointerException", null);
    }

    @Test
    void aSuccessfulExtensionIsCachedPerLayer() {
        Palette base = PALETTER.palette(LATEST.protocolVersion());
        AtomicInteger calls = new AtomicInteger();
        PaletteLayer counted = PaletteLayer.builder("counted").creative(editor -> calls.incrementAndGet()).build();
        assertSame(base.extend(counted), base.extend(counted));
        assertEquals(1, calls.get());
    }

    @Test
    void aFailedGlobalLayerCachesNothingInThePaletter() {
        PaletteLayer duplicate = PaletteLayer.builder("global").block(block("minecraft:stone")).build();
        Paletter paletter = Paletter.builder().layer(duplicate).build();
        assertThrows(PaletteException.class, () -> paletter.palette(LATEST.protocolVersion()));
        assertThrows(PaletteException.class, () -> paletter.palette(LATEST.protocolVersion()));
        assertThrows(PaletteException.class, paletter::warmUp);
        // The vanilla palette is not affected
        assertNotNull(paletter.vanilla(LATEST));
    }

    @Test
    void aGlobalLayerFailsLoudlyWithContext() {
        PaletteLayer duplicate = PaletteLayer.builder("global").block(block("minecraft:stone")).build();
        PaletteException loud = assertThrows(PaletteException.class, () -> Paletter.builder().layer(duplicate).build().palette(LATEST.protocolVersion()));
        assertTrue(loud.getMessage().contains("global") && loud.getMessage().contains(LATEST.toString()), loud.getMessage());
        assertEquals("global", loud.layer());
        assertEquals("minecraft:stone", loud.identifier());

        IllegalStateException cause = new IllegalStateException("boom");
        PaletteLayer throwing = PaletteLayer.builder("startup").creative(editor -> {
            throw cause;
        }).build();
        PaletteException wrapped = assertThrows(PaletteException.class, () -> Paletter.builder().layer(throwing).build().palette(LATEST.protocolVersion()));
        assertSame(cause, wrapped.getCause());
        assertTrue(wrapped.getMessage().contains("startup") && wrapped.getMessage().contains(LATEST.toString())
            && wrapped.getMessage().contains("boom"), wrapped.getMessage());
    }

    @Test
    void aReportsConsumerMayUseThePaletterAndEachPaletteIsReportedOnce() {
        List<PaletteReport> reports = Collections.synchronizedList(new ArrayList<>());
        Paletter[] self = new Paletter[1];
        Paletter paletter = Paletter.builder().reports(report -> {
            reports.add(report);
            // Re-entrancy: asking for another palette from inside the consumer
            self[0].palette(self[0].versions().getFirst().protocolVersion());
        }).layer(PaletteLayer.builder("global").build()).build();
        self[0] = paletter;
        paletter.palette(LATEST.protocolVersion());
        assertEquals(2, reports.size(), "the latest, then the oldest it asked for");
        paletter.palette(LATEST.protocolVersion());
        paletter.palette(paletter.versions().getFirst().protocolVersion());
        assertEquals(2, reports.size());
    }

    @Test
    void aProtocolNewerThanTheDataIsReportedAsInfo() {
        List<PaletteReport> reports = new ArrayList<>();
        Paletter paletter = Paletter.builder().reports(reports::add).build();
        int newer = LATEST.protocolVersion() + 1;
        Palette palette = paletter.palette(newer);
        assertEquals(2, reports.size(), "the compiled palette, then the note");
        PaletteReport note = reports.get(1);
        assertEquals(1, note.entries().size());
        assertEquals(PaletteReport.Level.INFO, note.entries().getFirst().level());
        assertTrue(note.entries().getFirst().message().contains("newer than the newest bundled data"));
        assertTrue(palette.report().warnings().isEmpty());
        assertEquals(note.entries().getFirst(), palette.report().entries().getLast());
        // Once per palette, however often it is asked for
        assertSame(palette, paletter.palette(newer));
        paletter.palette(LATEST.protocolVersion());
        assertEquals(2, reports.size());
    }

    @Test
    void warmUpCompilesEveryVersionOnce() {
        AtomicInteger reports = new AtomicInteger();
        Paletter paletter = Paletter.builder().reports(report -> reports.incrementAndGet()).build();
        assertSame(paletter, paletter.warmUp());
        assertEquals(paletter.versions().size(), reports.get());
        for (DataVersion version : paletter.versions()) {
            assertNotNull(paletter.palette(version.protocolVersion()));
        }
        paletter.warmUp();
        assertEquals(paletter.versions().size(), reports.get());
    }

    @Test
    void applyToAStartGamePacketIsIdempotent() {
        PaletteLayer layer = PaletteLayer.builder("blocks").block(block("example:lamp")).build();
        Palette palette = Palettes.palette(LATEST.protocolVersion(), layer);
        StartGamePacket packet = new StartGamePacket();
        palette.applyTo(packet);
        assertTrue(packet.isBlockNetworkIdsHashed());
        List<BlockPropertyData> once = List.copyOf(packet.getBlockProperties());
        assertTrue(once.stream().anyMatch(data -> data.getName().equals("example:lamp")));
        palette.applyTo(packet);
        palette.applyTo(packet);
        assertEquals(once, packet.getBlockProperties());
        assertEquals(palette.blocks().blockProperties().size(), packet.getBlockProperties().size());
        assertEquals(packet.getExperiments().stream().distinct().count(), packet.getExperiments().size());
    }

    @Test
    void theBuiltPaletterDoesNotShareItsVanillaContentWithTheBuilder() {
        VanillaContent[] leaked = new VanillaContent[1];
        Paletter.Builder builder = Paletter.builder().vanilla(content -> leaked[0] = content);
        Paletter paletter = builder.build();
        int protocol = LATEST.protocolVersion();
        assertFalse(Palettes.palette(protocol).experiments().isEmpty(), "the latest version has content behind a toggle");
        assertEquals(List.of(), paletter.palette(protocol).experiments());

        // Changing the builder's content (through the leaked reference, or the builder) after build() changes nothing
        leaked[0].experiments(Palettes.EXPERIMENTS);
        builder.vanilla(content -> content.experiments(Palettes.EXPERIMENTS));
        assertEquals(List.of(), paletter.palette(protocol).experiments());
        assertEquals(List.of(), paletter.vanilla(LATEST).experiments());
        // A second build() takes the changes
        assertFalse(builder.build().palette(protocol).experiments().isEmpty());
    }
}
