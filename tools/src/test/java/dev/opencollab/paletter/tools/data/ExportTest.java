package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.access.Access;
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
     * The compiled palette sends what the bundled data holds (a capture of BDS without experiments): exported, every section,
     * the creative layout with the block of each stack included, verifies as identical against it. The item registry has the
     * items behind a disabled experiment too, as the bundled data does.
     */
    @Test
    void bundledRoundTrip() throws Exception {
        Paletter paletter = Paletter.bundled();
        Palette palette = paletter.vanilla(paletter.latest());
        Path export = Files.createDirectory(directory.resolve("export"));
        Export.write(palette, export);
        Diff diff = Diff.source(export, palette.version().name(), Access.palette().vanillaData(paletter, palette.version()), "bundled");
        diff.sections.values().forEach(section -> assertTrue(section.differences.isEmpty(), section.title + "\n" + diff.markdown(30)));
    }
}
