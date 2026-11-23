package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockGenerator} end to end on a repository of two versions whose files carry their block states
 * ({@link BlockGeneratorFixture#repository}): the sources, the version files without block states but with their digest,
 * the import named from {@code builds.txt}, the state order a second source confirms, and the export back to data.
 */
class BlockGeneratorTest {
    @Test
    void theFilesOfAnImportBecomeSourcesAndVersionFilesWithoutBlockStates(@TempDir Path root) throws IOException {
        BlockGeneratorFixture.repository(root);
        BlockGenerator.Result result = BlockGenerator.generate(root);
        String base = BlockCodeTest.file(result.sources(), "BlockBase");
        assertTrue(base.contains("imported from the captures of BDS 1.1.0.1 (data/bds/1.1.0.1; builds.txt verifies 1.1.0.1, 1.1.1.1)"), base);
        String changes = BlockCodeTest.file(result.sources(), "BlockChanges1_2");
        assertTrue(changes.contains("imported from the captures of its preview build in data/bds"), changes);
        assertTrue(changes.contains("// new; " + BlockCode.CONFIRMED + "\n        v.block(\"test:new\""), changes);
        assertTrue(changes.contains("// new; " + BlockCode.INFERRED + "\n        v.block(\"test:odd\""), changes);
        assertEquals(List.of("test:door", "test:odd"), result.inferred().get("1.2"));
        assertEquals(List.of("1.1.nbt", "1.2.nbt"), List.copyOf(result.versionFiles().keySet()));
        BlockDefinitions.Palette palette = BlockGeneratorFixture.palettes().get("1.2");
        NbtMap slim = PaletteDataCodec.readGzip(new ByteArrayInputStream(result.versionFiles().get("1.2.nbt")));
        assertFalse(slim.containsKey("blocks") || slim.containsKey("block_defaults"));
        assertEquals(PaletteDataCodec.digest(palette.states(), palette.defaults()), slim.getCompound("blocks_digest"));
        assertEquals(PaletteDataCodec.FORMAT, slim.getInt("format"));
    }

    @Test
    void runWritesTheSlimFilesAndCheckFindsThemStale(@TempDir Path root) throws IOException {
        Path resources = BlockGeneratorFixture.repository(root);
        assertEquals(1, BlockGenerator.run(root, true), "a version file with its block states is stale");
        assertTrue(Nbt.readGzip(resources.resolve("1.2.nbt")).containsKey("blocks"), "check writes nothing");
        assertEquals(0, BlockGenerator.run(root, false));
        assertFalse(Nbt.readGzip(resources.resolve("1.2.nbt")).containsKey("blocks"));
        assertTrue(Files.exists(root.resolve(BlockCode.DIRECTORY).resolve("BlockChanges1_2.java")));
    }

    @Test
    void exportWritesThePalettesAsData(@TempDir Path root) throws IOException {
        Path resources = BlockGeneratorFixture.repository(root);
        Path out = root.resolve("export");
        BlockGenerator.export(root, out);
        assertEquals(Files.readString(resources.resolve("versions.txt")), Files.readString(out.resolve("versions.txt")));
        assertEquals(BlockGeneratorFixture.palettes().get("1.2").states(), BundledFiles.read(out.resolve("1.2.nbt")).blockStates());
    }
}
