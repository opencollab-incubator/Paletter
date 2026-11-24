package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsRun;
import dev.opencollab.paletter.tools.bds.BdsServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code creative-probe}: runs an add-on on its own copy of BDS and captures the creative inventory, once per server
 * start, to see where BDS puts custom content (see docs/wire-notes.md). The options are {@link Options}; the protocol and
 * the bundled version the capture resolves block hashes against follow from the build unless given.
 * The server is extracted into {@code <cache>/servers/creative-probe-<platform>-<version>} and kept.
 */
public final class CreativeProbe {
    private static final String WORLD = "paletter_creative_probe";
    private static final String PACK = "paletter_creative_probe";

    private CreativeProbe() {
    }

    /**
     * What a run is asked to do.
     *
     * @param bds the build ({@code version} required) and its server
     * @param addon the add-on directory, with {@code behavior_pack} and optionally {@code resource_pack}
     * @param out where the captures go
     * @param starts how many times the server starts
     * @param protocol the protocol of the build, or 0 for the one of its spec capture in {@code data/bds}
     * @param dataVersion the bundled version the captures resolve block hashes against, or null for the build's line
     * @param experiments the toggles of the world, empty for none
     */
    public record Options(BdsRun bds, Path addon, Path out, int starts, int protocol, String dataVersion, List<String> experiments) {
    }

    public static void run(Path root, Options options) throws Exception {
        Path addon = options.addon().toAbsolutePath();
        Path out = options.out().toAbsolutePath();
        Path cache = options.bds().cache();
        if (options.bds().version() == null) {
            throw new IllegalArgumentException("--version is required: the BDS build the add-on runs on");
        }
        BdsDistribution distribution = options.bds().distribution();
        Path server = distribution.serverDirectory(cache, "creative-probe-");
        if (!BdsDistribution.extracted(server)) {
            BdsDistribution.extract(distribution.zip(cache, root), server);
        }
        int port = options.bds().port();
        int starts = options.starts();
        int protocol = options.protocol();
        if (protocol == 0) {
            Integer committed = BuildHistory.protocol(root.resolve(Layout.BDS).resolve(distribution.version()));
            if (committed == null) {
                throw new IllegalArgumentException("No protocol: data/bds has no spec capture of " + distribution.version() + "; pass --protocol");
            }
            protocol = committed;
        }
        String dataVersion = options.dataVersion() != null ? options.dataVersion() : BuildNumber.parse(distribution.version()).dataVersion();
        VanillaData reference;
        try (InputStream stream = Files.newInputStream(root.resolve("paletter/src/main/resources/paletter").resolve(dataVersion + ".nbt"))) {
            reference = PaletteDataCodec.read(stream);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-name", WORLD);
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("gamemode", "creative");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("transport", "raknet");
        properties.put("enable-lan-visibility", "false");
        properties.put("online-mode", "false");
        properties.put("allow-list", "false");
        properties.put("view-distance", "4");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        installWorld(server, addon);
        Files.createDirectories(out);

        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(out.resolve("bds-create.log"));
            bds.awaitStarted(options.bds().timeout());
        }
        if (!options.experiments().isEmpty()) {
            Capture.enableExperiments(server.resolve("worlds").resolve(WORLD).resolve("level.dat"), options.experiments());
        }
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        for (int start = 1; start <= starts; start++) {
            Path run = out.resolve("start-" + start);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(out.resolve("bds-start-" + start + ".log"));
                bds.awaitStarted(options.bds().timeout());
                Capture.capture(address, protocol, reference, distribution.version(), run, run.resolve("custom"), 90);
                List<String> log = bds.lines(line -> line.matches(".*(ERROR|WARN)\\] \\[.*") && !line.contains("TRANSPORT"));
                Files.write(run.resolve("content-log.txt"), log);
            }
            System.out.println("Captured start " + start + " into " + run);
        }
    }

    private static void installWorld(Path server, Path addon) throws Exception {
        Path world = server.resolve("worlds").resolve(WORLD);
        BdsDistribution.deleteRecursively(world);
        Files.createDirectories(world);
        for (String kind : new String[] {"behavior", "resource"}) {
            Path source = addon.resolve(kind + "_pack");
            Path target = server.resolve(kind + "_packs").resolve(PACK);
            BdsDistribution.deleteRecursively(target);
            if (!Files.isDirectory(source)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path copy = target.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(copy);
                    } else {
                        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            JsonObject header = JsonParser.parseString(Files.readString(source.resolve("manifest.json")))
                .getAsJsonObject().getAsJsonObject("header");
            Files.writeString(world.resolve("world_" + kind + "_packs.json"), "[{\"pack_id\": \""
                + header.get("uuid").getAsString() + "\", \"version\": " + header.get("version") + "}]\n");
        }
    }
}
