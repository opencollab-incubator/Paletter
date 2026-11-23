package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Paletter.Builder#build()} compiles the versions in parallel: the palettes are the same as compiled one after
 * another, with the same runtime ids, definitions and reports, the same objects shared between versions, and the reports
 * passed on in the same order.
 */
class ParallelCompileTest {
    /** Threads the global layer was compiled on, by the creative edit it runs. */
    private static final Set<String> THREADS = ConcurrentHashMap.newKeySet();

    /** A global layer with blocks and items in creative: every version compiles custom content on top of vanilla. */
    private static PaletteLayer layer() {
        return PaletteLayer.builder("server")
            .block(CustomBlock.builder("parallel:lamp").property(BlockProperty.bool("parallel:lit")).menuCategory(CreativeCategory.CONSTRUCTION).build())
            .block(CustomBlock.builder("parallel:slab").property(BlockProperty.range("parallel:height", 0, 3)).build())
            .item(CustomItem.builder("parallel:gem").component(MaxStackSize.of(16)).menuCategory(CreativeCategory.ITEMS).build())
            .creative(editor -> THREADS.add(Thread.currentThread().getName()))
            .build();
    }

    /** The configuration of the test, with its reports collected in order. */
    private static Paletter paletter(List<PaletteReport> reports, boolean sequential) {
        Paletter.Builder builder = Paletter.builder().experiments(Palettes.EXPERIMENTS).layer(layer()).reports(reports::add);
        builder.sequential = sequential;
        return builder.build();
    }

    @Test
    void parallelCompileGivesTheSamePalettes() {
        List<PaletteReport> sequentialReports = new ArrayList<>();
        Paletter sequential = paletter(sequentialReports, true);
        List<DataVersion> versions = sequential.versions();
        assertTrue(versions.size() > 1, "the test needs several versions");
        List<String> expectedSharing = PaletteComparison.sharing(palettes(sequential, versions));
        List<String> expectedVanillaSharing = PaletteComparison.sharing(vanillaPalettes(sequential, versions));

        // Several rounds: a race shows up in some of them only
        for (int round = 0; round < 3; round++) {
            THREADS.clear();
            List<PaletteReport> reports = Collections.synchronizedList(new ArrayList<>());
            Paletter parallel = paletter(reports, false);
            if (Runtime.getRuntime().availableProcessors() > 1) {
                assertTrue(THREADS.size() > 1 && THREADS.stream().allMatch(name -> name.startsWith("Paletter compile")),
                    "compiled on " + THREADS);
            }
            assertTrue(parallel.compileDataReleased());

            assertEquals(sequentialReports.size(), reports.size());
            for (int i = 0; i < reports.size(); i++) {
                PaletteReport expected = sequentialReports.get(i);
                PaletteReport report = reports.get(i);
                assertEquals(expected.version(), report.version(), "report " + i);
                assertEquals(expected.entries(), report.entries(), "report " + i);
                assertEquals(List.copyOf(expected.unknownBlocks()), List.copyOf(report.unknownBlocks()), "report " + i);
                assertEquals(List.copyOf(expected.unknownItems()), List.copyOf(report.unknownItems()), "report " + i);
            }
            for (DataVersion version : versions) {
                PaletteComparison.assertSameContent(sequential.palette(version.protocolVersion()), parallel.palette(version.protocolVersion()));
                PaletteComparison.assertSameContent(sequential.vanilla(version), parallel.vanilla(version));
            }
            assertEquals(expectedSharing, PaletteComparison.sharing(palettes(parallel, versions)), "round " + round);
            assertEquals(expectedVanillaSharing, PaletteComparison.sharing(vanillaPalettes(parallel, versions)), "round " + round);
            // Nothing was reported twice, however often it is asked for
            palettes(parallel, versions);
            assertEquals(sequentialReports.size(), reports.size());
        }
    }

    private static List<Palette> palettes(Paletter paletter, List<DataVersion> versions) {
        return versions.stream().map(version -> paletter.palette(version.protocolVersion())).toList();
    }

    private static List<Palette> vanillaPalettes(Paletter paletter, List<DataVersion> versions) {
        return versions.stream().map(paletter::vanilla).toList();
    }
}
