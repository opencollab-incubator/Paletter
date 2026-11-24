package dev.opencollab.paletter.internal.data;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.creative.RawLayout;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block part of the data format: what the writer leaves out, the digest a file without block states carries and what
 * reading does with it, and the block to item relation without its identities.
 */
class PaletteDataCodecBlocksTest {
    private static VanillaData newest() throws IOException {
        DataVersion version = PaletteDataSource.bundled().versions().getLast();
        return Access.data().loader(PaletteDataSource.bundled()).load(version);
    }

    private static byte[] bytes(VanillaData data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PaletteDataCodec.write(data, out);
        return out.toByteArray();
    }

    private static VanillaData read(byte[] bytes) throws IOException {
        return PaletteDataCodec.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void theBundledPaletteIsLeftOutWithItsDigestAndComesBack() throws IOException {
        VanillaData data = newest();
        assertTrue(PaletteDataCodec.derivable(data));
        byte[] bytes = bytes(data);
        NbtMap root = PaletteDataCodec.readGzip(new ByteArrayInputStream(bytes));
        assertFalse(root.containsKey("blocks"));
        assertEquals(PaletteDataCodec.digest(data.blockStates(), data.blockDefaults()), root.getCompound("blocks_digest"));
        VanillaData again = read(bytes);
        assertEquals(data.blockStates(), again.blockStates());
        assertEquals(data.blockDefaults(), again.blockDefaults());
        assertEquals(data.blockItems(), again.blockItems());
    }

    @Test
    void everyBundledFileHasTheDigestOfItsDerivedPalette() throws IOException {
        // The bundled source does not check it on load (FileDataSource.BUNDLED): the build does, here and in checkBlocks
        for (DataVersion version : PaletteDataSource.bundled().versions()) {
            try (var in = PaletteDataCodecBlocksTest.class.getResourceAsStream("/paletter/" + version.name() + ".nbt")) {
                assertEquals(Access.data().loader(PaletteDataSource.bundled()).load(version).blockStates(), PaletteDataCodec.read(in, true).blockStates());
            }
        }
    }

    @Test
    void otherStatesOrDefaultsAreWrittenOut() throws IOException {
        VanillaData data = newest();
        List<NbtMap> fewer = new ArrayList<>(data.blockStates());
        fewer.removeLast();
        VanillaData lacking = new VanillaData(data.version(), fewer, data.items(), data.dataDrivenBlocks(), data.creative(), data.itemTags(),
            data.fallbacks(), data.blockDefaults(), data.experiments(), Map.of());
        assertFalse(PaletteDataCodec.derivable(lacking));
        assertEquals(fewer, read(bytes(lacking)).blockStates());

        Map<String, NbtMap> defaults = new HashMap<>(data.blockDefaults());
        defaults.remove(defaults.keySet().iterator().next());
        VanillaData otherDefaults = new VanillaData(data.version(), data.blockStates(), data.items(), data.dataDrivenBlocks(), data.creative(),
            data.itemTags(), data.fallbacks(), defaults, data.experiments(), data.blockItems());
        assertFalse(PaletteDataCodec.derivable(otherDefaults));
        assertEquals(defaults, read(bytes(otherDefaults)).blockDefaults());
    }

    @Test
    void aDigestOfAnotherPaletteFailsNamingTheVersion() throws IOException {
        NbtMap root = PaletteDataCodec.readGzip(new ByteArrayInputStream(bytes(newest())));
        NbtMap changed = root.toBuilder().putCompound("blocks_digest", NbtMap.builder().putInt("states", 1).putLong("hash", 1).build()).build();
        IOException e = assertThrows(IOException.class, () -> read(gzip(changed)));
        assertTrue(e.getMessage().contains("The block states the library derives for " + root.getString("name")), e.getMessage());
        IOException unknown = assertThrows(IOException.class, () -> read(gzip(root.toBuilder().putString("name", "9.99").build())));
        assertTrue(unknown.getMessage().contains("no generated block definitions of it (it has ["), unknown.getMessage());
    }

    @Test
    void theBlockToItemRelationKeepsOnlyWhatThePaletteDoesNotGive() throws IOException {
        List<NbtMap> states = List.of(BlockStates.of("test:same"), BlockStates.of("test:other"), BlockStates.of("test:none"));
        Map<String, String> blockItems = Map.of("test:same", "test:same", "test:other", "test:item", "test:elsewhere", "test:elsewhere");
        VanillaData data = new VanillaData(DataVersion.of("1.1", 1), states, List.of(), List.of(), RawLayout.of(Map.of()), Map.of(),
            VersionFallbacks.NONE, Map.of(), Map.of(), blockItems);
        byte[] bytes = bytes(data);
        NbtMap root = PaletteDataCodec.readGzip(new ByteArrayInputStream(bytes));
        assertEquals(Map.of("test:other", "test:item", "test:elsewhere", "test:elsewhere"), new HashMap<>(root.getCompound("block_items")));
        assertEquals(List.of("test:none"), root.getList("blocks_without_item", org.cloudburstmc.nbt.NbtType.STRING));
        assertEquals(blockItems, read(bytes).blockItems());
    }

    private static byte[] gzip(NbtMap root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (NBTOutputStream nbt = NbtUtils.createGZIPWriter(out)) {
            nbt.writeTag(root);
        }
        return out.toByteArray();
    }
}
