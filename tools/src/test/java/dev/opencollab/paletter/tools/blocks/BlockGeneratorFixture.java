package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VersionFallbacks;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small palettes of three versions and a repository of two of them, for the tests of the block generator and of update. */
public final class BlockGeneratorFixture {
    static final StateValues FACING = StateValues.ints("facing", 0, 3);
    static final StateValues OPEN = StateValues.bool("open_bit");
    static final StateValues HINGE = StateValues.bool("hinge_bit");
    static final StateValues GROWTH = StateValues.ints("growth", 0, 2);
    static final StateValues COLOUR = StateValues.strings("test:colour", "red", "blue");
    static final StateValues OTHER_COLOUR = StateValues.strings("colour", "green", "red");
    static final StateValues FEW_COLOURS = StateValues.strings("colour", "white", "black");
    static final StateValues ODD = StateValues.intList("odd", 4, 2, 9);

    private BlockGeneratorFixture() {
    }

    /**
     * 1.1: a, door (default facing 2), crop. 1.2: crop gone, new, door gains a hinge, odd. 1.3: the door's order turns,
     * new's default moves, odd loses a property, a block of the other colour list.
     */
    public static Map<String, BlockDefinitions.Palette> palettes() {
        BlockDefinitions v1 = new BlockDefinitions();
        v1.block("test:a");
        v1.block("test:door", FACING, OPEN).defaultState(2, false);
        v1.block("test:crop", GROWTH);
        BlockDefinitions v2 = v1.copy();
        v2.remove("test:crop");
        v2.block("test:new", COLOUR, OTHER_COLOUR).defaultState("blue", "green");
        v2.addProperties("test:door", 1, HINGE);
        v2.block("test:odd", OPEN, ODD);
        BlockDefinitions v3 = v2.copy();
        v3.redefine("test:door", OPEN, HINGE, FACING).defaultState(false, false, 2);
        v3.defaultState("test:new", "red", "red");
        v3.removeProperty("test:odd", "open_bit");
        v3.block("test:wool", FEW_COLOURS);
        Map<String, BlockDefinitions.Palette> palettes = new LinkedHashMap<>();
        palettes.put("1.1", v1.derive());
        palettes.put("1.2", v2.derive());
        palettes.put("1.3", v3.derive());
        return palettes;
    }

    /** A version file of a palette, with its block states, as an import writes it. */
    public static void write(Path file, String version, int protocol, BlockDefinitions.Palette palette) throws IOException {
        VanillaData data = new VanillaData(new DataVersion(version, protocol), palette.states(), List.of(), List.of(), RawLayout.of(Map.of()),
            Map.of(), VersionFallbacks.NONE, palette.defaults());
        try (OutputStream out = Files.newOutputStream(file)) {
            PaletteDataCodec.write(data, out, true);
        }
    }

    /**
     * Writes a repository with the versions 1.1 and 1.2 of {@link #palettes()}, both with their block states, 1.1 with
     * two verified builds, and CloudburstMC/Data's palette of 1.2, which shows test:new as we have it and nothing else.
     *
     * @return its bundled data directory
     */
    public static Path repository(Path root) throws IOException {
        Path resources = Files.createDirectories(root.resolve(BlockGenerator.RESOURCES));
        Files.writeString(resources.resolve("versions.txt"), "1.1 1\n1.2 2\n");
        Files.writeString(resources.resolve("builds.txt"), "# generated\n1.1 1.1.0.1 1\n1.1 1.1.1.1 1\n");
        Map<String, BlockDefinitions.Palette> palettes = palettes();
        write(resources.resolve("1.1.nbt"), "1.1", 1, palettes.get("1.1"));
        write(resources.resolve("1.2.nbt"), "1.2", 2, palettes.get("1.2"));
        List<NbtMap> theirs = palettes.get("1.2").states().stream().filter(s -> s.getString("name").equals("test:new")).toList();
        Path second = Files.createDirectories(root.resolve(BlockGenerator.SECOND_SOURCE).resolve("1.2"));
        Nbt.writeGzip(second.resolve("block_palette.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, theirs).build());
        return resources;
    }
}
