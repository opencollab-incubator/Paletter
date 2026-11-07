package org.geysermc.paletter.generator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paletter data generator.
 * <pre>
 * import  --source DIR --name 26.60 --protocol 2225 [--item-tags FILE] [--bds-dump FILE]
 *         Converts a palette dump (the layout of CloudburstMC/Data, as produced from BDS) into the bundled format.
 *         Fallbacks are read from generator/fallbacks/NAME.json if present.
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
              check""");
    }
}
