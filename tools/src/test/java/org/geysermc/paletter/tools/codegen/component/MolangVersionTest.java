package org.geysermc.paletter.tools.codegen.component;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code molangVersion} {@code BlockWire} writes into every block is a constant. The captures back it in two steps: every block
 * of every spec capture has it ({@link #everyCapturedBlockHasTheConstant}), and the control blocks of the add-on's extra packs, whose
 * {@code min_engine_version} is older ({@link AddonGenerator#enginePacks}), have the same ({@link #theEnginePacksAgree}). If a pack's
 * block has another value, the engine version decides it: the constant must become an input of the API, and this test fails to
 * say so.
 */
class MolangVersionTest {
    private static final Path BLOCK_WIRE = ComponentConformanceTest.ROOT.resolve("paletter/src/generated/java/org/geysermc/paletter/internal/component/BlockWire.java");

    /**
     * The builds whose spec capture has no engine pack blocks yet: captured before the add-on had them. Explicit until the recapture;
     * a build with the blocks that is still listed fails, and so does one without them that is not listed.
     */
    private static final Set<String> ENGINE_PACKS_PENDING = Set.of("1.26.36.1", "1.26.45.1", "1.26.52.3", "1.26.63.1", "1.26.70.24");

    static int constant() throws IOException {
        Matcher m = Pattern.compile("out.put\\(\"molangVersion\", (\\d+)\\)").matcher(Files.readString(BLOCK_WIRE));
        assertTrue(m.find(), "BlockWire writes no molangVersion constant");
        return Integer.parseInt(m.group(1));
    }

    private static int molang(NbtMap block) {
        return ((Number) block.getCompound("properties").get("molangVersion")).intValue();
    }

    @Test
    void everyCapturedBlockHasTheConstant() throws IOException {
        int constant = constant();
        for (Path capture : ComponentConformanceTest.captures()) {
            Set<Integer> values = new TreeSet<>();
            for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                if (!block.getString("name").startsWith(AddonGenerator.NAMESPACE + ":engine_")) {
                    values.add(molang(block));
                }
            }
            assertEquals(Set.of(constant), values, capture + ": the molangVersion of its blocks, against the constant of BlockWire");
        }
    }

    @Test
    void theEnginePacksAgree() throws IOException {
        int constant = constant();
        Set<String> without = new TreeSet<>();
        for (Path capture : ComponentConformanceTest.captures()) {
            String build = capture.getParent().getFileName().toString();
            Set<String> found = new TreeSet<>();
            for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                AddonGenerator.enginePacks().stream().filter(p -> p.identifier().equals(block.getString("name"))).forEach(pack -> {
                    found.add(pack.identifier());
                    assertEquals(constant, molang(block), build + ": the block of the pack with min_engine_version " + pack.minEngineVersion()
                        + " has another molangVersion: it follows the engine version, so BlockWire's constant must become an input");
                });
            }
            if (found.isEmpty()) {
                without.add(build);
            } else {
                assertEquals(AddonGenerator.enginePacks().size(), found.size(), build + ": a block of an engine pack is missing");
            }
        }
        assertEquals(ENGINE_PACKS_PENDING, without, "the builds whose capture has the engine pack blocks must leave ENGINE_PACKS_PENDING");
    }
}
