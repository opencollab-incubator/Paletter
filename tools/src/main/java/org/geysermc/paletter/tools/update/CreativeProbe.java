package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code creative-probe}: runs an add-on on its own copy of BDS and captures the creative inventory, once per server
 * start, to see where BDS puts custom content (see data/bds/CREATIVE-ORDER.md). Options: {@code --addon DIR} (with
 * {@code behavior_pack}, optional {@code resource_pack}), {@code --out DIR}, {@code --version 1.26.63.1},
 * {@code --reference 26.60} (bundled version the capture resolves block hashes against), {@code --protocol 2225},
 * {@code --starts 2}, {@code --experiments a,b} (none by default), {@code --port 19290}, {@code --cache DIR}.
 * The server is extracted into {@code <cache>/servers/creative-probe-<platform>-<version>} and kept.
 */
public final class CreativeProbe {
    private static final String WORLD = "paletter_creative_probe";
    private static final String PACK = "paletter_creative_probe";

    private CreativeProbe() {
    }

    public static void run(Path root, Map<String, String> options) throws Exception {
        Path addon = Path.of(options.get("addon")).toAbsolutePath();
        Path out = Path.of(options.get("out")).toAbsolutePath();
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(false, options.getOrDefault("version", "1.26.63.1"));
        Path server = cache.resolve("servers").resolve("creative-probe-" + distribution.platform() + "-" + distribution.version());
        if (!Files.exists(BdsServer.executable(server))) {
            BdsDistribution.extract(distribution.zip(cache), server);
        }
        int port = Integer.parseInt(options.getOrDefault("port", "19290"));
        int starts = Integer.parseInt(options.getOrDefault("starts", "2"));
        int protocol = Integer.parseInt(options.getOrDefault("protocol", "2225"));
        VanillaData reference;
        try (InputStream stream = Files.newInputStream(root.resolve("paletter/src/main/resources/paletter")
            .resolve(options.getOrDefault("reference", "26.60") + ".nbt"))) {
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
            bds.awaitStarted(300);
        }
        if (options.containsKey("experiments")) {
            Capture.enableExperiments(server.resolve("worlds").resolve(WORLD).resolve("level.dat"),
                Arrays.asList(options.get("experiments").split(",")));
        }
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        for (int start = 1; start <= starts; start++) {
            Path run = out.resolve("start-" + start);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(out.resolve("bds-start-" + start + ".log"));
                bds.awaitStarted(300);
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
