package org.geysermc.paletter.generator;

import com.google.gson.JsonParser;
import org.geysermc.paletter.capture.LevelDat;

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
 * Runs the Paletter addon on a Bedrock Dedicated Server and captures its console output.
 * <ol>
 *     <li>optionally downloads the newest BDS for this OS from the official download links API;</li>
 *     <li>installs {@code bds-addon/} as a behavior pack on a fresh flat world;</li>
 *     <li>starts BDS headless, copies its output to the log file, waits for {@code PALETTER-DONE} and stops it.</li>
 * </ol>
 * Downloading BDS means accepting the Minecraft EULA and privacy policy (https://minecraft.net/eula); BDS itself asks
 * for nothing else. Keep the server directory outside the repository.
 */
final class BdsRunner {
    static final int DUMP_PORT = 19232;
    static final String WORLD = "paletter";
    private static final String PACK_DIRECTORY = "paletter_dumper";
    private static final String PACK_UUID = "6f1d3c5e-7a1b-4c2d-9e8f-0a1b2c3d4e5f";

    private final Path addon;

    BdsRunner(Path addon) {
        this.addon = addon;
    }

    /**
     * Downloads and extracts BDS into {@code parent/<version>} unless it is already there.
     *
     * @param version a BDS version such as {@code 1.26.51.1}, or {@code null} for the newest one (or newest preview)
     * @return the server directory
     */
    static Path download(Path parent, boolean preview, String version) throws IOException, InterruptedException {
        BdsDistribution distribution = BdsDistribution.resolve(preview, version);
        Path server = parent.resolve(distribution.version() + (preview ? "-preview" : ""));
        if (Files.exists(BdsServer.executable(server))) {
            System.out.println("BDS " + distribution.version() + " is already in " + server);
            return server;
        }
        BdsDistribution.extract(distribution.zip(parent.resolve(".cache")), server);
        return server;
    }

    void run(Path server, Path log, int timeoutSeconds) throws IOException, InterruptedException {
        run(server, log, timeoutSeconds, DUMP_PORT, true);
    }

    /**
     * Installs the addon on a fresh world, runs BDS until the addon printed {@code PALETTER-DONE} and stops it.
     */
    void run(Path server, Path log, int timeoutSeconds, int port, boolean echo) throws IOException, InterruptedException {
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
    void rerun(Path server, Path log, int timeoutSeconds, int port, Collection<String> toggles) throws IOException, InterruptedException {
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

    private static void dump(Path server, Path log, int timeoutSeconds, boolean echo) throws IOException, InterruptedException {
        String result;
        try (BdsServer bds = new BdsServer(server, echo)) {
            bds.start(log);
            result = bds.await(line -> line.contains("PALETTER-DONE") || line.contains("PALETTER-FAILED"), timeoutSeconds);
        }
        if (result == null || !result.contains("PALETTER-DONE")) {
            throw new IOException("BDS exited without PALETTER-DONE (timeout " + timeoutSeconds + "s); see " + log);
        }
    }

    private void installPack(Path server) throws IOException {
        Path pack = server.resolve("behavior_packs").resolve(PACK_DIRECTORY);
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
        String version = JsonParser.parseString(Files.readString(addon.resolve("manifest.json")))
            .getAsJsonObject().getAsJsonObject("header").get("version").toString();

        // A fresh world every time: nothing of a previous run (or a previous BDS version) leaks into the dump
        Path world = server.resolve("worlds").resolve(WORLD);
        deleteRecursively(world);
        Files.createDirectories(world);
        Files.writeString(world.resolve("world_behavior_packs.json"),
            "[{\"pack_id\": \"" + PACK_UUID + "\", \"version\": " + version + "}]\n");
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
