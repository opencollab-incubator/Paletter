package org.geysermc.paletter.tools.model.addon;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The generated add-ons are the committed ones (data/addons), and the large probe is the one that was captured. */
class FixedAddonsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void captureAddonIsTheCommittedPack(@TempDir Path out) throws IOException {
        FixedAddons.writeCapture(out);
        assertSame(out.resolve("behavior_pack"), ROOT.resolve("data/addons/capture/behavior_pack"));
        assertSame(out.resolve("resource_pack"), ROOT.resolve("data/addons/capture/resource_pack"));
    }

    @Test
    void creativeAddonIsTheCommittedPack(@TempDir Path out) throws IOException {
        FixedAddons.writeCreative(out);
        assertSame(out.resolve("behavior_pack"), ROOT.resolve("data/addons/creative/behavior_pack"));
    }

    @Test
    void largeProbeHasTheCapturedIdentifiers(@TempDir Path out) throws IOException {
        FixedAddons.writeCreativeLarge(out);
        Path capture = ROOT.resolve("data/bds/1.26.63.1/creative-large");
        java.util.Set<String> unique = new java.util.TreeSet<>();
        for (String file : List.of("custom_block_properties.nbt", "custom_item_registry.nbt")) {
            NbtMap data = Nbt.read(capture.resolve(file));
            data.getList("blocks", NbtType.COMPOUND, List.of()).forEach(b -> unique.add(b.getString("name")));
            data.getList("items", NbtType.COMPOUND, List.of()).forEach(i -> unique.add(i.getString("name")));
        }
        List<String> captured = new ArrayList<>(unique);
        assertEquals(740, captured.size());
        assertEquals(captured, FixedAddons.largeIdentifiers(out.resolve("behavior_pack")));
    }

    private static void assertSame(Path generated, Path committed) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(committed)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> made;
        try (Stream<Path> walk = Files.walk(generated)) {
            made = walk.filter(Files::isRegularFile).sorted().toList();
        }
        assertEquals(files.stream().map(committed::relativize).toList(), made.stream().map(generated::relativize).toList());
        for (Path file : files) {
            assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(generated.resolve(committed.relativize(file))), file.toString());
        }
    }
}
