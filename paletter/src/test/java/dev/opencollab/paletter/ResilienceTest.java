package dev.opencollab.paletter;

import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItems;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.VoxelShape;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.ItemRegistry;
import dev.opencollab.paletter.item.component.MaxStackSize;
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
     * the base and everything shared with it are untouched, and the same layer fails again (no palette or failure is
     * cached). The canonical tables the failed compile may already have filled for the layer are not checked: they are
     * weak and deterministic per layer, so a later extension of the same layer is unaffected.
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
    void aHashCollisionThatWouldChangeAnEarlierLayersIdThrows() {
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
        // BDS gives the hash to the block it numbers first: added by a later layer, that block would take the earlier one's id
        List<String> bdsOrder = java.util.stream.Stream.of(first, second).sorted(dev.opencollab.paletter.internal.BlockIdOrder.DESCENDING).toList();
        Palette base = PALETTER.palette(LATEST.protocolVersion()).extend(PaletteLayer.builder("earlier").block(CustomBlock.builder(bdsOrder.get(1)).build()).build());
        PaletteLayer layer = PaletteLayer.builder("colliding").block(CustomBlock.builder(bdsOrder.get(0)).build()).build();
        assertRejected(base, layer, "collision", bdsOrder.get(0));
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
    void aFailedGlobalLayerFailsTheBuildEveryTime() {
        PaletteLayer duplicate = PaletteLayer.builder("global").block(block("minecraft:stone")).build();
        Paletter.Builder builder = Paletter.builder().versions(LATEST::equals).layer(duplicate);
        assertThrows(PaletteException.class, builder::build);
        assertThrows(PaletteException.class, builder::build);
    }

    @Test
    void aGlobalLayerFailsLoudlyWithContext() {
        PaletteLayer duplicate = PaletteLayer.builder("global").block(block("minecraft:stone")).build();
        PaletteException loud = assertThrows(PaletteException.class, () -> Paletter.builder().versions(LATEST::equals).layer(duplicate).build());
        assertTrue(loud.getMessage().contains("global") && loud.getMessage().contains(LATEST.toString()), loud.getMessage());
        assertEquals("global", loud.layer());
        assertEquals(LATEST, loud.version());
        assertEquals("minecraft:stone", loud.identifier());

        IllegalStateException cause = new IllegalStateException("boom");
        PaletteLayer throwing = PaletteLayer.builder("startup").creative(editor -> {
            throw cause;
        }).build();
        PaletteException wrapped = assertThrows(PaletteException.class, () -> Paletter.builder().versions(LATEST::equals).layer(throwing).build());
        assertSame(cause, wrapped.getCause());
        assertTrue(wrapped.getMessage().contains("startup") && wrapped.getMessage().contains(LATEST.toString())
            && wrapped.getMessage().contains("boom"), wrapped.getMessage());
    }

    @Test
    void eachPaletteIsReportedOnceOldestFirst() {
        List<PaletteReport> reports = Collections.synchronizedList(new ArrayList<>());
        Paletter paletter = Paletter.builder().reports(reports::add).layer(PaletteLayer.builder("global").build()).build();
        assertEquals(paletter.versions(), reports.stream().map(PaletteReport::version).toList());
        paletter.palette(LATEST.protocolVersion());
        paletter.palette(paletter.versions().getFirst().protocolVersion());
        assertEquals(paletter.versions().size(), reports.size());
    }

    @Test
    void aProtocolNewerThanTheDataIsReportedAsInfo() {
        List<PaletteReport> reports = new ArrayList<>();
        Paletter paletter = Paletter.builder().versions(LATEST::equals).reports(reports::add).build();
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
    void buildCompilesEveryVersionOnceWithEverythingLoaded() {
        AtomicInteger reports = new AtomicInteger();
        Paletter paletter = Paletter.builder().reports(report -> reports.incrementAndGet()).build();
        assertEquals(paletter.versions().size(), reports.get());
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            // Nothing is left to build on first use, behaviour values included
            assertTrue(palette.loadedEagerly(true), version.toString());
        }
        assertEquals(paletter.versions().size(), reports.get());
    }

    @Test
    void withoutBehaviourNothingIsLoadedAndAskingThrows() {
        Paletter paletter = Paletter.builder().versions(LATEST::equals).behaviour(false).build();
        Palette palette = paletter.palette(LATEST.protocolVersion());
        assertTrue(palette.loadedEagerly(false));
        ResolvedBlock stone = palette.resolve(BedrockBlocks.STONE.defaultState());
        assertNotNull(stone.definition());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> stone.behaviour().get(BlockBehaviour.FRICTION));
        assertTrue(e.getMessage().contains("behaviour(false)"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> palette.resolve(BedrockItems.STICK).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0));
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
    void theBuiltPaletterDoesNotShareItsExperimentsWithTheBuilder() {
        Paletter.Builder builder = Paletter.builder();
        Paletter paletter = builder.build();
        int protocol = LATEST.protocolVersion();
        assertFalse(Palettes.palette(protocol).experiments().isEmpty(), "the latest version has content behind a toggle");
        assertEquals(List.of(), paletter.palette(protocol).experiments());

        // Changing the builder after build() changes nothing
        builder.experiments(Palettes.EXPERIMENTS);
        assertEquals(List.of(), paletter.palette(protocol).experiments());
        assertEquals(List.of(), paletter.vanilla(LATEST).experiments());
        // A second build() takes the changes
        assertFalse(builder.build().palette(protocol).experiments().isEmpty());
    }
}
