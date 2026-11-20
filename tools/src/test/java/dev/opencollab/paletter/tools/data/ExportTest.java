package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code export} writes what a compiled palette sends; {@code verify --against} compares such a directory with a cb/data checkout. */
class ExportTest {
    @TempDir
    Path directory;

    private static Palette palette() {
        Paletter paletter = Paletter.bundled();
        return paletter.vanilla(paletter.latest());
    }

    @Test
    void exportAgainstItself() throws Exception {
        Palette palette = palette();
        Path export = Files.createDirectory(directory.resolve("export"));
        Export.write(palette, export);
        assertTrue(Files.exists(export.resolve(Export.MARKER)));

        Diff diff = Diff.against(export, palette.version().name(), export);
        assertTrue(diff.identical(), diff.markdown(20));
        assertTrue(diff.section("Export network ids").bundled.contains("states carry `network_id`"));
    }

    @Test
    void plantedDifference() throws Exception {
        Palette palette = palette();
        Path export = Files.createDirectory(directory.resolve("export"));
        Export.write(palette, export);

        Export.Compiled compiled = Export.compiled(palette);
        VanillaData data = compiled.data();
        List<VanillaItem> items = new ArrayList<>(data.items());
        int planted = 0;
        VanillaItem item = items.get(planted);
        items.set(planted, new VanillaItem(item.identifier(), item.runtimeId() + 1000, item.version(), item.componentBased(), item.componentData()));
        VanillaData changed = new VanillaData(data.version(), data.blockStates(), items, data.dataDrivenBlocks(), data.creative(),
            data.itemTags(), data.fallbacks(), data.blockDefaults());
        Path other = Files.createDirectory(directory.resolve("other"));
        Export.write(changed, other, compiled.networkIds());

        Diff diff = Diff.against(other, palette.version().name(), export);
        assertFalse(diff.identical());
        assertTrue(diff.markdown(50).contains(item.identifier()), diff.markdown(50));
    }

    /**
     * The compiled palette sends what the bundled data holds: exported, every section but the creative layout verifies as
     * identical against it. The creative layout differs in one known way: compiling gives the stacks of block items that the
     * raw layout lists without a block state (the doors) the block state the client needs. The stacks themselves, in order,
     * are the same.
     */
    @Test
    void bundledRoundTrip() throws Exception {
        Palette palette = palette();
        Path export = Files.createDirectory(directory.resolve("export"));
        Export.write(palette, export);
        Diff diff = Diff.source(export, palette.version().name(), palette.vanillaData(), "bundled");
        diff.sections.values().stream().filter(section -> !section.title.equals("Creative layout")).forEach(
            section -> assertTrue(section.differences.isEmpty(), section.title + "\n" + diff.markdown(30)));

        List<String> bundled = palette.vanillaData().creative().stacks().map(stack -> stack.identifier() + ":" + stack.damage()).toList();
        List<String> exported = Export.read(export, palette.version().name()).data().creative().stacks()
            .map(stack -> stack.identifier() + ":" + stack.damage()).toList();
        org.junit.jupiter.api.Assertions.assertEquals(bundled, exported);
    }
}
