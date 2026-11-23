package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.codegen.GeneratedSources;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.tools.data.Importer;
import dev.opencollab.paletter.tools.data.Nbt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code blocks generate} ({@code :tools:generateBlocks}) and {@code blocks check} ({@code :tools:checkBlocks}, part of
 * {@code ./gradlew check}): the generated vanilla block definitions ({@link BlockCode}) of the versions of
 * {@code versions.txt}, read from the bundled data. A version file that still has its block states (an import writes them)
 * is the palette of its version; one without them has its palette from the generated definitions the tools were compiled
 * with. Each palette is checked against the definitions read from it ({@link BlockModel}); then the files are written and
 * every version file without its block states, which the definitions give. A version no longer in {@code versions.txt}
 * (retired) leaves the definitions: the next one becomes the base, with what it inherited folded in.
 */
public final class BlockGenerator {
    static final String RESOURCES = "paletter/src/main/resources/paletter";
    /** Where the generated sources go, from the repository root. */
    public static final String DIRECTORY = BlockCode.DIRECTORY;
    /** How the first line of every generated file starts. */
    public static final String GENERATOR = BlockCode.GENERATOR;

    private BlockGenerator() {
    }

    /** What a run gives: the generated sources and the version files to write, by path from the repository root. */
    public record Result(Map<String, String> sources, Map<String, byte[]> versionFiles) {
    }

    /** @return the generated sources and slimmed version files of the bundled data under {@code root} */
    public static Result generate(Path root) throws IOException {
        return generate(root, root.resolve(RESOURCES));
    }

    /** @param resources a directory in the bundled format: the repository's, or a staged copy of it */
    public static Result generate(Path root, Path resources) throws IOException {
        Map<String, BlockDefinitions.Palette> palettes = new LinkedHashMap<>();
        Map<String, VanillaData> full = new TreeMap<>();
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            Path file = resources.resolve(entry.name() + ".nbt");
            VanillaData data = BundledFiles.read(file);
            palettes.put(entry.name(), new BlockDefinitions.Palette(data.blockStates(), data.blockDefaults()));
            if (Nbt.readGzip(file).containsKey("blocks")) {
                full.put(entry.name(), data);
            }
        }
        Map<String, BlockDefinitions> versions = BlockModel.versions(palettes);
        Map<String, String> sources = BlockCode.generate(versions, captures(root, palettes.keySet()), "the bundled palettes of versions.txt");
        Map<String, byte[]> versionFiles = new TreeMap<>();
        for (VanillaData data : full.values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PaletteDataCodec.write(data, bytes, false);
            versionFiles.put(root.relativize(resources.resolve(data.version().name() + ".nbt")).toString().replace('\\', '/'), bytes.toByteArray());
        }
        return new Result(sources, versionFiles);
    }

    /**
     * What each version was imported from: the newest build of its line with a vanilla capture or a Script API dump in {@code data/bds}
     * ({@code BDS 1.26.52.3}).
     */
    static Map<String, String> captures(Path root, Iterable<String> versions) throws IOException {
        List<String> builds = new ArrayList<>();
        Path bds = root.resolve(Layout.BDS);
        if (Files.isDirectory(bds)) {
            try (Stream<Path> dirs = Files.list(bds)) {
                // The import reads the network capture and the Script API dump (a preview's capture keeps only its dump)
                dirs.filter(dir -> BuildNumber.isBuild(dir.getFileName().toString())
                        && (Files.exists(dir.resolve("vanilla/capture.json")) || Files.exists(dir.resolve("dump.json.gz"))))
                    .map(dir -> dir.getFileName().toString()).forEach(builds::add);
            }
        }
        builds.sort(java.util.Comparator.comparing(BuildNumber::parse, java.util.Comparator.comparingInt(BuildNumber::minor)
            .thenComparingInt(BuildNumber::patch).thenComparingInt(BuildNumber::revision)));
        Map<String, String> captures = new TreeMap<>();
        for (String version : versions) {
            for (String build : builds) {
                if (BuildNumber.parse(build).inLine(version)) {
                    captures.put(version, "BDS " + build);
                }
            }
        }
        return captures;
    }

    /** @return the exit code: 1 if {@code check} found a generated file out of date or a version file with its block states */
    public static int run(Path root, boolean check) throws IOException {
        Result result = generate(root);
        List<String> changes = new ArrayList<>(GeneratedSources.sync(root, result.sources(), List.of(BlockCode.DIRECTORY), BlockCode.GENERATOR, check));
        for (Map.Entry<String, byte[]> file : result.versionFiles().entrySet()) {
            changes.add(file.getKey() + ": has its block states, which the generated definitions give");
            if (!check) {
                Files.write(root.resolve(file.getKey()), file.getValue());
            }
        }
        int code = GeneratedSources.report(changes, check, ":tools:generateBlocks");
        System.out.println(check ? result.sources().size() + " generated block files, " + changes.size() + " stale"
            : "Wrote " + changes.size() + " files of " + (result.sources().size() + result.versionFiles().size()));
        return code;
    }
}
