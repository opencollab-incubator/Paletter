package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.blocks.BlockGenerator;
import dev.opencollab.paletter.tools.blocks.BlockGeneratorFixture;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlocksStage} in an update that writes (into a repository of its own): the palettes of the staged data become
 * sources, the version files go into the repository with their block states, so the stages after {@code Staging#apply}
 * read the staged palettes in this process, and lose them only in {@link BlocksStage#slim}, the last step.
 */
class BlocksStageTest {
    private static Update update(Path root) {
        Update update = new Update(root, UpdateOptions.release());
        update.begin(new BdsDistribution("1.26.99.1", false, "win", "https://example.invalid/bedrock-server-1.26.99.1.zip"));
        return update;
    }

    /** What {@code Staging#apply} does with the version files: the staged copy into the repository. */
    private static void apply(Path staged, Path resources) throws IOException {
        try (Stream<Path> files = Files.list(staged)) {
            for (Path file : files.toList()) {
                Files.copy(file, resources.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path stage(Path root) throws IOException {
        Path staged = Files.createDirectories(root.resolve("work/staging/resources"));
        apply(root.resolve("paletter/src/main/resources/paletter"), staged);
        return staged;
    }

    @Test
    void newVersionsAreReadAsStagedUntilTheLastStep(@TempDir Path root) throws IOException {
        BlockGeneratorFixture.repository(root);
        Path staged = stage(root);
        Update update = update(root);
        BlocksStage blocks = new BlocksStage(update.context());
        blocks.blocks(staged);
        apply(staged, update.context().resources);
        assertFalse(update.report().failed(), update.report().toString());
        assertTrue(Files.exists(root.resolve(BlockGenerator.DIRECTORY).resolve("BlockChanges1_2.java")));
        // The library this test runs on has no definitions of 1.2: what the stages after apply read is the staged palette
        BlockDefinitions.Palette palette = BlockGeneratorFixture.palettes().get("1.2");
        assertEquals(palette.states(), BundledFiles.read(update.context().resources.resolve("1.2.nbt")).blockStates());
        blocks.slim();
        NbtMap slim = Nbt.readGzip(update.context().resources.resolve("1.2.nbt"));
        assertFalse(slim.containsKey("blocks"));
        assertEquals(PaletteDataCodec.digest(palette.states(), palette.defaults()), slim.getCompound("blocks_digest"));
    }

    @Test
    void aChangedPaletteUnderAnExistingNameIsTheStagedOne(@TempDir Path root) throws IOException {
        Path resources = Files.createDirectories(root.resolve("paletter/src/main/resources/paletter"));
        DataVersion newest = PaletteDataSource.bundled().versions().getLast();
        VanillaData bundled = Access.data().loader(PaletteDataSource.bundled()).load(newest);
        Map<String, NbtMap> defaults = new HashMap<>(bundled.blockDefaults());
        defaults.remove(defaults.keySet().iterator().next());
        VanillaData changed = new VanillaData(newest, bundled.blockStates(), bundled.items(), bundled.dataDrivenBlocks(), bundled.creative(),
            bundled.itemTags(), bundled.fallbacks(), defaults, bundled.experiments(), bundled.blockItems());
        Files.writeString(resources.resolve("versions.txt"), newest.name() + " " + newest.protocolVersion() + "\n");
        try (OutputStream out = Files.newOutputStream(resources.resolve(newest.name() + ".nbt"))) {
            PaletteDataCodec.write(changed, out, true);
        }
        Path staged = stage(root);
        Update update = update(root);
        BlocksStage blocks = new BlocksStage(update.context());
        blocks.blocks(staged);
        apply(staged, resources);
        assertFalse(update.report().failed(), update.report().toString());
        assertEquals(defaults, BundledFiles.read(resources.resolve(newest.name() + ".nbt")).blockDefaults());
        blocks.slim();
        // Slim, the file names the changed palette: the library this test runs on derives the committed one, and says so
        IOException e = assertThrows(IOException.class, () -> BundledFiles.read(resources.resolve(newest.name() + ".nbt")));
        assertTrue(e.getMessage().contains("are not the ones its data file was written with"), e.getMessage());
    }

    @Test
    void aPaletteTheDefinitionsCannotSayIsAFailLineNotAnAbort(@TempDir Path root) throws IOException {
        Path resources = BlockGeneratorFixture.repository(root);
        Files.writeString(resources.resolve("versions.txt"), "1.1 1\n1.2 2\n9.9 3\n");
        BlockDefinitions.Palette palette = BlockGeneratorFixture.palettes().get("1.2");
        java.util.List<NbtMap> lacking = new java.util.ArrayList<>(palette.states());
        lacking.remove(palette.states().stream().filter(s -> s.getString("name").equals("test:door")).reduce((a, b) -> b).orElseThrow());
        BlockGeneratorFixture.write(resources.resolve("9.9.nbt"), "9.9", 3, new BlockDefinitions.Palette(lacking, palette.defaults()));
        Path staged = stage(root);
        Update update = update(root);
        new BlocksStage(update.context()).blocks(staged);
        assertTrue(update.report().failed(), "a block that is not a cartesian product fails the stage, as a line of the report");
    }
}
