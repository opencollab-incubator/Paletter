package org.geysermc.paletter.generator;

import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paletter data generator.
 * <pre>
 * import  --source DIR --name 26.60 --protocol 2225 [--item-tags FILE] [--bds-dump FILE]
 *         Converts a palette dump (the layout of CloudburstMC/Data, as produced from BDS) into the bundled format.
 *         Fallbacks are read from generator/fallbacks/NAME.json if present.
 * bds-run --log FILE (--server DIR | --download DIR [--version 1.26.51.1] [--preview true]) [--timeout SECONDS]
 *         Installs bds-addon on a fresh flat world of a Bedrock Dedicated Server, runs it headless until the addon
 *         printed PALETTER-DONE and writes the console output to FILE (the input of import --bds-dump).
 *         --download fetches BDS for this OS from minecraft.net into DIR/VERSION first (the newest, or --version); keep
 *         DIR outside the repository. Downloading BDS means accepting the Minecraft EULA.
 * bds-compare --bds-dump FILE --name 26.60 [--item-tags FILE]
 *         Cross-checks a BDS dump with a bundled version: block states and defaults against the block palette, items
 *         and stack sizes, item tags against the bundled ones (and against --item-tags, e.g. Geyser's item_tags.json).
 * update  --channel release|preview [--version X] [--verify-only] [--import-preview] [--keep-server]
 *         [--cache DIR] [--report FILE] [--name 26.70] [--experiments a,b] [--port 19260] [--timeout SECONDS]
 *         The whole pipeline: newest BDS of the channel, Script API dump, vanilla and add-on network captures (hashed
 *         and sequential block ids), comparison with the committed data, import or refresh, component shape
 *         comparison, markdown report. --verify-only writes nothing and exits with 1 on any difference.
 * spec-capture [--channel release|preview] [--version X] [--out DIR] [--keep-server] [--cache DIR] [--port 19270]
 *         Generates the spec add-on from codegen/model (spec module), loads it in BDS with experiments on and writes the
 *         content log, the custom item registry and block properties and variants.json to
 *         codegen/model/captures/bds-VERSION (input of spec infer).
 * check   Verifies the bundled data: every block and item of the newest version that is missing in an older
 *         version needs a fallback there, and every fallback target must exist. Exits with 1 on problems.
 * </pre>
 * Paths are relative to the repository root.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        if (args[0].equals("update")) {
            System.exit(new Update(Path.of("").toAbsolutePath(), parseFlags(args)).run());
        }
        if (args[0].equals("spec-capture")) {
            System.exit(new SpecCapture(Path.of("").toAbsolutePath(), parseFlags(args)).run());
        }
        Map<String, String> options = parseOptions(args);
        Path root = Path.of("").toAbsolutePath();
        Path output = root.resolve("paletter/src/main/resources/paletter");
        switch (args[0]) {
            case "import" -> new Importer(output, root.resolve("generator/fallbacks")).run(
                Path.of(require(options, "source")),
                require(options, "name"),
                Integer.parseInt(require(options, "protocol")),
                options.containsKey("item-tags") ? Path.of(options.get("item-tags")) : null,
                options.containsKey("bds-dump") ? Path.of(options.get("bds-dump")) : null);
            case "bds-run" -> {
                Path server = options.containsKey("download")
                    ? BdsRunner.download(Path.of(options.get("download")), Boolean.parseBoolean(options.get("preview")), options.get("version"))
                    : Path.of(require(options, "server"));
                new BdsRunner(root.resolve("bds-addon")).run(server, Path.of(require(options, "log")),
                    Integer.parseInt(options.getOrDefault("timeout", "600")));
            }
            case "bds-compare" -> {
                Map<String, Map<String, List<String>>> tagSources = new LinkedHashMap<>();
                VanillaData data;
                try (InputStream stream = Files.newInputStream(output.resolve(require(options, "name") + ".nbt"))) {
                    data = PaletteDataCodec.read(stream);
                }
                tagSources.put("the bundled " + data.version().name() + " tags", data.itemTags());
                if (options.containsKey("item-tags")) {
                    tagSources.put(options.get("item-tags"), Importer.readItemTags(Path.of(options.get("item-tags"))));
                }
                BdsCompare.compare(BdsDump.read(Path.of(require(options, "bds-dump"))), data, tagSources);
            }
            case "check" -> System.exit(new Checker(output).run() ? 0 : 1);
            default -> usage();
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
        for (int i = 0; i < rest.size(); i++) {
            String arg = rest.get(i);
            if (!arg.startsWith("--") || i + 1 >= rest.size()) {
                throw new IllegalArgumentException("Expected --option value, got " + arg);
            }
            options.put(arg.substring(2), rest.get(++i));
        }
        return options;
    }

    /** Like {@link #parseOptions}, but an option without a value (followed by another option or last) is a flag. */
    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option [value], got " + args[i]);
            }
            String name = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(name, args[++i]);
            } else {
                options.put(name, "true");
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }

    private static void usage() {
        System.out.println("""
            Usage:
              import --source DIR --name 26.60 --protocol 2225 [--item-tags FILE] [--bds-dump FILE]
              bds-run --log FILE (--server DIR | --download DIR [--version 1.26.51.1] [--preview true]) [--timeout SECONDS]
              bds-compare --bds-dump FILE --name 26.60 [--item-tags FILE]
              update --channel release|preview [--version X] [--verify-only] [--import-preview] [--keep-server]
                     [--cache DIR] [--report FILE] [--name 26.70] [--experiments a,b] [--port 19260] [--timeout SECONDS]
              spec-capture [--channel release|preview] [--version X] [--out DIR] [--keep-server]
              check""");
    }
}
