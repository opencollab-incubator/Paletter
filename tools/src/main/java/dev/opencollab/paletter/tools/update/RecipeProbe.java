package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsRun;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.bds.CapturePlatform;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.client.RecipeCapture;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code recipes-capture --version <build> [--channel preview]}: captures a vanilla world of one BDS build, the same capture as the
 * one of {@code update}, and copies only the recipe files ({@link RecipeCapture#CRAFTING}, {@link RecipeCapture#TRIM}) into
 * {@code data/bds/<build>/vanilla}. Options: {@code --cache DIR}, {@code --port 19750}, {@code --timeout 300},
 * {@code --keep-server}. It refuses a build without captures in {@code data/bds} and a machine of another BDS platform
 * than the committed captures ({@link CapturePlatform}).
 */
public final class RecipeProbe {
    private static final String WORLD = "paletter_recipes";

    private RecipeProbe() {
    }

    public static void run(Path root, BdsRun options) throws Exception {
        if (!Files.isDirectory(root.resolve(Layout.BDS))) {
            throw new IllegalArgumentException(root + " is not the repository root (it has no " + Layout.BDS
                + "); pass --root as a native path, e.g. C:/...");
        }
        Path cache = options.cache();
        BdsDistribution distribution = options.distribution();
        // The recipe files go beside the captures of the build, which `update` made (a preview has no vanilla/capture.json)
        Path build = root.resolve(Layout.BDS).resolve(distribution.version());
        if (!Files.isDirectory(build)) {
            throw new IllegalArgumentException("No captures of BDS " + distribution.version() + " in " + build + ": run `update` for it first");
        }
        String committed = CapturePlatform.single(root.resolve(Layout.BDS));
        String mismatch = committed == null ? null : CapturePlatform.mismatch("The committed data (" + Layout.BDS + ")", committed, CapturePlatform.host());
        if (mismatch != null) {
            throw new IllegalStateException(mismatch);
        }
        String name = "recipes-" + distribution.platform() + "-" + distribution.version();
        Path server = cache.resolve("servers").resolve(name);
        Path work = cache.resolve("runs").resolve(name);
        int port = options.port();
        int timeout = options.timeout();
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        if (!BdsDistribution.extracted(server)) {
            BdsDistribution.extract(distribution.zip(cache, root), server);
        }
        try {
            capture(build.resolve("vanilla"), root, distribution.version(), server, work, port, timeout);
        } finally {
            if (!options.keepServer()) {
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
            Capture.requireProtocol(reachable);
            VanillaData reference = EducationProbe.reference(root, null, reachable.protocol());
            System.out.println("BDS " + bdsVersion + ", protocol " + reachable.protocol() + (reachable.netherNet() ? " (NetherNet)" : ""));
            Capture.capture(address, reachable.protocol(), reference, bdsVersion, work.resolve("vanilla"), null, 90, reachable.netherNet());
        }
        Files.createDirectories(out);
        for (String file : new String[] {RecipeCapture.CRAFTING, RecipeCapture.TRIM}) {
            if (!Files.exists(work.resolve("vanilla").resolve(file))) {
                throw new IOException("BDS sent no data for " + file + "; see " + work.resolve("bds.log"));
            }
            Files.copy(work.resolve("vanilla").resolve(file), out.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Wrote " + out.resolve(file) + " (" + Files.size(out.resolve(file)) + " bytes)");
        }
    }
}
