package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.data.Importer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code spec-capture}: generates the spec add-on from {@code codegen/model}, installs it on a fresh world of a BDS of
 * the channel (experiments on), and records what BDS does with every variant: the content log of the load (what it
 * rejects) and the custom item registry and block properties it sends. Writes
 * {@code codegen/model/captures/bds-<version>/} (the input of {@code spec infer}). Reuses the {@code update} tooling
 * (download cache, server control, capture client).
 */
public final class SpecCapture {
    private static final String WORLD = "paletter_spec";

    private final Path root;
    private final Map<String, String> options;

    public SpecCapture(Path root, Map<String, String> options) {
        this.root = root;
        this.options = options;
    }

    public int run() throws Exception {
        boolean preview = options.getOrDefault("channel", "release").equals("preview");
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(preview, options.get("version"));
        int port = Integer.parseInt(options.getOrDefault("port", "19270"));
        int timeout = Integer.parseInt(options.getOrDefault("timeout", "600"));
        Path work = cache.resolve("runs").resolve("spec-" + distribution.platform() + "-" + distribution.version());
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        Path out = options.containsKey("out") ? Path.of(options.get("out"))
            : root.resolve("codegen/model/captures/bds-" + distribution.version());

        Path modelDir = root.resolve("codegen/model");
        AddonGenerator generator = new AddonGenerator(Model.read(modelDir.resolve("components.json")), Fixtures.read(modelDir));
        Path addon = work.resolve("addon");
        generator.write(addon);

        Path server = distribution.extract(cache, !options.containsKey("keep-server"));
        try {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("level-type", "FLAT");
            properties.put("level-seed", "0");
            properties.put("gamemode", "creative");
            properties.put("server-port", String.valueOf(port));
            properties.put("server-portv6", String.valueOf(port + 1));
            properties.put("transport", "raknet");
            properties.put("enable-lan-visibility", "true");
            properties.put("online-mode", "false");
            properties.put("allow-list", "false");
            properties.put("view-distance", "4");
            properties.put("content-log-console-output-enabled", "true");
            properties.put("content-log-level", "warning");
            properties.put("level-name", WORLD);
            properties.put("block-network-ids-are-hashes", "true");
            freshWorld(server, addon);
            BdsServer.configure(server, properties);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-create.log"));
                bds.awaitStarted(timeout);
            }
            List<String> experiments = options.containsKey("experiments")
                ? Arrays.asList(options.get("experiments").split(",")) : Update.DEFAULT_EXPERIMENTS;
            Capture.enableExperiments(server.resolve("worlds").resolve(WORLD).resolve("level.dat"), experiments);

            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            List<String> contentLog;
            Capture.Summary summary;
            int protocol;
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-capture.log"));
                bds.awaitStarted(timeout);
                contentLog = attribute(bds.lines(line -> line.matches(".*(ERROR|WARN)\\] \\[.*") && !line.contains("TRANSPORT"))
                    .stream().map(line -> line.replaceFirst("^\\[[^]]*(ERROR|WARN)\\] ", "$1 ")).toList());
                String[] pong = null;
                for (int attempt = 0; attempt < 10 && (pong == null || pong.length <= 3); attempt++) {
                    Thread.sleep(1000); // the pong carries the protocol once LAN discovery is up
                    pong = Capture.ping(address, 2000);
                }
                boolean raknet = pong != null && bds.sawLine(line -> line.contains("IPv4 supported, port: " + port));
                String[] netherNet = raknet ? null : Capture.netherNetPing(address, 10);
                if (raknet && pong.length > 3) {
                    protocol = Integer.parseInt(pong[2]);
                } else if (netherNet != null && !netherNet[0].equals("0")) {
                    protocol = Integer.parseInt(netherNet[0]);
                } else {
                    protocol = Integer.parseInt(options.getOrDefault("protocol", "0"));
                }
                if (protocol == 0) {
                    throw new IOException("No protocol from the server status; pass --protocol");
                }
                summary = Capture.capture(address, protocol, reference(protocol), distribution.version(), work.resolve("capture"),
                    work.resolve("custom"), 120, !raknet);
            }
            Files.createDirectories(out);
            for (String file : List.of("custom_item_registry.nbt", "custom_block_properties.nbt")) {
                Files.copy(work.resolve("custom").resolve(file), out.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(out.resolve("content-log.txt"), String.join("\n", contentLog) + "\n", StandardCharsets.UTF_8);
            Files.writeString(out.resolve("variants.json"), Model.GSON.toJson(generator.manifest()) + "\n", StandardCharsets.UTF_8);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("bdsVersion", distribution.version());
            metadata.addProperty("protocol", protocol);
            metadata.addProperty("preview", preview);
            metadata.addProperty("formatVersion", Fixtures.read(modelDir).formatVersion);
            metadata.addProperty("nativeCodec", summary.nativeCodec());
            metadata.addProperty("codecProtocol", summary.codecProtocol());
            JsonArray echoed = new JsonArray();
            summary.experiments().forEach(echoed::add);
            metadata.add("experiments", echoed);
            Files.writeString(out.resolve("capture.json"), new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
            System.out.println("Spec capture of BDS " + distribution.version() + " (protocol " + protocol + "): " + generator.variants().size()
                + " variants, " + contentLog.size() + " content log lines -> " + out);
            return 0;
        } finally {
            if (!options.containsKey("keep-server")) {
                try {
                    BdsDistribution.deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }
    }

    /**
     * BDS logs item errors as "Error Parsing Item 'x':" followed by the field errors; the field errors get the item
     * name prefixed so the (sorted) log still says which variant they belong to.
     */
    static List<String> attribute(List<String> lines) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        String item = null;
        java.util.regex.Pattern parsing = java.util.regex.Pattern.compile("Error Parsing Item '([^']+)'");
        for (String line : lines) {
            java.util.regex.Matcher m = parsing.matcher(line);
            if (m.find()) {
                item = m.group(1);
                out.add(line);
            } else if (line.contains("[Item]") && line.contains("Failed to parse field") && item != null) {
                out.add(line.replace("Failed to parse field", "'" + item + "': Failed to parse field"));
            } else {
                if (!line.contains("[Item]")) {
                    item = null;
                }
                out.add(line);
            }
        }
        return List.copyOf(out);
    }

    private VanillaData reference(int protocol) throws IOException {
        Path resources = root.resolve("paletter/src/main/resources/paletter");
        List<Importer.IndexEntry> index = Importer.readIndex(resources);
        Importer.IndexEntry entry = index.stream().filter(e -> e.protocol() == protocol).findFirst()
            .orElse(index.stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow());
        try (InputStream stream = Files.newInputStream(resources.resolve(entry.name() + ".nbt"))) {
            return PaletteDataCodec.read(stream);
        }
    }

    private static void freshWorld(Path server, Path addon) throws IOException {
        Path directory = server.resolve("worlds").resolve(WORLD);
        BdsDistribution.deleteRecursively(directory);
        Files.createDirectories(directory);
        Map<String, String> uuids = Map.of("behavior", AddonGenerator.BP_UUID, "resource", AddonGenerator.RP_UUID);
        for (String kind : List.of("behavior", "resource")) {
            Path source = addon.resolve(kind + "_pack");
            Path target = server.resolve(kind + "_packs").resolve("paletter_spec");
            BdsDistribution.deleteRecursively(target);
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
            Files.writeString(directory.resolve("world_" + kind + "_packs.json"),
                "[{\"pack_id\": \"" + uuids.get(kind) + "\", \"version\": [1, 0, 0]}]\n");
        }
    }
}
