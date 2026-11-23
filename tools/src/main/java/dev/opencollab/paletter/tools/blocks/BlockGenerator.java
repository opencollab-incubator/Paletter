package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.tools.codegen.GeneratedSources;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code blocks generate} ({@code :tools:generateBlocks}), {@code blocks check} ({@code :tools:checkBlocks}, part of
 * {@code ./gradlew check}) and {@code blocks export}: the generated vanilla block definitions ({@link BlockCode}) of the
 * versions of {@code versions.txt}, read from the bundled data. A version file that still has its block states (an import
 * writes them) is the palette of its version; one without them has its palette from the generated definitions the tools
 * were compiled with, checked against the digest the file stores (so a definition that is wrong but consistent, a merge
 * of two changes nobody captured, fails). Each palette is checked against the definitions read from it ({@link BlockModel});
 * then the sources are written and every version file without its block states. A version no longer in
 * {@code versions.txt} (retired) leaves the definitions: the next one becomes the base, with what it inherited folded in.
 */
public final class BlockGenerator {
    static final String RESOURCES = "paletter/src/main/resources/paletter";
    /** The CloudburstMC/Data palettes of the test data: a second source of the state order of their versions. */
    static final String SECOND_SOURCE = "tools/src/test/resources/test-data";
    /** Where the generated sources go, from the repository root. */
    public static final String DIRECTORY = BlockCode.DIRECTORY;
    /** How the first line of every generated file starts. */
    public static final String GENERATOR = BlockCode.GENERATOR;

    private BlockGenerator() {
    }

    /**
     * What a run gives.
     *
     * @param sources the generated sources by path from the repository root
     * @param versionFiles the version files without their block states, by file name in the bundled data directory: only
     *                     those that still had them
     * @param inferred per version, the blocks a change of that version gives a state order no second source confirms
     */
    public record Result(Map<String, String> sources, Map<String, byte[]> versionFiles, Map<String, List<String>> inferred) {
    }

    /** @return the generated sources and slimmed version files of the bundled data under {@code root} */
    public static Result generate(Path root) throws IOException {
        return generate(root, root.resolve(RESOURCES));
    }

    /** @param resources a directory in the bundled format: the repository's, or a staged copy of it */
    public static Result generate(Path root, Path resources) throws IOException {
        Map<String, BlockDefinitions.Palette> palettes = palettes(resources);
        Map<String, byte[]> versionFiles = new TreeMap<>();
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            Path file = resources.resolve(entry.name() + ".nbt");
            if (Nbt.readGzip(file).containsKey("blocks")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                PaletteDataCodec.write(BundledFiles.read(file), bytes, false);
                versionFiles.put(entry.name() + ".nbt", bytes.toByteArray());
            }
        }
        Map<String, BlockDefinitions> versions = BlockModel.versions(palettes);
        Map<String, Set<String>> confirmed = new TreeMap<>();
        for (String version : palettes.keySet()) {
            confirmed.put(version, confirmed(root, version, palettes.get(version)));
        }
        BlockCode.Generated generated = BlockCode.generate(versions, imports(resources, palettes.keySet()), confirmed,
            "the bundled palettes of versions.txt");
        return new Result(generated.files(), versionFiles, generated.inferred());
    }

    /** @return the palette of every version of {@code versions.txt} in a bundled data directory, oldest first */
    static Map<String, BlockDefinitions.Palette> palettes(Path resources) throws IOException {
        Map<String, BlockDefinitions.Palette> palettes = new LinkedHashMap<>();
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            VanillaData data = BundledFiles.read(resources.resolve(entry.name() + ".nbt"));
            palettes.put(entry.name(), new BlockDefinitions.Palette(data.blockStates(), data.blockDefaults()));
        }
        return palettes;
    }

    /**
     * What each version was imported from, named once in its class: the builds {@code builds.txt} records as sending its
     * data (the build it was imported from first), or that it is a preview, which none is verified for.
     */
    static Map<String, String> imports(Path resources, Iterable<String> versions) throws IOException {
        Map<String, List<String>> builds = new TreeMap<>();
        Path file = resources.resolve("builds.txt");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.strip().split("\\s+");
                if (parts.length >= 2 && !line.startsWith("#")) {
                    builds.computeIfAbsent(parts[0], v -> new ArrayList<>()).add(parts[1]);
                }
            }
        }
        Map<String, String> imports = new TreeMap<>();
        for (String version : versions) {
            List<String> verified = builds.get(version);
            imports.put(version, verified == null ? "the captures of its preview build in data/bds (no release build is verified to send it, builds.txt)"
                : "the captures of BDS " + verified.getFirst() + " (data/bds/" + verified.getFirst() + "; builds.txt verifies "
                + String.join(", ", verified) + ")");
        }
        return imports;
    }

    /**
     * The blocks of a version whose state order a second source shows the same: the CloudburstMC/Data palette of the
     * version in the test data. BDS sends no palette, so where there is none the order of a new block is inferred.
     */
    static Set<String> confirmed(Path root, String version, BlockDefinitions.Palette palette) throws IOException {
        Path file = root.resolve(SECOND_SOURCE).resolve(version).resolve("block_palette.nbt");
        Set<String> confirmed = new HashSet<>();
        if (!Files.exists(file)) {
            return confirmed;
        }
        Map<String, List<NbtMap>> theirs = new LinkedHashMap<>();
        for (NbtMap state : Nbt.readGzip(file).getList("blocks", NbtType.COMPOUND)) {
            theirs.computeIfAbsent(state.getString("name"), n -> new ArrayList<>()).add(state.getCompound("states", NbtMap.EMPTY));
        }
        Map<String, List<NbtMap>> ours = new LinkedHashMap<>();
        palette.states().forEach(state -> ours.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(BlockStates.states(state)));
        ours.forEach((name, states) -> {
            if (states.equals(theirs.get(name))) {
                confirmed.add(name);
            }
        });
        return confirmed;
    }

    /** @return the exit code: 1 if {@code check} found a generated file out of date or a version file with its block states */
    public static int run(Path root, boolean check) throws IOException {
        Result result = generate(root);
        List<String> changes = new ArrayList<>(GeneratedSources.sync(root, result.sources(), List.of(BlockCode.DIRECTORY), BlockCode.GENERATOR, check));
        for (Map.Entry<String, byte[]> file : result.versionFiles().entrySet()) {
            changes.add(RESOURCES + "/" + file.getKey() + ": has its block states, which the generated definitions give");
            if (!check) {
                Files.write(root.resolve(RESOURCES).resolve(file.getKey()), file.getValue());
            }
        }
        int code = GeneratedSources.report(changes, check, ":tools:generateBlocks");
        result.inferred().forEach((version, blocks) -> {
            if (!blocks.isEmpty()) {
                System.out.println(version + ": state order inferred, not observed, for " + blocks.size() + " blocks: " + blocks);
            }
        });
        System.out.println(check ? result.sources().size() + " generated block files, " + changes.size() + " stale"
            : "Wrote " + changes.size() + " files of " + (result.sources().size() + result.versionFiles().size()));
        return code;
    }

    /** Writes every version with its block states into {@code out}: the palettes back as data, to recover from. */
    public static void export(Path root, Path out) throws IOException {
        Path resources = root.resolve(RESOURCES);
        Files.createDirectories(out);
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            try (var stream = Files.newOutputStream(out.resolve(entry.name() + ".nbt"))) {
                PaletteDataCodec.write(BundledFiles.read(resources.resolve(entry.name() + ".nbt")), stream, true);
            }
        }
        for (String file : List.of("versions.txt", "builds.txt", "renames.txt", BundledFiles.STORE)) {
            if (Files.exists(resources.resolve(file))) {
                Files.copy(resources.resolve(file), out.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("Wrote the bundled data with block states to " + out);
    }
}
