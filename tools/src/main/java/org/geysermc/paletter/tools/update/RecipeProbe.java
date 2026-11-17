package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.tools.client.RecipeCapture;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code recipes-capture --version <build> [--preview]}: captures a vanilla world of one BDS build, the same capture as the
 * one of {@code update}, and copies only the recipe files ({@link RecipeCapture#CRAFTING}, {@link RecipeCapture#TRIM}) into
 * {@code data/bds/<build>/vanilla}. Options: {@code --cache DIR}, {@code --port 19750}, {@code --timeout 300},
 * {@code --keep-server}.
 */
public final class RecipeProbe {
    private static final String WORLD = "paletter_recipes";

    private RecipeProbe() {
    }

    public static void run(Path root, Map<String, String> options) throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(options.containsKey("preview"), options.get("version"));
        String name = "recipes-" + distribution.platform() + "-" + distribution.version();
        Path server = cache.resolve("servers").resolve(name);
        Path work = cache.resolve("runs").resolve(name);
        int port = Integer.parseInt(options.getOrDefault("port", "19750"));
        int timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        if (!Files.exists(BdsServer.executable(server))) {
            BdsDistribution.extract(distribution.zip(cache), server);
        }
        try {
            capture(root.resolve(Layout.BDS).resolve(distribution.version()).resolve("vanilla"), root, distribution.version(), server, work, port, timeout);
        } finally {
            if (!options.containsKey("keep-server")) {
                BdsDistribution.deleteRecursively(server);
            }
        }
    }

    private static void capture(Path out, Path root, String bdsVersion, Path server, Path work, int port, int timeout) throws Exception {
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
        properties.put("level-name", WORLD);
        BdsServer.configure(server, properties);
        Path world = server.resolve("worlds").resolve(WORLD);
        BdsDistribution.deleteRecursively(world);
        Files.createDirectories(world);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds.log"));
            bds.awaitStarted(timeout);
            Capture.Reachable reachable = Capture.reachable(bds, address, 20);
            if (reachable == null || reachable.protocol() == 0) {
                throw new java.io.IOException("BDS answers neither a RakNet ping nor NetherNet LAN discovery with a protocol");
            }
            VanillaData reference = EducationProbe.reference(root, null, reachable.protocol());
            System.out.println("BDS " + bdsVersion + ", protocol " + reachable.protocol() + (reachable.netherNet() ? " (NetherNet)" : ""));
            Capture.capture(address, reachable.protocol(), reference, bdsVersion, work.resolve("vanilla"), null, 90, reachable.netherNet());
        }
        Files.createDirectories(out);
        for (String file : new String[] {RecipeCapture.CRAFTING, RecipeCapture.TRIM}) {
            Files.copy(work.resolve("vanilla").resolve(file), out.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Wrote " + out.resolve(file) + " (" + Files.size(out.resolve(file)) + " bytes)");
        }
    }
}
