package org.geysermc.paletter.tools.shape;

import org.geysermc.paletter.tools.bds.CapturePlatform;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.tools.client.LevelDat;
import org.geysermc.paletter.tools.client.VoxelShapeCapture;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.FixedAddons;
import org.geysermc.paletter.tools.update.EducationProbe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * {@code voxel-shapes capture}: what one BDS build says about voxel shapes, written to
 * {@code data/bds/<build>/voxel-shapes/}:
 * <ul>
 *     <li>{@code vanilla-shapes.json}: the boxes of the shapes BDS defines in its own packs ({@link ShapeFiles});</li>
 *     <li>{@code vanilla.json}: the {@code VoxelShapesPacket} of a world without packs or experiments. The same world
 *     is captured with the experiment {@value #EXPERIMENT} on; {@code capture.json} says whether that changed the
 *     packet, and if it did {@code experiment.json} is that packet;</li>
 *     <li>{@code addon-variants.json}, {@code addon.json}, {@code content-log.txt}: the shapes and blocks of the probe
 *     add-on ({@link ShapeAddon}), the packet of a world with it (experiment on) and what BDS logged;</li>
 *     <li>{@code anchors.json.gz}: the anchors ({@link ShapeAddon#anchors}) and the packet of a world with them
 *     (experiment on);</li>
 *     <li>{@code addon-plain.json}, {@code anchors-plain.json.gz}: the packets of the same two worlds without the
 *     experiment, where they differ.</li>
 * </ul>
 * {@code voxel-shapes import} turns the captures into the bundled {@code voxel_shapes.nbt} ({@link ShapeImport}).
 * <p>
 * Options: {@code --version 1.26.63.1}, {@code --cache DIR}, {@code --port 19310}, {@code --timeout 300},
 * {@code --keep-server}. Downloading BDS means accepting the Minecraft EULA.
 */
public final class ShapeProbe {
    public static final String DIRECTORY = "voxel-shapes";
    public static final String EXPERIMENT = "voxel_shapes";
    static final String GENERATED = "Captured from BDS by `voxel-shapes capture` (:tools:run, also part of `update`); do not edit by hand";
    private static final String PACK = "paletter_shapes";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ShapeProbe() {
    }

    public static void run(Path root, Map<String, String> options) throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(false, options.get("version"));
        Path server = distribution.serverDirectory(cache, "shapes-");
        Path work = distribution.runDirectory(cache, "shapes-");
        int port = Integer.parseInt(options.getOrDefault("port", "19310"));
        int timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        if (!Files.exists(BdsServer.executable(server))) {
            BdsDistribution.extract(distribution.zip(cache), server);
        }
        try {
            capture(root.resolve(Layout.BDS).resolve(distribution.version()).resolve(DIRECTORY), root, distribution.version(), server, work,
                null, port, timeout);
        } finally {
            if (!options.containsKey("keep-server")) {
                BdsDistribution.deleteRecursively(server);
            }
        }
    }

    /**
     * Captures on an extracted server.
     *
     * @param out where the capture goes: {@code data/bds/<build>/voxel-shapes}
     * @param work where the logs and whole captures go
     * @param reference the bundled data to resolve block hashes against and to take the vanilla block names for the
     *                  anchors from, or null for the bundled version of the server's protocol
     */
    public static void capture(Path out, Path root, String bdsVersion, Path server, Path work, VanillaData reference, int port,
                               int timeout) throws Exception {
        // A server kept from an earlier run still has the probe's pack: only the packs of BDS itself are read below
        BdsDistribution.deleteRecursively(server.resolve("behavior_packs").resolve(PACK));
        Files.createDirectories(out);
        String formatVersion = AddonGenerator.formatVersionFor(FixedAddons.FORMAT_VERSION, bdsVersion);
        String minEngine = "[" + formatVersion.replace(".", ", ") + "]";
        Files.writeString(out.resolve("vanilla-shapes.json"), shapesJson(bdsVersion, ShapeFiles.ofServer(server)));

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
        properties.put("block-network-ids-are-hashes", "true");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);

        // The vanilla world: created by the first start, which also tells the protocol
        String vanillaWorld = "paletter_shapes_vanilla";
        properties.put("level-name", vanillaWorld);
        BdsServer.configure(server, properties);
        world(server, vanillaWorld, null);
        int protocol;
        boolean netherNet;
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-create.log"));
            bds.awaitStarted(timeout);
            Capture.Reachable reachable = Capture.reachable(bds, address, 20);
            if (reachable == null || reachable.protocol() == 0) {
                throw new IOException("BDS answers neither a RakNet ping nor NetherNet LAN discovery with a protocol");
            }
            netherNet = reachable.netherNet();
            protocol = reachable.protocol();
        }
        if (reference == null) {
            reference = EducationProbe.reference(root, null, protocol);
        }
        System.out.println("BDS " + bdsVersion + ", protocol " + protocol);
        Path levelDat = server.resolve("worlds").resolve(vanillaWorld).resolve("level.dat");
        for (boolean experiment : new boolean[] {false, true}) {
            LevelDat.setExperiments(levelDat, experiment ? List.of(EXPERIMENT) : List.of());
            String label = experiment ? "experiment" : "vanilla";
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-" + label + ".log"));
                bds.awaitStarted(timeout);
                Capture.Summary summary = Capture.capture(address, protocol, reference, bdsVersion, work.resolve(label), null, 90, netherNet);
                if (summary.enabledExperiments().contains(EXPERIMENT) != experiment) {
                    throw new IOException("BDS did not apply " + EXPERIMENT + "=" + experiment + ": " + summary.experiments());
                }
            }
        }
        Path vanilla = packet(work.resolve("vanilla"));
        Files.copy(vanilla, out.resolve("vanilla.json"), StandardCopyOption.REPLACE_EXISTING);
        boolean experimentChanges = !Files.readString(vanilla).equals(Files.readString(packet(work.resolve("experiment"))));
        if (experimentChanges) {
            Files.copy(packet(work.resolve("experiment")), out.resolve("experiment.json"), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(out.resolve("experiment.json"));
        }

        // The add-on world: created with the pack, then the experiment switched on
        Path pack = work.resolve("pack");
        ShapeAddon.write(pack, formatVersion, minEngine);
        Files.writeString(out.resolve("addon-variants.json"), ShapeAddon.variantsJson(bdsVersion, formatVersion));
        List<String> contentLog = addonWorld(server, properties, "paletter_shapes_addon", pack, work, "addon", address, protocol, reference,
            bdsVersion, timeout, netherNet);
        Files.copy(packet(work.resolve("addon")), out.resolve("addon.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(out.resolve("content-log.txt"), String.join("\n", contentLog) + "\n", StandardCharsets.UTF_8);
        boolean plainAddonDiffers = !Files.readString(packet(work.resolve("addon"))).equals(Files.readString(packet(work.resolve("addon-plain"))));
        if (plainAddonDiffers) {
            Files.copy(packet(work.resolve("addon-plain")), out.resolve("addon-plain.json"), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(out.resolve("addon-plain.json"));
        }

        // The anchor world: a block before every vanilla block, which shows which block each unnamed shape is made for
        List<ShapeAddon.Anchor> anchors = ShapeAddon.anchors(reference.blockStates().stream().map(state -> state.getString("name")).toList());
        ShapeAddon.writeAnchors(pack, anchors, formatVersion, minEngine);
        List<String> anchorLog = addonWorld(server, properties, "paletter_shapes_anchors", pack, work, "anchors", address, protocol, reference,
            bdsVersion, timeout, netherNet);
        if (!anchorLog.isEmpty()) {
            throw new IOException("BDS logged about the anchors: " + anchorLog.subList(0, Math.min(5, anchorLog.size())));
        }
        writeAnchors(out.resolve("anchors.json.gz"), bdsVersion, anchors, packet(work.resolve("anchors")));
        if (!Files.readString(packet(work.resolve("anchors"))).equals(Files.readString(packet(work.resolve("anchors-plain"))))) {
            writeAnchors(out.resolve("anchors-plain.json.gz"), bdsVersion, anchors, packet(work.resolve("anchors-plain")));
        } else {
            Files.deleteIfExists(out.resolve("anchors-plain.json.gz"));
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("_generated", GENERATED);
        metadata.addProperty("bdsVersion", bdsVersion);
        metadata.addProperty(CapturePlatform.KEY, CapturePlatform.host());
        metadata.addProperty("protocol", protocol);
        metadata.addProperty("formatVersion", formatVersion);
        metadata.addProperty("experimentChangesPacket", experimentChanges);
        Files.writeString(out.resolve("capture.json"), new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
        System.out.println("Wrote " + out);
    }

    /**
     * Creates a world with a behavior pack and captures it as it is ({@code <label>-plain}) and with the experiment on
     * ({@code <label>}).
     *
     * @return what BDS logged about the pack
     */
    private static List<String> addonWorld(Path server, Map<String, String> properties, String world, Path pack, Path work, String label,
                                           InetSocketAddress address, int protocol, VanillaData reference, String bdsVersion,
                                           int timeout, boolean netherNet) throws Exception {
        properties.put("level-name", world);
        BdsServer.configure(server, properties);
        world(server, world, pack);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-" + label + "-plain.log"));
            bds.awaitStarted(timeout);
            Capture.capture(address, protocol, reference, bdsVersion, work.resolve(label + "-plain"), null, 90, netherNet);
        }
        LevelDat.setExperiments(server.resolve("worlds").resolve(world).resolve("level.dat"), List.of(EXPERIMENT));
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-" + label + ".log"));
            bds.awaitStarted(timeout);
            Capture.capture(address, protocol, reference, bdsVersion, work.resolve(label), null, 90, netherNet);
            return bds.lines(line -> line.matches(".*(ERROR|WARN)\\] \\[.*") && !line.contains("TRANSPORT"))
                .stream().map(line -> line.replaceFirst("^\\[[^]]*(ERROR|WARN)\\] ", "$1 ")).sorted().distinct().toList();
        }
    }

    private static Path packet(Path capture) throws IOException {
        Path file = capture.resolve(VoxelShapeCapture.FILE);
        if (Files.notExists(file)) {
            throw new IOException("BDS sent no voxel shapes packet: " + file + " is missing");
        }
        return file;
    }

    /** A fresh world, with the probe's behavior pack if one is given. */
    private static void world(Path server, String world, Path behaviorPack) throws IOException {
        Path directory = server.resolve("worlds").resolve(world);
        BdsDistribution.deleteRecursively(directory);
        Files.createDirectories(directory);
        if (behaviorPack == null) {
            return;
        }
        Path target = server.resolve("behavior_packs").resolve(PACK);
        BdsDistribution.deleteRecursively(target);
        try (var files = Files.walk(behaviorPack)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(behaviorPack.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(directory.resolve("world_behavior_packs.json"),
            "[{\"pack_id\": \"" + ShapeAddon.UUID + "\", \"version\": [1, 0, 0]}]\n");
    }

    /** The anchors and the packet BDS sent for them, gzipped (the packet has three shapes per anchor). */
    private static void writeAnchors(Path file, String bdsVersion, List<ShapeAddon.Anchor> anchors, Path packet) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("_generated", GENERATED);
        json.addProperty("bdsVersion", bdsVersion);
        JsonArray list = new JsonArray();
        for (ShapeAddon.Anchor anchor : anchors) {
            JsonObject entry = new JsonObject();
            entry.addProperty("block", ShapeAddon.NAMESPACE + ":" + anchor.block());
            entry.addProperty("before", anchor.before());
            entry.add("box", GSON.toJsonTree(anchor.box().coordinates()));
            list.add(entry);
        }
        json.add("anchors", list);
        json.add("packet", JsonParser.parseString(Files.readString(packet)));
        try (var stream = new GZIPOutputStream(Files.newOutputStream(file))) {
            stream.write(GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One shape per line, by pack: {@code "<identifier>": [[minX, minY, minZ, maxX, maxY, maxZ], ...]}, in pixels. */
    static String shapesJson(String bdsVersion, Map<String, Map<String, List<ShapeAddon.Box>>> packs) {
        StringBuilder text = new StringBuilder("{\n");
        text.append("  \"_generated\": \"Read from the behavior packs of BDS by `voxel-shapes capture` (:tools:run, also part of `update`); do not edit by hand\",\n");
        text.append("  \"bdsVersion\": ").append(GSON.toJson(bdsVersion)).append(",\n");
        text.append("  \"packs\": {");
        String packSeparator = "\n";
        for (Map.Entry<String, Map<String, List<ShapeAddon.Box>>> pack : packs.entrySet()) {
            text.append(packSeparator).append("    ").append(GSON.toJson(pack.getKey())).append(": {");
            String separator = "\n";
            for (Map.Entry<String, List<ShapeAddon.Box>> shape : pack.getValue().entrySet()) {
                JsonArray boxes = new JsonArray();
                shape.getValue().forEach(box -> boxes.add(GSON.toJsonTree(box.coordinates())));
                text.append(separator).append("      ").append(GSON.toJson(shape.getKey())).append(": ").append(GSON.toJson(boxes));
                separator = ",\n";
            }
            text.append("\n    }");
            packSeparator = ",\n";
        }
        return text.append("\n  }\n}\n").toString();
    }
}
