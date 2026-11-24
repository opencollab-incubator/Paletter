package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonParser;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.internal.component.ComponentIndex;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.tools.CommittedBuilds;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.addon.AddonWriter;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code molangVersion} BDS writes into every block follows the {@code min_engine_version} of the block's pack
 * ({@code molangVersions} of {@code mappings.json}, {@code ComponentIndex.molangVersion}), which a layer sets
 * ({@link PaletteLayer.Builder#minEngineVersion}). The captures back it: every block of the main pack of every spec capture
 * ({@link #everyCapturedBlockHasTheVersionOfItsPack}) and the control block of each of the add-on's engine packs
 * ({@link AddonWriter#enginePacks}, {@link #theEnginePacksHaveTheVersionOfTheirEngine}).
 */
class MolangVersionTest {
    private static int molang(NbtMap block) {
        return ((Number) block.getCompound("properties").get("molangVersion")).intValue();
    }

    @Test
    void everyCapturedBlockHasTheVersionOfItsPack() throws IOException {
        for (Path capture : ComponentConformanceTest.captures()) {
            String format = JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject().get("formatVersion").getAsString();
            Set<Integer> values = new TreeSet<>();
            for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                if (!block.getString("name").startsWith(AddonGenerator.NAMESPACE + ":engine_")) {
                    values.add(molang(block));
                }
            }
            assertEquals(Set.of(ComponentIndex.molangVersion(format)), values, capture + ": the molangVersion of the blocks of the main pack (format " + format + ")");
        }
    }

    @Test
    void theEnginePacksHaveTheVersionOfTheirEngine() throws IOException {
        Set<String> with = new TreeSet<>();
        for (Path capture : ComponentConformanceTest.captures()) {
            String build = capture.getParent().getFileName().toString();
            Set<String> found = new TreeSet<>();
            for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                AddonWriter.enginePacks().stream().filter(p -> p.identifier().equals(block.getString("name"))).forEach(pack -> {
                    found.add(pack.identifier());
                    assertEquals(ComponentIndex.molangVersion(pack.minEngineVersion()), molang(block), build + ": the block of the pack with min_engine_version "
                        + pack.minEngineVersion());
                });
            }
            if (!found.isEmpty()) {
                assertEquals(AddonWriter.enginePacks().size(), found.size(), build + ": a block of an engine pack is missing");
                with.add(build);
            }
        }
        CommittedBuilds.assertPending("engine pack blocks in its spec capture", with::contains);
    }

    /** A layer's engine version reaches its blocks; a layer without one is of the newest engine. */
    @Test
    void aLayerSendsTheVersionOfItsEngine() {
        Paletter paletter = Paletter.bundled();
        int protocol = paletter.latest().protocolVersion();
        for (String engine : new String[] {"1.16.100", "1.19.40", null}) {
            PaletteLayer.Builder layer = PaletteLayer.builder("engine").block(CustomBlock.builder("test:block").build());
            if (engine != null) {
                layer.minEngineVersion(engine);
            }
            Palette palette = paletter.palette(protocol).extend(layer.build());
            BlockPropertyData data = palette.blocks().blockProperties().stream().filter(b -> b.getName().equals("test:block")).findFirst().orElseThrow();
            assertEquals(ComponentIndex.molangVersion(engine), data.getProperties().getInt("molangVersion"), String.valueOf(engine));
        }
    }
}
