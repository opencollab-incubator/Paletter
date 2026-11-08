package org.geysermc.paletter.capture;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** level.dat experiment editing on synthetic files shaped like BDS writes them (storage version 10, LE NBT). */
class LevelDatTest {

    private static byte[] levelDat(NbtMap experiments) throws IOException {
        NbtMap root = NbtMap.builder().putString("LevelName", "test").putInt("StorageVersion", 10)
            .putCompound("experiments", experiments).build();
        return LevelDat.write(new LevelDat.Contents(10, root));
    }

    private static NbtMap fresh() {
        // What BDS 1.26.63.1 writes for a new world
        return NbtMap.builder().putBoolean(LevelDat.EVER_USED, false).putBoolean(LevelDat.SAVED_WITH_TOGGLED, false).build();
    }

    @Test
    void readsTheExperimentsCompound() throws IOException {
        byte[] bytes = levelDat(NbtMap.builder().putBoolean("gametest", true).putBoolean("poi", false)
            .putBoolean(LevelDat.EVER_USED, true).putBoolean(LevelDat.SAVED_WITH_TOGGLED, true).build());
        Map<String, Boolean> experiments = LevelDat.experiments(bytes);
        assertEquals(Map.of("gametest", true, "poi", false, LevelDat.EVER_USED, true, LevelDat.SAVED_WITH_TOGGLED, true), experiments);
        assertEquals(List.of("gametest"), LevelDat.enabled(experiments));
    }

    @Test
    void replaceSetsExactlyTheTogglesAndTheFlags() throws IOException {
        byte[] bytes = levelDat(NbtMap.builder().putBoolean("gametest", true).putBoolean(LevelDat.EVER_USED, true)
            .putBoolean(LevelDat.SAVED_WITH_TOGGLED, true).build());
        byte[] replaced = LevelDat.enable(bytes, List.of("y_2026_drop_4", " poi "), true);
        Map<String, Boolean> experiments = LevelDat.experiments(replaced);
        assertEquals(List.of("poi", "y_2026_drop_4"), LevelDat.enabled(experiments));
        assertTrue(experiments.get(LevelDat.EVER_USED));
        assertTrue(experiments.get(LevelDat.SAVED_WITH_TOGGLED));

        // None: saved_with_toggled_experiments goes off, experiments_ever_used stays (BDS never clears it)
        Map<String, Boolean> none = LevelDat.experiments(LevelDat.enable(replaced, List.of(), true));
        assertEquals(List.of(), LevelDat.enabled(none));
        assertTrue(none.get(LevelDat.EVER_USED));
        assertEquals(false, none.get(LevelDat.SAVED_WITH_TOGGLED));

        // A fresh world stays unflagged when nothing is enabled
        Map<String, Boolean> untouched = LevelDat.experiments(LevelDat.enable(levelDat(fresh()), List.of(), true));
        assertEquals(false, untouched.get(LevelDat.EVER_USED));
    }

    @Test
    void enableKeepsExistingTogglesAndTheRestOfTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("level.dat");
        Files.write(file, levelDat(NbtMap.builder().putBoolean("gametest", true).build()));
        LevelDat.enableExperiments(file, List.of("voxel_shapes"));
        assertEquals(List.of("gametest", "voxel_shapes"), LevelDat.enabled(LevelDat.experiments(file)));
        LevelDat.Contents contents = LevelDat.read(Files.readAllBytes(file));
        assertEquals(10, contents.storageVersion());
        assertEquals("test", contents.root().getString("LevelName"));
        // The header length is the payload length
        ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt();
        assertEquals(Files.size(file) - 8, header.getInt());
    }

    @Test
    void manyCandidatesFit(@TempDir Path directory) throws IOException {
        // Discovery writes tens of thousands of candidate keys at once
        Path file = directory.resolve("level.dat");
        Files.write(file, levelDat(fresh()));
        List<String> candidates = java.util.stream.IntStream.range(0, 50_000).mapToObj(i -> "candidate_" + i).toList();
        LevelDat.setExperiments(file, candidates);
        assertEquals(50_000, LevelDat.enabled(LevelDat.experiments(file)).size());
    }

    @Test
    void rejectsBrokenFiles() throws IOException {
        byte[] good = levelDat(fresh());
        assertThrows(IOException.class, () -> LevelDat.experiments(new byte[4]));
        assertThrows(IOException.class, () -> LevelDat.experiments(Arrays.copyOf(good, good.length - 3)));
        byte[] wrongLength = good.clone();
        ByteBuffer.wrap(wrongLength).order(ByteOrder.LITTLE_ENDIAN).putInt(4, good.length);
        assertThrows(IOException.class, () -> LevelDat.experiments(wrongLength));
    }

    @Test
    void rejectsBookkeepingKeysAndBlankToggles() throws IOException {
        byte[] bytes = levelDat(fresh());
        assertThrows(IllegalArgumentException.class, () -> LevelDat.enable(bytes, List.of(LevelDat.EVER_USED), true));
        assertThrows(IllegalArgumentException.class, () -> LevelDat.enable(bytes, List.of(" "), false));
    }

    @Test
    void noExperimentsCompound() throws IOException {
        NbtMap root = NbtMap.builder().putString("LevelName", "old").build();
        byte[] bytes = LevelDat.write(new LevelDat.Contents(9, root));
        assertEquals(Map.of(), LevelDat.experiments(bytes));
        assertEquals(List.of("gametest"), LevelDat.enabled(LevelDat.experiments(LevelDat.enable(bytes, List.of("gametest"), false))));
    }
}
