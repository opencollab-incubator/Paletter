package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.client.LevelDat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs a Paletter addon (the dump addon, the behaviour probe) on a Bedrock Dedicated Server and captures its console
 * output.
 * <ol>
 *     <li>optionally downloads the newest BDS for this OS from the official download links API;</li>
 *     <li>installs the addon as a behavior pack on a fresh flat world;</li>
 *     <li>starts BDS headless, copies its output to the log file, waits for the addon's {@code <marker>-DONE} (the dump
 *     addon prints {@code PALETTER-DONE}) and stops it.</li>
 * </ol>
 * Downloading BDS means accepting the Minecraft EULA and privacy policy (https://minecraft.net/eula); BDS itself asks
 * for nothing else. Keep the server directory outside the repository.
 */
public final class BdsRunner {
    static final int DUMP_PORT = 19232;
    public static final String WORLD = "paletter";

    private final Path addon;
    private final String marker;
    private final String packDirectory;

    /** The dump addon: it prints {@code PALETTER-DONE}. */
    public BdsRunner(Path addon) {
        this(addon, "PALETTER", "paletter_dumper");
    }

    /**
     * @param marker the addon prints {@code <marker>-DONE} when it is finished and {@code <marker>-FAILED} if it failed
     * @param packDirectory the name of the pack's directory in the server's {@code behavior_packs}
     */
    public BdsRunner(Path addon, String marker, String packDirectory) {
        this.addon = addon;
        this.marker = marker;
        this.packDirectory = packDirectory;
    }

    /**
     * Downloads and extracts BDS into {@code parent/<version>} unless it is already there.
     *
     * @param version a BDS version such as {@code 1.26.51.1}, or {@code null} for the newest one (or newest preview)
     * @return the server directory
     */
    public static Path download(Path parent, boolean preview, String version) throws IOException, InterruptedException {
        BdsDistribution distribution = BdsDistribution.resolve(preview, version);
        Path server = parent.resolve(distribution.version() + (preview ? "-preview" : ""));
        if (Files.exists(BdsServer.executable(server))) {
            System.out.println("BDS " + distribution.version() + " is already in " + server);
            return server;
        }
        BdsDistribution.extract(distribution.zip(parent.resolve(".cache")), server);
        return server;
    }

    public void run(Path server, Path log, int timeoutSeconds) throws IOException, InterruptedException {
        run(server, log, timeoutSeconds, DUMP_PORT, true);
    }

    /**
     * Installs the addon on a fresh world, runs BDS until the addon printed {@code PALETTER-DONE} and stops it.
     */
    public void run(Path server, Path log, int timeoutSeconds, int port, boolean echo) throws IOException, InterruptedException {
        installPack(server);
        configure(server, port);
        System.out.println("Starting BDS in " + server + ", log: " + log);
        dump(server, log, timeoutSeconds, echo);
        System.out.println("Dump complete: " + log);
    }

    /**
     * Runs the dump again on the world of the last {@link #run}, with exactly these experiment toggles in its
     * {@code level.dat} (BDS applies them when it loads the world).
     */
    public void rerun(Path server, Path log, int timeoutSeconds, int port, Collection<String> toggles) throws IOException, InterruptedException {
        Path levelDat = server.resolve("worlds").resolve(WORLD).resolve("level.dat");
        if (!Files.exists(levelDat)) {
            throw new IOException("No dump world to rerun in " + levelDat.getParent());
        }
        LevelDat.setExperiments(levelDat, toggles);
        configure(server, port);
        dump(server, log, timeoutSeconds, false);
    }

    private static void configure(Path server, int port) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-name", WORLD);
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("enable-lan-visibility", "false");
        properties.put("online-mode", "false");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");
        properties.put("allow-list", "false");
        BdsServer.configure(server, properties);
    }

    private void dump(Path server, Path log, int timeoutSeconds, boolean echo) throws IOException, InterruptedException {
        String result;
        try (BdsServer bds = new BdsServer(server, echo)) {
            bds.start(log);
            result = bds.await(line -> line.contains(marker + "-DONE") || line.contains(marker + "-FAILED"), timeoutSeconds);
        }
        if (result == null || !result.contains(marker + "-DONE")) {
            throw new IOException("BDS exited without " + marker + "-DONE (timeout " + timeoutSeconds + "s); see " + log);
        }
    }

    private void installPack(Path server) throws IOException {
        Path pack = server.resolve("behavior_packs").resolve(packDirectory);
        deleteRecursively(pack);
        try (Stream<Path> files = Files.walk(addon)) {
            for (Path file : files.toList()) {
                Path target = pack.resolve(addon.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        JsonObject header = JsonParser.parseString(Files.readString(addon.resolve("manifest.json"))).getAsJsonObject().getAsJsonObject("header");
        String uuid = header.get("uuid").getAsString();
        String version = header.get("version").toString();

        // A fresh world every time: nothing of a previous run (or a previous BDS version) leaks into the dump
        Path world = server.resolve("worlds").resolve(WORLD);
        deleteRecursively(world);
        Files.createDirectories(world);
        Files.writeString(world.resolve("world_behavior_packs.json"),
            "[{\"pack_id\": \"" + uuid + "\", \"version\": " + version + "}]\n");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
